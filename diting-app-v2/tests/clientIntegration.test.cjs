const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const plain = value => JSON.parse(JSON.stringify(value))

/** 只装载本次对接的 API 及其直接依赖；uni 网络由确定性桩代替。 */
function client(options = {}) {
  let token = 'test-token'
  let unauthorized = 0
  let uploadResponse = { code: 200, data: 'server.mp3', msg: null }
  const calls = []
  const uploads = []
  const storage = options.storage ?? {}
  const modules = new Map()
  const uni = {
    getStorageSync: key => storage[key] ?? '',
    setStorageSync: (key, value) => {
      storage[key] = value
    },
    removeStorageSync: (key) => {
      delete storage[key]
    },
    request(request) {
      calls.push(request)
      Promise.resolve().then(() => options.respond?.(request) ?? { code: 200, data: null, msg: null })
        .then(data => request.success({ statusCode: options.httpStatus ?? 200, data }))
        .catch(error => request.fail({ errMsg: error.message }))
    },
    getFileSystemManager: () => ({}),
    uploadFile(request) {
      uploads.push(request)
      request.success({ statusCode: 200, data: JSON.stringify(uploadResponse) })
      return { onProgressUpdate() {} }
    },
  }
  if (options.nativeUpload === false)
    delete uni.getFileSystemManager
  if (options.readFile)
    uni.getFileSystemManager = () => ({ readFile: options.readFile })
  function load(name, parent = '') {
    if (name === '@/utils/authToken') {
      return { getToken: () => token, clearToken: () => { token = '' } }
    }
    const file = name.startsWith('@/')
      ? path.resolve(__dirname, '../src', `${name.slice(2)}.ts`)
      : path.resolve(path.dirname(parent), `${name}.ts`)
    if (modules.has(file))
      return modules.get(file)
    const exports = {}
    modules.set(file, exports)
    const source = fs.readFileSync(file, 'utf8').replace('import.meta.env.VITE_BASE_URL', "''")
    const code = ts.transpileModule(source, { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020,
    } }).outputText
    vm.runInNewContext(code, {
      exports, require: name => load(name, file), uni, ...options.globals,
    }, { filename: file })
    return exports
  }
  const request = load('@/api/request')
  request.onUnauthorized(() => { unauthorized++ })
  return {
    load, calls, uploads, request, storage,
    setToken: value => { token = value },
    setUpload: data => { uploadResponse = data },
    get token() { return token },
    get unauthorized() { return unauthorized },
  }
}

function task(overrides = {}) {
  return {
    audio_translation_id: 12, user_id: 1, status: 7, task_status: 'COMPLETED',
    task_id: 'cloud-task', audio_duration: 12, createtime: '2026-10-17T12:00:00',
    handle_result: null, match_result: null, picture_handle_results: null, picture_match_results: null,
    ...overrides,
  }
}

async function upload(c, name, returned = `stored-${name}`) {
  const api = c.load('@/api/upload')
  c.setUpload({ code: 200, data: returned, msg: null })
  const sign = await api.signUpload(name, 100, c.token)
  await api.uploadRaw(sign, `memory/${name}`)
  await api.completeUpload(sign.uploadId, c.token)
  return sign.uploadId
}

test('登录使用表单且不附带旧 token，其他请求使用参数 token 并禁用跨域 cookie', async () => {
  const c = client({ respond: () => ({ code: 200, data: 'jwt', msg: null }) })
  await c.load('@/api/auth').loginByPassword('用户名', 'a+b&c')
  assert.equal(c.calls[0].url, 'http://localhost:8081/user/login')
  assert.equal(new URLSearchParams(c.calls[0].data).get('password'), 'a+b&c')
  await c.request.request({ url: '/user/get' })
  assert.match(c.calls[1].url, /\?token=test-token$/)
  assert.equal(c.calls[1].withCredentials, false)
  assert.equal(c.calls[1].header.Authorization, undefined)
})

