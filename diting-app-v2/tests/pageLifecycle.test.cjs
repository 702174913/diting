const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

function pageHarness(page, options = {}) {
  const hooks = {}
  const timers = new Set()
  const timeouts = new Set()
  const toasts = []
  const navigations = []
  const store = { token: 'user-token', userInfo: { id: 7 }, ensureLogin: options.login || (async () => true) }
  class ApiError extends Error {
    constructor(code, message) {
      super(message)
      this.code = code
    }
  }
  let requests = 0
  const dependencies = {
    'vue': { ...vue, onUnmounted: (cb) => { hooks.onUnmounted = cb } },
    '@dcloudio/uni-app': Object.fromEntries(['onLoad', 'onShow', 'onHide', 'onUnload', 'onShareAppMessage'].map(name => [name, (cb) => { hooks[name] = cb }])),
    '@/stores/user': { useUserStore: () => store },
    '@/utils/h5Auth': { requireH5Login: () => true },
    // 冷启动授权门禁：本文件测的是上传/记录流程，按「已同意」处理
    '@/utils/consentGate': { hasFullAccess: () => true },
    '@/utils/share': { useShareContent: () => () => ({}) },
    '@/utils/demo': { DEMO_MODE: false },
    '@/api/record': {
      fetchTaskStatus: async () => {
        requests++
        return { status: options.status || 'TRANSCRIBING' }
      },
      ...options.recordApi,
    },
    '@/api/request': { ApiError },
    '@/api/upload': options.uploadApi || {},
    '@/api/question': { fetchSurveyQuestions: async () => [] },
    '@/utils/audioDuration': { readAudioDurationMs: options.readAudioDurationMs || (async () => 60_000) },
    '@/utils/errors': { messageOf: () => '加载失败，请重试' },
    '@/utils/idempotency': { newIdempotencyKey: () => `upload-${Math.random()}` },
    '@/utils/choose': {
      MAX_IMAGES: 6,
      isImage: file => /\.(?:png|jpg)$/i.test(file.name),
      releaseChosenFile() {},
      ...options.chooseApi,
    },
  }
  const sandbox = {
    setInterval: (cb) => {
      timers.add(cb)
      return cb
    },
    clearInterval: cb => timers.delete(cb),
    // 延时任务只登记不自动执行：由用例决定何时推进（跳转时序要可观察）
    setTimeout: (cb) => {
      timeouts.add(cb)
      return cb
    },
    clearTimeout: cb => timeouts.delete(cb),
    uni: {
      showToast: options => toasts.push(options),
      // 导出/下载的进度反馈：真实 uni 两个都要有，存根必须跟上，否则 mock 比线上“宽容”
      showLoading: options => toasts.push(options),
      hideLoading() {},
      downloadFile() {},
      navigateTo: options => navigations.push(options.url),
      switchTab: options => navigations.push(options.url),
      redirectTo: options => navigations.push(options.url),
      navigateBack() {},
      setNavigationBarTitle() {},
      ...options.uni,
    },
  }
  function evaluate(source, filename) {
    const exports = {}
    const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText
    vm.runInNewContext(output, { ...sandbox, exports, require: load }, { filename })
    return exports
  }
  function load(name) {
    if (dependencies[name])
      return dependencies[name]
    if (['@/utils/taskStatus', '@/utils/polling', '@/utils/reportPresentation', '@/utils/survey'].includes(name)) {
      return evaluate(fs.readFileSync(path.join(__dirname, '../src', `${name.slice(2)}.ts`), 'utf8'), name)
    }
    return {}
  }
  const filename = path.join(__dirname, `../src/pages/${page}/${page === 'report' ? 'detail' : 'index'}.vue`)
  const { descriptor, errors } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: page })
  assert.equal(compileTemplate({ source: descriptor.template.content, filename, id: page, compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  const component = evaluate(script.content, filename).default
  const state = component.setup({}, { expose() {} })
  /** 推进登记的延时任务（上传成功后的跳转靠它触发） */
  function runTimeouts() {
    const pending = [...timeouts]
    timeouts.clear()
    pending.forEach(cb => cb())
  }
  return {
    hooks,
    timers,
    timeouts,
    runTimeouts,
    state,
    ApiError,
    requests: () => requests,
    toasts,
    navigations,
    template: descriptor.template.content,
  }
}

const flush = () => new Promise(resolve => setImmediate(resolve))

test('提交响应丢失后先回列表确认，不重复登记任务', async () => {
  let signs = 0
  let puts = 0
  let completes = 0
  let quotaChecks = 0
  const submissions = []
  const h = pageHarness('upload', {
    uploadApi: {
      fetchQuota: async () => ({ remaining: ++quotaChecks === 1 ? 1 : 0, maxRecordingSeconds: 600 }),
      signUpload: async () => ({ uploadId: ++signs }),
      uploadRaw: async () => { puts++ },
      completeUpload: async () => {
        completes++
        return { durationMs: 1000 }
      },
      submitTask: async (ids, duration, _token, key) => {
        submissions.push({ ids: [...ids], duration, key })
        if (submissions.length === 1) {
          throw new Error('response lost')
        }
        return 88
      },
    },
  })
  h.state.pendingFiles.value = [{ name: 'audio.m4a', path: 'memory/audio', size: 123 }]
  await h.state.confirmUpload()
  assert.equal(h.state.attempt.value.submitted, true)
  assert.equal(h.state.pendingFiles.value.length, 1)
  h.state.removeFile('memory/audio')
  assert.equal(h.state.pendingFiles.value.length, 1)
  await h.state.confirmUpload()
  assert.equal(signs, 1)
  assert.equal(puts, 1)
  assert.equal(completes, 1)
  assert.equal(quotaChecks, 1)
  assert.equal(submissions.length, 1)
  assert.equal(h.state.attempt.value, null)
  h.runTimeouts()
  assert.deepEqual(h.navigations, ['/pages/history/index'])
})

test('报告仅对404显示不存在，网络错误保留重试入口', async () => {
  let error
  const h = pageHarness('report', {
    recordApi: {
      fetchReport: async () => {
        throw error
      },
    },
  })
  error = new h.ApiError(503, 'unavailable')
  await h.state.loadReport(12)
  assert.match(h.state.loadError.value, /重试/)
  assert.equal(h.state.report.value, null)
  error = new h.ApiError(404, 'missing')
  await h.state.loadReport(12)
  assert.equal(h.state.loadError.value, '')
  assert.match(h.template, /@click="retryReport"/)
})

test('导出轮询超时后继续查询已有作业，不重复创建', async () => {
  let creates = 0
  let waits = 0
  const ids = []
  const h = pageHarness('report', {
    recordApi: {
      createExport: async () => ({ exportId: ++creates }),
      waitExport: async (id) => {
        ids.push(id)
        return ++waits === 1 ? null : { status: 'COMPLETED', fileType: 'pdf' }
      },
      fetchExportDownload: async () => ({ downloadUrl: 'https://example.test/file' }),
    },
  })
  h.state.report.value = { id: 12, reportVersionId: 4, status: 'PUBLISHED' }
  await h.state.save(false)
  await h.state.save(false)
  assert.equal(creates, 1)
  assert.deepEqual(ids, [1, 1])
})

test('记录页 WAITING_ROLE 继续轮询，且不要求用户确认角色', async () => {
  const h = pageHarness('report', {
    status: 'WAITING_ROLE',
    recordApi: {
      fetchReport: async () => ({ id: 12, taskStatus: 'WAITING_ROLE', status: null, reportVersionId: null }),
      fetchAssets: async () => [],
    },
  })
  await h.hooks.onLoad({ id: '12' })
  h.hooks.onShow()
  await flush()
  assert.equal(h.requests(), 1)
  assert.equal(h.timers.size, 1)
  assert.equal(h.state.taskStatus.value, 'WAITING_ROLE')
  assert.match(h.template, /<ProcessingCard/)
  assert.doesNotMatch(h.template, /needsRoleConfirm|去确认说话人角色/)
  h.hooks.onUnload()
})

test('录音与图片可以一起交：选一种不清空另一种', async () => {
  const released = []
  const h = pageHarness('upload', {
    chooseApi: {
      releaseChosenFile: file => released.push(file.path),
      chooseAudioFile: async () => ({ name: 'call.m4a', path: 'blob:audio', size: 20 }),
    },
    uploadApi: { fetchQuota: async () => ({ remaining: 1, maxRecordingSeconds: 600 }) },
  })

  // 已有图片时选录音：录音排在最前，图片照旧保留，摘要同时列出两类
  h.state.pendingFiles.value = [{ name: '1.png', path: 'blob:img', size: 10 }]
  await h.state.chooseFile()
  assert.equal(h.state.pendingFiles.value.map(file => file.name).join(','), 'call.m4a,1.png')
  assert.equal(h.state.pendingSummary.value, '录音 1 个（call.m4a），图片 1 张')
  assert.deepEqual(released, [])

  // 提交结果未确认时仍不允许改动本批文件：换掉的会话不能丢
  h.state.attempt.value = { key: 'k', ownerId: 7, submitted: true, uploads: [] }
  assert.equal(h.state.canChangeFiles(), false)
})

test('选中超长录音立即拦截，保留之前的文件且不上传 OSS', async () => {
  const released = []
  const h = pageHarness('upload', {
    chooseApi: {
      chooseAudioFile: async () => ({ name: 'long.mp3', path: 'blob:long', size: 100 }),
      releaseChosenFile: file => released.push(file.path),
    },
    readAudioDurationMs: async () => 600_001,
    uploadApi: { fetchQuota: async () => ({ remaining: 1, maxRecordingSeconds: 600 }) },
  })
  h.state.pendingFiles.value = [{ name: 'old.mp3', path: 'blob:old', size: 100, durationMs: 10_000 }]
  await h.state.chooseFile()
  assert.equal(h.state.pendingFiles.value[0].name, 'old.mp3')
  assert.deepEqual(released, ['blob:long'])
  assert.match(h.toasts.at(-1).title, /超过 600 秒/)
})

test('恰好达到上限可上传，提交任务时带上声明时长与问卷作答', async () => {
  const submissions = []
  const h = pageHarness('upload', {
    uploadApi: {
      fetchQuota: async () => ({ remaining: 1, maxRecordingSeconds: 600 }),
      signUpload: async () => ({ uploadId: 1 }),
      uploadRaw: async () => {},
      completeUpload: async () => ({ durationMs: null }),
      submitTask: async (_ids, duration, _token, _key, questions) => {
        submissions.push({ duration, questions: JSON.stringify(questions) })
        return 88
      },
    },
  })
  h.state.pendingFiles.value = [{ name: 'call.mp3', path: 'blob:call', size: 100, durationMs: 600_000 }]
  h.state.surveyQuestions.value = [
    { id: 1, title: '第一题', options: [{ key: 'A', label: '是', answerId: 1 }] },
    { id: 5, title: '第五题', options: [{ key: 'C', label: '不确定', answerId: 11 }] },
  ]
  h.state.surveyAnswers.value = { 1: 'A', 5: 'C' }
  await h.state.confirmUpload()
  assert.deepEqual(submissions, [{
    duration: 600,
    questions: '[{"question_id":1,"question_answer_id":1},{"question_id":5,"question_answer_id":11}]',
  }])
  assert.deepEqual({ ...h.state.surveyAnswers.value }, {})
})

test('配置改变后正式签发前再次拦截超长录音，不发签名也不上传 OSS', async () => {
  let signs = 0
  let puts = 0
  let quotaChecks = 0
  const h = pageHarness('upload', {
    chooseApi: { chooseAudioFile: async () => ({ name: 'call.mp3', path: 'blob:call', size: 100 }) },
    readAudioDurationMs: async () => 90_000,
    uploadApi: {
      fetchQuota: async () => ({ remaining: 1, maxRecordingSeconds: ++quotaChecks === 1 ? 120 : 60 }),
      signUpload: async () => {
        signs++
        return { uploadId: 1 }
      },
      uploadRaw: async () => { puts++ },
    },
  })
  await h.state.chooseFile()
  assert.equal(h.state.pendingFiles.value[0].durationMs, 90_000)
  await h.state.confirmUpload()
  assert.equal(signs, 0)
  assert.equal(puts, 0)
  assert.match(h.toasts.at(-1).title, /超过 60 秒/)
})

test('时长无法读取或上限接口不可用时拒绝上传，不伪装为合格录音', async () => {
  let signs = 0
  const h = pageHarness('upload', {
    readAudioDurationMs: async () => { throw new Error('无法读取录音时长，请换文件') },
    uploadApi: {
      fetchQuota: async () => ({ remaining: 1, maxRecordingSeconds: 600 }),
      signUpload: async () => {
        signs++
        return { uploadId: 1 }
      },
    },
  })
  h.state.pendingFiles.value = [{ name: 'bad.amr', path: 'memory/bad', size: 123 }]
  await h.state.confirmUpload()
  assert.equal(signs, 0)
  assert.match(h.toasts.at(-1).title, /无法读取录音时长/)

  h.state.pendingFiles.value = [{ name: 'call.mp3', path: 'memory/call', size: 123, durationMs: 30_000 }]
  h.state.maxSeconds.value = null
  const offline = pageHarness('upload', {
    uploadApi: {
      fetchQuota: async () => { throw new Error('offline') },
      signUpload: async () => {
        signs++
        return { uploadId: 1 }
      },
    },
  })
  offline.state.pendingFiles.value = h.state.pendingFiles.value
  await offline.state.confirmUpload()
  assert.equal(signs, 0)
  assert.match(offline.toasts.at(-1).title, /无法获取录音时长上限/)
})

test('上传页不再内联进度：提交完只负责提示与跳转', async () => {
  const h = pageHarness('upload')
  await flush()
  assert.equal(h.timers.size, 0)
  assert.doesNotMatch(h.template, /<ProcessingCard/)
})

test('上传成功后先提示再进新记录详情页', async () => {
  const h = pageHarness('upload', {
    uploadApi: {
      fetchQuota: async () => ({ remaining: 1, maxRecordingSeconds: 600 }),
      signUpload: async () => ({ uploadId: 1 }),
      uploadRaw: async () => {},
      completeUpload: async () => ({ durationMs: 1000 }),
      submitTask: async () => 88,
    },
  })
  h.state.pendingFiles.value = [{ name: 'audio.m4a', path: 'memory/audio', size: 123 }]
  await h.state.confirmUpload()
  assert.equal(h.state.pendingFiles.value.length, 0)
  assert.equal(h.state.attempt.value, null)
  assert.equal(h.navigations.length, 0)
  assert.deepEqual(
    { title: h.toasts[0].title, icon: h.toasts[0].icon },
    { title: '上传成功', icon: 'success' },
  )
  h.runTimeouts()
  assert.deepEqual(h.navigations, ['/pages/report/detail?id=88'])
  h.hooks.onUnmounted()
})

test('提示期间被切走就不弹跳：待跳转的定时器随页面隐藏撤回', async () => {
  const h = pageHarness('upload', {
    uploadApi: {
      fetchQuota: async () => ({ remaining: 1, maxRecordingSeconds: 600 }),
      signUpload: async () => ({ uploadId: 1 }),
      uploadRaw: async () => {},
      completeUpload: async () => ({ durationMs: 1000 }),
      submitTask: async () => 88,
    },
  })
  h.state.pendingFiles.value = [{ name: 'audio.m4a', path: 'memory/audio', size: 123 }]
  await h.state.confirmUpload()
  h.hooks.onHide()
  h.runTimeouts()
  assert.deepEqual(h.navigations, [])
})

test('进度页只做重定向：编译通过、不发请求、不动登录态', async () => {
  const h = pageHarness('progress')
  h.hooks.onLoad({ id: '12' })
  assert.deepEqual(h.navigations, ['/pages/report/detail?id=12'])
  assert.equal(h.requests(), 0)
  assert.equal(h.timers.size, 0)
})

test('记录页隐藏后登录异步续体不得启动轮询', async () => {
  let resolveLogin
  const h = pageHarness('report', {
    login: () => new Promise((resolve) => {
      resolveLogin = resolve
    }),
    recordApi: {
      fetchReport: async () => ({ id: 12, taskStatus: 'TRANSCRIBING', status: null, reportVersionId: null }),
      fetchAssets: async () => [],
    },
  })
  const loading = h.hooks.onLoad({ id: '12' })
  h.hooks.onShow()
  h.hooks.onHide()
  resolveLogin(true)
  await loading
  await flush()
  assert.equal(h.timers.size, 0)
  assert.equal(h.requests(), 0)
  h.hooks.onShow()
  await flush()
  assert.equal(h.timers.size, 1)
  assert.equal(h.requests(), 1)
  h.hooks.onUnload()
})

test('记录页处理中只给进度，完成后自动拉正式报告并停轮询', async () => {
  let status = 'TRANSCRIBING'
  let reports = 0
  const h = pageHarness('report', {
    recordApi: {
      fetchTaskStatus: async () => ({ status, durationSec: 41 }),
      fetchReport: async () => {
        reports++
        return {
          id: 12,
          taskStatus: status,
          status: status === 'COMPLETED' ? 'PUBLISHED' : null,
          reportVersionId: status === 'COMPLETED' ? 3 : null,
        }
      },
      fetchAssets: async () => [],
    },
  })
  await h.hooks.onLoad({ id: '12' })
  h.hooks.onShow()
  await flush()
  assert.equal(h.state.taskStatus.value, 'TRANSCRIBING')
  assert.equal(h.state.running.value, true)
  assert.match(h.template, /<ProcessingCard/)
  assert.equal(h.state.cardDurationSec.value, 41)
  assert.equal(reports, 1)
  assert.equal(h.timers.size, 1)

  status = 'COMPLETED'
  await h.state.polling.tick()
  await flush()
  assert.equal(h.state.running.value, false)
  assert.equal(reports, 2)
  assert.equal(h.timers.size, 0)
  h.hooks.onUnload()
})

test('记录在别处被删除后，处理中的页面不留下旧内容', async () => {
  let status = 'TRANSCRIBING'
  const h = pageHarness('report', {
    recordApi: {
      fetchTaskStatus: async () => ({ status }),
      fetchReport: async () => ({ id: 12, taskStatus: status, status: null, reportVersionId: null, sentences: [] }),
      fetchAssets: async () => [],
    },
  })
  await h.hooks.onLoad({ id: '12' })
  h.hooks.onShow()
  await flush()
  assert.equal(h.state.running.value, true)

  status = 'DELETED'
  await h.state.polling.tick()
  await flush()
  assert.equal(h.state.running.value, false)
  assert.equal(h.state.report.value, null)
  assert.equal(h.timers.size, 0)
  h.hooks.onUnload()
})