test('微信 code 登录与账号密码共用表单 /user/login，不带旧 token 和账号密码', async () => {
  const c = client({ respond: () => ({ code: 200, data: 'wx-jwt', msg: null }) })
  const result = await c.load('@/api/auth').loginByWechat('wx-code', true, { a: 1 })
  assert.equal(c.calls.length, 1)
  assert.equal(c.calls[0].url, 'http://localhost:8081/user/login')
  assert.equal(c.calls[0].method, 'POST')
  const body = new URLSearchParams(c.calls[0].data)
  assert.equal(body.get('code'), 'wx-code')
  assert.equal(body.get('username'), null)
  assert.equal(body.get('password'), null)
  assert.equal(result.token, 'wx-jwt')
  assert.equal(result.userInfo.hasWechat, true)
})

test('分享归因：登录表单带 parent_user_id，成功后清除', async () => {
  const c = client({
    storage: { diting_parent_user_id: 42 },
    respond: () => ({ code: 200, data: 'jwt', msg: null }),
  })
  await c.load('@/api/auth').loginByPassword('u', 'p')
  assert.equal(new URLSearchParams(c.calls[0].data).get('parent_user_id'), '42')
  assert.equal(c.storage.diting_parent_user_id, undefined)

  // 没有归因时不发送空参数
  const plain = client({ respond: () => ({ code: 200, data: 'jwt', msg: null }) })
  await plain.load('@/api/auth').loginByWechat('wx-code')
  assert.equal(new URLSearchParams(plain.calls[0].data).has('parent_user_id'), false)
})

test('列表页码转换为从 0 起，固定每页 20 条', async () => {
  const c = client({ respond: () => ({ code: 200, data: Array.from({ length: 20 }, () => task()), msg: null }) })
  const result = await c.load('@/api/record').fetchTaskList(c.token, 2)
  assert.equal(c.calls.length, 1)
  assert.match(c.calls[0].url, /\/audio_translation\/list\?token=/)
  assert.equal(c.calls[0].data.page, 1)
  assert.equal(result.pageSize, 20)
  assert.equal(result.total, 41)
  assert.equal(result.items[0].id, 12)
})

test('详情每次使用 audio_translation_id 直接查询，转写完成不等于全流程完成', async () => {
  let status = 3
  const c = client({ respond: () => ({ code: 200, data: task({ status }), msg: null }) })
  const api = c.load('@/api/record')
  assert.equal((await api.fetchTaskStatus(12, c.token)).status, 'PARSING')
  status = 5
  assert.equal((await api.fetchTaskStatus(12, c.token)).status, 'ANALYZING')
  status = 7
  assert.equal((await api.fetchTaskStatus(12, c.token)).status, 'COMPLETED')
  status = 9
  assert.equal((await api.fetchTaskStatus(12, c.token)).status, 'FAILED')
  status = 0
  assert.equal((await api.fetchTaskStatus(12, c.token)).status, 'QUEUED')
  for (const call of c.calls) {
    assert.match(call.url, /\/audio_translation\/get\?token=/)
    assert.equal(call.data.audio_translation_id, 12)
  }
})

test('详情页并发拉报告/状态/原件只触发一次详情请求', async () => {
  const c = client({ respond: request => (request.url.includes('/configure/list')
    ? { code: 200, data: [], msg: null }
    : { code: 200, data: task(), msg: null }) })
  const api = c.load('@/api/record')
  await Promise.all([
    api.fetchReport(12, c.token),
    api.fetchTaskStatus(12, c.token),
    api.fetchAssets(12, c.token),
  ])
  const gets = c.calls.filter(call => call.url.includes('/audio_translation/get'))
  assert.equal(gets.length, 1)
  await api.fetchTaskStatus(12, c.token)
  assert.equal(c.calls.filter(call => call.url.includes('/audio_translation/get')).length, 2)
})

test('不存在或不属于当前用户的详情返回可识别的 404', async () => {
  const c = client()
  await assert.rejects(c.load('@/api/record').fetchReport(12, c.token), error => error.code === 404)
})

test('音频和图片结果解析 JSON，保留原始规则 ID 与关键词，高亮重复及重叠命中', async () => {
  const c = client({ respond: () => ({ code: 200, data: task({
    match_result: JSON.stringify([{ speaker: 'agent-A', content: '您好您好<script>', model_ids: [3, 5], hit_texts: ['您好', '好您'] }]),
    picture_match_results: JSON.stringify([{ speaker: '1', content: '图片文字', model_ids: [9], hit_texts: ['图片'] }]),
  }), msg: null }) })
  const report = await c.load('@/api/record').fetchReport(12, c.token)
  assert.equal(report.status, 'PUBLISHED')
  assert.equal(report.flaggedCount, 2)
  assert.equal(report.riskScore, null)
  assert.equal(report.sentences[0].text, '您好您好<script>')
  assert.equal(report.sentences[0].speaker, 'agent-A')
  assert.equal(report.sentences[0].hasTiming, false)
  assert.deepEqual(plain(report.sentences[0].modelIds), [3, 5])
  assert.deepEqual(plain(report.sentences[0].hitTexts), ['您好', '好您'])
  assert.deepEqual(plain(report.sentences[0].highlights), [{ sentenceId: 1, start: 0, end: 4 }])
  assert.equal(report.sentences[1].sourceKind, 'IMAGE')
  assert.equal(report.sentences[1].sourceAssetId, null)
})

test('新接口的 audio_* 字段归一化后照常生成报告', async () => {
  const c = client({ respond: () => ({ code: 200, data: task({
    audio_task_id: 'new-cloud-task',
    audio_task_status: 'COMPLETED',
    audio_handle_result: JSON.stringify([
      { speaker: '1', content: '第一句', model_ids: [], models: [], hit_texts: [] },
      { speaker: '2', content: '第二句', model_ids: [], models: [], hit_texts: [] },
    ]),
    audio_match_result: JSON.stringify([
      { speaker: '2', content: '第二句', model_ids: [7], models: [], hit_texts: ['第二'] },
    ]),
    audio_translation_result: 'null',
  }), msg: null }) })
  const report = await c.load('@/api/record').fetchReport(12, c.token)
  assert.equal(report.taskId, 'new-cloud-task')
  assert.equal(report.sentences.length, 2)
  assert.equal(report.sentences[1].text, '第二句')
  assert.deepEqual(plain(report.sentences[1].modelIds), [7])
  assert.equal(report.flaggedCount, 1)
})

test('命中句解析 models 并展示规则与法条，重复 model_id 去重', async () => {
  const model = (id, serial, type, name, provisions) => ({ model_id: id, serial_number: serial, violation_type: type, violation_name: name, legalProvisions: provisions })
  const c = client({ respond: () => ({ code: 200, data: task({
    handle_result: JSON.stringify([
      { speaker: '1', content: '王先生是吧', models: [], model_ids: [], hit_texts: [] },
      { speaker: '2', content: '我再想想办法', models: [], model_ids: [], hit_texts: [] },
    ]),
    match_result: JSON.stringify([
      { speaker: '1', content: '王先生是吧', model_ids: [100, 100, 99], models: [
        model(100, 'D28', '机构管理', '发现不宜催收账户未按流程处置', [{ legal_provision_id: 63, name: '消费者权益保护法', which: '第五十五条', content: '经营者...' }]),
        model(100, 'D28', '机构管理', '发现不宜催收账户未按流程处置', [{ legal_provision_id: 63, name: '消费者权益保护法', which: '第五十五条', content: '经营者...' }]),
        model(99, 'D27', '激进催收', '肢体冲突或突发情况瞒报', [{ legal_provision_id: 45, name: '三类银行合作通知', which: '合作机构管理', content: '' }]),
      ], hit_texts: ['王先生'] },
      { speaker: '2', content: '我再想想办法', model_ids: [], models: [], hit_texts: [] },
    ]),
  }), msg: null }) })
  const report = await c.load('@/api/record').fetchReport(12, c.token)
  assert.equal(report.flaggedCount, 1)
  const rules = report.sentences[0].rules
  assert.equal(rules.length, 2)
  assert.deepEqual(plain(rules.map(rule => rule.modelId)), [100, 99])
  assert.equal(rules[0].violationName, '发现不宜催收账户未按流程处置')
  assert.equal(rules[0].provisions[0].which, '第五十五条')
  assert.equal(report.sentences[1].rules.length, 0)
})

test('空值、非法 JSON、错误数组元素不使报告崩溃，整理原文可作为回退', async () => {
  const c = client({ respond: () => ({ code: 200, data: task({
    status: 5, match_result: '{bad', picture_match_results: 'null',
    handle_result: JSON.stringify([null, 42, { content: '原文', speaker: 0, model_ids: 'x', hit_texts: ['原', '', 42] }]),
  }), msg: null }) })
  const report = await c.load('@/api/record').fetchReport(12, c.token)
  assert.equal(report.status, 'ANALYZING')
  assert.equal(report.reportVersionId, null)
  assert.equal(report.sentences.length, 1)
  assert.deepEqual(plain(report.sentences[0].hitTexts), ['原'])
  assert.equal(report.sentences[0].speaker, '0')
})

test('handle_result 缺失时从 translation_result 的段落 Words 拼出转写原文', async () => {
  const c = client({ respond: () => ({ code: 200, data: task({
    status: 5,
    handle_result: null,
    translation_result: JSON.stringify({ Transcription: { Paragraphs: [
      { SpeakerId: 7, Words: [{ Text: '您' }, { Text: '好' }] },
      { SpeakerId: '2', Words: [{ Text: '你好' }] },
      { SpeakerId: 3, Words: [] },
    ] } }),
  }), msg: null }) })
  const report = await c.load('@/api/record').fetchReport(12, c.token)
  assert.equal(report.sentences.length, 2)
  assert.equal(report.sentences[0].text, '您好')
  assert.equal(report.sentences[0].speaker, '7')
  assert.equal(report.sentences[1].speaker, '2')
})

test('match_result 只含命中项时按原文合并，不丢失未命中的完整对话', async () => {
  const c = client({ respond: () => ({ code: 200, data: task({
    status: 7,
    handle_result: JSON.stringify([
      { speaker: '1', content: '第一句' },
      { speaker: '2', content: '第二句' },
      { speaker: '1', content: '第三句' },
    ]),
    match_result: JSON.stringify([
      { speaker: '2', content: '第二句', model_ids: [4], hit_texts: ['第二'] },
    ]),
  }), msg: null }) })
  const report = await c.load('@/api/record').fetchReport(12, c.token)
  assert.equal(report.sentences.length, 3)
  assert.deepEqual(plain(report.sentences[0].modelIds), [])
  assert.deepEqual(plain(report.sentences[1].modelIds), [4])
  assert.equal(report.sentences[1].text, '第二句')
  assert.equal(report.flaggedCount, 1)
})

test('完整对话与匹配结果等长时按下标合并命中信息', async () => {
  const c = client({ respond: () => ({ code: 200, data: task({
    status: 7,
    handle_result: JSON.stringify([
      { speaker: '1', content: '甲乙' },
      { speaker: '2', content: '甲乙' },
    ]),
    match_result: JSON.stringify([
      { speaker: '1', content: '甲乙', model_ids: [], hit_texts: [] },
      { speaker: '2', content: '甲乙', model_ids: [9], hit_texts: ['乙'] },
    ]),
  }), msg: null }) })
  const report = await c.load('@/api/record').fetchReport(12, c.token)
  assert.equal(report.sentences.length, 2)
  assert.deepEqual(plain(report.sentences[0].modelIds), [])
  assert.deepEqual(plain(report.sentences[1].modelIds), [9])
  assert.equal(report.flaggedCount, 1)
})

test('单音频提交填对源文件名与返回名，未返回 ID 时为 null', async () => {
  const c = client()
  const api = c.load('@/api/upload')
  const id = await upload(c, '原录音.mp3', '20261017.mp3')
  assert.equal(
    await api.submitTask([id], 12.2, c.token, 'key', [{ question_id: 1, question_answer_id: 2 }]),
    null,
  )
  assert.equal(c.calls.length, 1)
  const call = c.calls[0]
  assert.match(call.url, /\/audio_translation\/add\?token=/)
  const body = new URLSearchParams(call.data)
  assert.equal(body.get('audio_name'), '原录音.mp3')
  assert.equal(body.get('audio'), '20261017.mp3')
  assert.equal(body.get('audio_duration'), '13')
  assert.equal(body.get('pictures'), '')
  assert.equal(body.get('picture_names'), '')
  assert.equal(body.get('questions'), '[{"question_id":1,"question_answer_id":2}]')
  assert.equal(c.uploads[0].name, 'file')
  assert.match(c.uploads[0].url, /upload_file\?token=/)
})

test('add 返回任务 ID：数字、数字字符串与对象形态都能取出新记录 ID', async () => {
  for (const data of [42, '42', { audio_translation_id: 42 }, { id: 42 }]) {
    const c = client({ respond: () => ({ code: 200, data, msg: null }) })
    const api = c.load('@/api/upload')
    const id = await upload(c, '原录音.mp3', '20261017.mp3')
    assert.equal(await api.submitTask([id], 1, c.token), 42)
  }
})

test('六张图片按原顺序提交，音频字段为空', async () => {
  const c = client()
  const ids = []
  for (let i = 1; i <= 6; i++)
    ids.push(await upload(c, `${i}.png`, `server${i}.png`))
  await c.load('@/api/upload').submitTask(ids, undefined, c.token)
  const body = new URLSearchParams(c.calls[0].data)
  assert.equal(body.get('picture_names'), '1.png,2.png,3.png,4.png,5.png,6.png')
  assert.equal(body.get('pictures'), 'server1.png,server2.png,server3.png,server4.png,server5.png,server6.png')
  assert.equal(body.get('audio'), '')
  assert.equal(body.get('audio_name'), '')
  assert.equal(body.get('audio_duration'), '0')
})

test('允许音频与图片混合提交，仍禁止多音频、七张图及空文件', async () => {
  const c = client()
  const api = c.load('@/api/upload')
  const audio = await upload(c, 'a.mp3')
  const second = await upload(c, 'b.mp3')
  const images = []
  for (let i = 0; i < 7; i++)
    images.push(await upload(c, `${i}.jpg`))
  for (const ids of [[], [audio, second], images])
    await assert.rejects(api.submitTask(ids, 1, c.token))
  assert.equal(c.calls.length, 0)

  // 一段录音与图片可以同批提交，字段各归各位
  await api.submitTask([audio, images[0], images[1]], 5, c.token)
  const body = new URLSearchParams(c.calls[0].data)
  assert.equal(body.get('audio'), 'stored-a.mp3')
  assert.equal(body.get('audio_name'), 'a.mp3')
  assert.equal(body.get('audio_duration'), '5')
  assert.equal(body.get('pictures'), 'stored-0.jpg,stored-1.jpg')
  assert.equal(body.get('picture_names'), '0.jpg,1.jpg')
})

test('上传端限制 MP3/JPG/PNG 和单文件 10MB，拒绝空文件', async () => {
  const c = client()
  const api = c.load('@/api/upload')
  for (const name of ['a.wav', 'a.jpeg', 'a.webp'])
    await assert.rejects(api.signUpload(name, 1, c.token))
  await assert.rejects(api.signUpload('a.mp3', 0, c.token))
  await assert.rejects(api.signUpload('a.mp3', 10 * 1024 * 1024 + 1, c.token))
  await api.signUpload('a.mp3', 10 * 1024 * 1024, c.token)
})

test('普通请求和文件上传 code=202 均清理登录态，旧身份的迟到响应不清新身份', async () => {
  const c = client({ respond: () => ({ code: 202, data: null, msg: 'Token过期' }) })
  await assert.rejects(c.request.request({ url: '/user/get' }), error => error.code === 202)
  assert.equal(c.unauthorized, 1)
  assert.equal(c.token, '')
  c.setToken('new-token')
  const sign = await c.load('@/api/upload').signUpload('a.mp3', 1, 'old-token')
  c.setUpload({ code: 202, data: null, msg: 'Token过期' })
  await assert.rejects(c.load('@/api/upload').uploadRaw(sign, 'memory/a.mp3'))
  assert.equal(c.token, 'new-token')
  const current = await c.load('@/api/upload').signUpload('a.mp3', 1, c.token)
  await assert.rejects(c.load('@/api/upload').uploadRaw(current, 'memory/a.mp3'))
  assert.equal(c.token, '')
  assert.equal(c.unauthorized, 2)
})

test('配置接口带 token，失败后可重试，切换身份不复用缓存', async () => {
  let attempts = 0
  const c = client({ respond: () => ++attempts === 1
    ? { code: 201, data: null, msg: '失败' }
    : { code: 200, data: [{ key: '最长录音时长', value: '30' }], msg: null } })
  const api = c.load('@/api/configure')
  await assert.rejects(api.fetchConfigure())
  assert.equal((await api.fetchConfigure())[0].value, '30')
  await api.fetchConfigure()
  assert.equal(c.calls.length, 2)
  c.setToken('another')
  await api.fetchConfigure()
  assert.equal(c.calls.length, 3)
  assert.match(c.calls[2].url, /token=another/)
  c.setToken('')
  await assert.rejects(api.fetchConfigure())
  assert.equal(c.calls.length, 3)
})

test('PDF 只传 token、不携带 cookie；当前返回的是真 PDF 才生成下载地址', async () => {
  let requested
  const c = client({ globals: {
    document: {},
    fetch: async (...args) => {
      requested = args
      return new Response('%PDF-1.4 test', { headers: { 'content-type': 'application/pdf' } })
    },
    URL: { createObjectURL: () => 'blob:pdf' },
  } })
  const result = await c.load('@/api/record').fetchExportDownload(1, c.token)
  assert.equal(requested[0], 'http://localhost:8081/pdf/export?token=test-token&audio_translation_id=1')
  assert.equal(requested[1].credentials, 'omit')
  assert.equal(result.downloadUrl, 'blob:pdf')
})

test('导出标识使用记录自身的 audio_translation_id，不再返回写死的 1', async () => {
  const c = client()
  const job = await c.load('@/api/record').createExport(37, 5, c.token)
  assert.equal(job.exportId, 37)
  assert.equal(job.status, 'COMPLETED')
})

test('PDF 导出带上 audio_translation_id，token 仍在 URL 上', async () => {
  let requested
  const c = client({ globals: {
    document: {},
    fetch: async (...args) => {
      requested = args
      return new Response('%PDF-1.4 test', { headers: { 'content-type': 'application/pdf' } })
    },
    URL: { createObjectURL: () => 'blob:pdf' },
  } })
  await c.load('@/api/record').fetchExportDownload(1, c.token, 88)
  assert.equal(requested[0], 'http://localhost:8081/pdf/export?token=test-token&audio_translation_id=88')
})

test('PDF 接口返回 HTTP 200 的鉴权 JSON 时不能保存为 PDF', async () => {
  const c = client({ globals: {
    document: {},
    fetch: async () => new Response(JSON.stringify({ code: 202, data: null, msg: 'Token过期' }), {
      headers: { 'content-type': 'application/json' },
    }),
  } })
  await assert.rejects(c.load('@/api/record').fetchExportDownload(1, c.token), error => error.code === 202)
  assert.equal(c.token, '')
})

test('本次修改的 Vue 组件和页面可独立编译，不执行全局 build/typecheck/lint', () => {
  for (const relative of ['pages/upload/index.vue', 'pages/report/detail.vue', 'components/SentenceItem.vue', 'components/UploadSelection.vue', 'components/ReportExportActions.vue']) {
    const filename = path.join(__dirname, '../src', relative)
    const { descriptor, errors } = parse(fs.readFileSync(filename, 'utf8'), { filename })
    assert.equal(errors.length, 0)
    const script = compileScript(descriptor, { id: relative })
    const result = compileTemplate({ source: descriptor.template.content, filename, id: relative, compilerOptions: { bindingMetadata: script.bindings } })
    assert.equal(result.errors.length, 0, relative)
  }
  const source = fs.readFileSync(path.join(__dirname, '../src/components/SentenceItem.vue'), 'utf8')
  assert.doesNotMatch(source, /model_ids/)
  assert.doesNotMatch(source, /hit_texts/)
  assert.match(source, /sentence\.rules/)
  assert.doesNotMatch(source, /v-html/)
})


test('小程序 PDF 下载也识别 HTTP 200 的过期信封，不把 JSON 当文档打开', async () => {
  const data = new TextEncoder().encode(JSON.stringify({ code: 202, data: null, msg: 'Token过期，请登录' })).buffer
  const c = client({ readFile: options => options.success({ data }) })
  await assert.rejects(c.load('@/api/record').validateDownloadedPdf('/tmp/report.pdf', 200, c.token), error => error.code === 202)
  assert.equal(c.token, '')
  assert.equal(c.unauthorized, 1)
})

test('小程序真实 PDF 文件通过签名校验', async () => {
  const data = new TextEncoder().encode('%PDF-1.4 test').buffer
  const c = client({ readFile: options => options.success({ data }) })
  await c.load('@/api/record').validateDownloadedPdf('/tmp/report.pdf', 200, c.token)
})

test('H5 multipart 使用 omit，不手写 boundary，上传返回名原样用于任务参数', async () => {
  const requests = []
  const c = client({ nativeUpload: false, globals: {
    fetch: async (url, init) => {
      requests.push({ url, init })
      return url.startsWith('blob:') ? new Response('audio')
        : new Response(JSON.stringify({ code: 200, data: 'opaque-file-name.mp3', msg: null }))
    },
    FormData,
  } })
  const api = c.load('@/api/upload')
  const sign = await api.signUpload('原文件.mp3', 10, c.token)
  const progress = []
  await api.uploadRaw(sign, 'blob:audio', value => progress.push(value))
  assert.equal(requests[1].init.credentials, 'omit')
  assert.equal(requests[1].init.method, 'POST')
  assert.equal(requests[1].init.headers['Content-Type'], undefined)
  assert.equal(requests[1].init.body.get('file').name, '原文件.mp3')
  assert.deepEqual(progress, [null, 100])
  await api.submitTask([sign.uploadId], 1, c.token)
  assert.equal(new URLSearchParams(c.calls[0].data).get('audio'), 'opaque-file-name.mp3')
})

test('上传成功信封未返回文件名时不能登记任务', async () => {
  const c = client()
  const api = c.load('@/api/upload')
  const sign = await api.signUpload('a.mp3', 1, c.token)
  c.setUpload({ code: 200, data: null, msg: null })
  await assert.rejects(api.uploadRaw(sign, 'memory/a.mp3'))
  await assert.rejects(api.completeUpload(sign.uploadId, c.token))
})


test('H5 表单请求和查询均显式禁用 cookie，业务失败正常拒绝', async () => {
  const requests = []
  const c = client({ globals: {
    document: {},
    fetch: async (url, init) => {
      requests.push({ url, init })
      return new Response(JSON.stringify({ code: 201, data: null, msg: '业务失败' }))
    },
  } })
  await assert.rejects(c.request.request({ url: '/audio_translation/list', data: { page: 0 } }), error => error.code === 201)
  assert.match(requests[0].url, /page=0/)
  assert.equal(requests[0].init.credentials, 'omit')
  await assert.rejects(c.request.request({ url: '/user/login', method: 'POST', form: true, token: '', data: { username: 'a+b', password: 'c&d' } }))
  assert.equal(requests[1].init.credentials, 'omit')
  assert.equal(requests[1].init.headers['Content-Type'], 'application/x-www-form-urlencoded')
  assert.equal(new URLSearchParams(requests[1].init.body).get('password'), 'c&d')
  assert.equal(c.unauthorized, 0)
})
