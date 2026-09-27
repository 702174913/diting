const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const pinia = require('pinia')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

function evaluate(relative, dependencies = {}, globals = {}) {
  const filename = path.join(__dirname, '../src', relative)
  let source = fs.readFileSync(filename, 'utf8')
  if (relative.endsWith('.vue')) {
    const { descriptor, errors } = parse(source, { filename })
    assert.equal(errors.length, 0)
    const script = compileScript(descriptor, { id: relative })
    assert.equal(compileTemplate({ source: descriptor.template.content, filename, id: relative, compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
    source = script.content
  }
  // import.meta 在 vm 的 CJS 脚本里不可用：所有 import.meta.env.X 一律替换成空串
  // （BASE_URL / VITE_API_BASE 在测试里都按「根路径、同源」取值）
  source = source.replaceAll(/import\.meta\.env\.[A-Z0-9_]+/g, '\'\'')
  const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, esModuleInterop: true, target: ts.ScriptTarget.ES2020 } }).outputText
  const exports = {}
  vm.runInNewContext(output, {
    exports,
    require: name => dependencies[name] || ({ vue, pinia }[name]) || {},
    ...globals,
  }, { filename })
  return exports
}

test('403失效权限清理身份并替换为固定站内登录路径', async () => {
  let cleared = 0
  const redirects = []
  const module = evaluate('api/request.ts', {
    'axios': () => Promise.reject(Object.assign(new Error('Forbidden'), {
      response: { status: 403, data: { code: 403, symbol: 'ACCOUNT_DISABLED' } },
    })),
    '@/utils/authToken': { getToken: () => 'token', clearAuth: () => { cleared++ } },
  }, { window: { location: { pathname: '/recordings', replace: url => redirects.push(url) } } })
  await assert.rejects(module.request({ url: '/api/admin/recordings' }), error => error.code === 403)
  assert.equal(cleared, 1)
  assert.deepEqual(redirects, ['/login'])
})

test('导出历史缓存去除签名，后续下载不写回地址', async () => {
  pinia.setActivePinia(pinia.createPinia())
  let saved = JSON.stringify([{ exportId: 1, status: 'COMPLETED', downloadUrl: 'https://old.test/secret' }])
  const module = evaluate('stores/exportTask.ts', {
    '@/stores/user': { useUserStore: () => ({ token: 'token' }) },
    '@/api/admin': { fetchExportDownload: async () => ({ downloadUrl: 'https://new.test/secret' }) },
    'vue-sonner': { toast: { success() {}, error() {} } },
  }, { localStorage: { getItem: () => saved, setItem: (_key, value) => { saved = value } } })
  const store = module.useExportTaskStore()
  assert.doesNotMatch(saved, /downloadUrl|secret/)
  assert.equal(await store.download(1), 'https://new.test/secret')
  assert.doesNotMatch(JSON.stringify(store.jobs), /downloadUrl|secret/)
  assert.doesNotMatch(saved, /downloadUrl|secret/)
})

test('协议保存采用服务端清洗正文且保存后dirty归零', async () => {
  const module = evaluate('views/agreements/index.vue', {
    'vue': { ...vue, onMounted() {} },
    '@/stores/user': { useUserStore: () => ({ token: 'token', role: 'ADMIN' }) },
    '@/api/admin': { saveAgreement: async () => ({ key: 'a', title: '标题', version: 2, contentHtml: '<p>安全正文</p>' }) },
    'vue-sonner': { toast: { success() {}, error() {} } },
  })
  const state = module.default.setup({}, { expose() {} })
  state.items.value = [{ key: 'a', title: '标题', version: 1, contentHtml: '原正文' }]
  state.activeKey.value = 'a'
  state.drafts.value = { a: { title: '标题', contentHtml: '未清洗的正文' } }
  state.loaded.value = true
  assert.equal(state.dirty.value, true)
  await state.save()
  assert.equal(state.dirty.value, false)
  assert.equal(state.active.value.contentHtml, '<p>安全正文</p>')
  assert.equal(state.draft.value.contentHtml, '<p>安全正文</p>')
})

test('时间范围映射真实参数；切换范围后旧响应不能覆盖新数据', async () => {
  pinia.setActivePinia(pinia.createPinia())
  const memory = new Map()
  const viewModule = evaluate('stores/view.ts', {}, {
    localStorage: { getItem: key => memory.get(key), setItem: (key, value) => memory.set(key, value) },
  })
  const view = viewModule.useViewStore()
  assert.equal(view.apiPeriod, '30d')
  view.setRange('QUARTER')
  assert.equal(view.apiPeriod, 'quarter')
  view.setRange('30D')
  const calls = []
  let resolveOld
  const response = value => ({ from: '2026-09-01', to: '2026-09-26', metrics: [{ key: 'reports', value }], series: [], breakdown: [] })
  const module = evaluate('components/MetricsPanel.vue', {
    'vue': { ...vue, onUnmounted() {} },
    '@/stores/user': { useUserStore: () => ({ token: 'token' }) },
    '@/stores/view': { useViewStore: () => view },
    '@/api/request': { ApiError: class extends Error {} },
    'vue-sonner': { toast: { error() {} } },
    '@/api/stats': {
      fetchStats: async (_token, _section, range) => {
        calls.push(range)
        if (range.period === '30d') {
          return new Promise((resolve) => {
            resolveOld = resolve
          })
        }
        return response(range.period === 'today' ? 1 : 0)
      },
    },
  })
  const state = module.default.setup(vue.reactive({ section: 'overview' }), { expose() {} })
  view.setRange('TODAY')
  await vue.nextTick()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(state.metrics.value[0].value, 1)
  resolveOld(response(99))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(state.metrics.value[0].value, 1)
  assert.equal(calls[0].period, '30d')
  assert.equal(calls[1].period, 'today')
  // 统计面收敛为总览看板一个（其余统计页已下线），这里只校总览页不再出现过期参数写法
  for (const page of ['overview']) {
    const source = fs.readFileSync(path.join(__dirname, `../src/views/${page}/index.vue`), 'utf8')
    assert.doesNotMatch(source, /period="30d"/)
  }
})

test('看板失败可重试，全零序列有明确说明', async () => {
  let fail = true
  const module = evaluate('components/MetricsPanel.vue', {
    'vue': { ...vue, onUnmounted() {} },
    '@/stores/user': { useUserStore: () => ({ token: 'token' }) },
    '@/stores/view': { useViewStore: () => ({ apiPeriod: 'today' }) },
    '@/api/request': { ApiError: class extends Error {} },
    'vue-sonner': { toast: { error() {} } },
    '@/api/stats': {
      fetchStats: async () => {
        if (fail) {
          throw new Error('network')
        }
        return { from: '2026-09-26', to: '2026-09-26', metrics: [], series: [], breakdown: [] }
      },
    },
  })
  const state = module.default.setup(vue.reactive({ section: 'overview' }), { expose() {} })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(state.error.value, '指标加载失败')
  fail = false
  await state.load()
  assert.equal(state.error.value, '')
  const source = fs.readFileSync(path.join(__dirname, '../src/components/MetricsPanel.vue'), 'utf8')
  // 只断言「按钮绑 load 且文案是重新加载」，不锁死在模板里怎么换行（lint --fix 会重排）
  assert.match(source, /@click="load"[\s\S]{0,80}重新加载/)
  assert.match(source, /本区间所有数据点均为 0/)
})

test('审计权限矩阵只列服务端三角色，音频签发时长与服务端一致', () => {
  const client = fs.readFileSync(path.join(__dirname, '../src/views/audit/index.vue'), 'utf8')
  const serverPath = path.join(__dirname, '../../diting-server/src/main/java/com/diting/controller/AdminRecordingController.java')
  const server = fs.readFileSync(serverPath, 'utf8')
  const roles = [...client.matchAll(/\{ role: '[^']+（(ADMIN|OPERATOR|INVESTOR)）'/g)].map(match => match[1])
  assert.deepEqual(roles, ['INVESTOR', 'OPERATOR', 'ADMIN'])
  const clientTtl = client.match(/AUDIO_LINK_TTL_MINUTES = (\d+)/)?.[1]
  const serverTtl = server.match(/AUDIO_TTL_MINUTES = (\d+)/)?.[1]
  assert.equal(clientTtl, serverTtl)
  assert.match(client, /role: '运营（OPERATOR）'[^\n]*exportable: '✕'/)
})

test('服务端复用同一份产物时，明确点击导出仍然真的下载（不被「已下载过」挡住）', async () => {
  pinia.setActivePinia(pinia.createPinia())
  const completed = () => ({ exportId: 1025, status: 'COMPLETED', withAudio: false, fileType: 'pdf', errorCode: null, createdAt: null, expiresAt: null })
  // 上一个会话已经自动下载过 1025，localStorage 里带着 autoDownloaded: true
  const stored = { value: JSON.stringify([{ ...completed(), recordingId: 1046, reportNo: 'DT-2026-001046', reportVersion: 3, autoDownloaded: true }]) }
  const toasts = []
  const clicks = []
  let signed = 0
  const module = evaluate('stores/exportTask.ts', {
    '@/stores/user': { useUserStore: () => ({ token: 'token' }) },
    '@/api/request': { ApiError: class extends Error {} },
    '@/api/admin': {
      fetchExportJob: async () => completed(),
      fetchExportDownload: async (id) => {
        signed++
        return { downloadUrl: `https://signed.test/${id}.pdf` }
      },
    },
    'vue-sonner': { toast: { success: message => toasts.push(message), error: message => toasts.push(message) } },
  }, {
    localStorage: { getItem: () => stored.value, setItem: (_key, value) => { stored.value = value } },
    document: {
      createElement: () => ({ style: {}, click: () => clicks.push('download'), remove() {} }),
      body: { appendChild() {} },
    },
    setInterval: () => 0,
    clearInterval: () => {},
  })
  const store = module.useExportTaskStore()
  assert.equal(store.jobs[0].autoDownloaded, true)
  const result = await store.track(completed(), { recordingId: 1046, reportNo: 'DT-2026-001046', reportVersion: 3 })
  assert.equal(signed, 1)
  assert.equal(clicks.length, 1)
  assert.equal(result.downloaded, true)
  // 成功文案由页面那条 loading→success 给，store 不重复嘘声
  assert.deepEqual(toasts, [])
})

test('关掉自动下载时点导出：产物已就绪也要明确告知去哪儿下载', async () => {
  pinia.setActivePinia(pinia.createPinia())
  const completed = () => ({ exportId: 1026, status: 'COMPLETED', withAudio: false, fileType: 'pdf', errorCode: null, createdAt: null, expiresAt: null })
  const toasts = []
  const clicks = []
  const module = evaluate('stores/exportTask.ts', {
    '@/stores/user': { useUserStore: () => ({ token: 'token' }) },
    '@/api/request': { ApiError: class extends Error {} },
    '@/api/admin': {
      fetchExportJob: async () => completed(),
      fetchExportDownload: async id => ({ downloadUrl: `https://signed.test/${id}.pdf` }),
    },
    'vue-sonner': { toast: { success: message => toasts.push(message), error: message => toasts.push(message) } },
  }, {
    // 用户手动关过自动下载：AUTO_DOWNLOAD_KEY = '0'
    localStorage: { getItem: key => (key === 'diting_admin_export_auto_download' ? '0' : null), setItem: () => {} },
    document: {
      createElement: () => ({ style: {}, click: () => clicks.push('download'), remove() {} }),
      body: { appendChild() {} },
    },
    setInterval: () => 0,
    clearInterval: () => {},
  })
  const store = module.useExportTaskStore()
  const result = await store.track(completed(), { recordingId: 1046, reportNo: 'DT-2026-001046', reportVersion: 3 })
  assert.equal(clicks.length, 0)
  assert.equal(result.downloaded, false)
  assert.match(toasts.join(' | '), /自动下载已关闭/)
})

test('全局 toast 容器挂上了 vue-sonner 基础样式，否则任何提示都看不到', () => {
  const main = fs.readFileSync(path.join(__dirname, '../src/main.ts'), 'utf8')
  assert.match(main, /import 'vue-sonner\/style\.css'/)
  // Toaster 只在应用根挂一次，页面里只调 toast.*
  const app = fs.readFileSync(path.join(__dirname, '../src/App.vue'), 'utf8')
  assert.match(app, /<Toaster\s/)
  // 样式表里确实带定位与层级：不然 Toaster 只是 #app 末尾的普通流元素，会被整屏布局/弹窗挤到视口外
  const css = fs.readFileSync(path.join(__dirname, '../node_modules/vue-sonner/lib/index.css'), 'utf8')
  assert.match(css, /\[data-sonner-toaster\]\s*\{[^}]*position:\s*fixed/)
  assert.match(css, /\[data-sonner-toaster\]\s*\{[^}]*z-index:\s*\d{6,}/)
})

test('未知后台地址有受登录守卫保护的兜底', () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/router/index.ts'), 'utf8')
  assert.match(source, /path: '\/:pathMatch\(\.\*\)\*', redirect: '\/overview'/)
})

test('逐句转写只显示命中规则名，不展示规则关键词清单', () => {
  const { displaySentenceNote } = evaluate('lib/recordingNotes.ts')
  assert.equal(displaySentenceNote('命中规则《威胁性后果或强制时限表述》：“让你待不下去”、“后果自负”'), '威胁性后果或强制时限表述')
  assert.equal(displaySentenceNote('命中规则《联系无关第三人或透露欠款信息》： “告诉你妈”'), '联系无关第三人或透露欠款信息')
  assert.equal(displaySentenceNote('规则《其他》不适用'), '规则《其他》不适用')
  const source = fs.readFileSync(path.join(__dirname, '../src/views/recordings/index.vue'), 'utf8')
  assert.match(source, /\{\{ displaySentenceNote\(note\) \}\}/)
  assert.match(source, /\{\{ violation\.note \}\}/)
})

test('录音详情能直接试听原件：进页签自动签发一次，播放失败有猎底', () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/views/recordings/index.vue'), 'utf8')
  assert.match(source, /<audio[\s\S]*?controls[\s\S]*?preload="metadata"[\s\S]*?:src="audioUrl"[\s\S]*?@error="onAudioError"/)
  // 进「原件」页签就补签一次，不再要求用户先点按钮；已签发/正在签的不重复拉
  assert.match(source, /watch\(activeTab, \(tab\) => \{/)
  assert.match(source, /tab !== 'origin' \|\| audioUrl\.value \|\| leasing\.value/)
  assert.match(source, /void leaseAudio\(true\)/)
  // 静默补签不弹成功提示，但失败照旧报错；点击必须显式调用，否则事件对象会被当成 silent
  assert.match(source, /if \(!silent\) \{[\s\S]*?toast\.success\(`链接有效期 \$\{result\.ttlMinutes\} 分钟，签发已留痕`\)/)
  assert.match(source, /@click="leaseAudio\(\)"/)
  assert.match(source, /onAudioError\(\) \{\n\s+audioError\.value = '这条链接已失效或录音读不出来/)
  // 非管理员拿不到原件，文案不能给人「点一下就能听」的错觉
  assert.match(source, /当前角色不能调取录音原件/)
})

test('后台导出与重判点击即有反馈：进行中提示同 id 换成结果或失败', () => {
  const filename = path.join(__dirname, '../src/views/recordings/index.vue')
  const source = fs.readFileSync(filename, 'utf8')
  // 模板与脚本必须能编译：文案/按钮改动不能把 SFC 写坏
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'recordings-feedback' })
  assert.equal(compileTemplate({ source: descriptor.template.content, filename, id: 'recordings-feedback', compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  // 导出：提交、生成、自动下载是三段时间，点下去就要有响
  assert.match(source, /const toastId = toast\.loading\(withAudio \? '正在提交含原件打包任务…' : '正在提交导出任务…'\)/)
  // 服务端复用产物时作业建完就是完成态：文案必须区分「已开始下载」与「去导出任务下载」，且都以「已加入下载任务」开头
  assert.match(source, /const result = await exportStore\.track\(job, \{/)
  assert.match(source, /const ready = job\.status === 'COMPLETED'/)
  assert.match(source, /result\.downloaded[\s\S]*?已加入下载任务（作业 #\$\{job\.exportId\}）：产物已就绪，已开始下载/)
  assert.match(source, /已加入下载任务（作业 #\$\{job\.exportId\}）：产物已就绪，在顶栏「导出任务」里下载/)
  assert.match(source, /已加入下载任务（含原件 ZIP）[\s\S]*?\{ id: toastId \}\)/)
  assert.match(source, /toast\.error\(e instanceof ApiError \? e\.message : '导出提交失败', \{ id: toastId \}\)/)
  // 重判同理
  assert.match(source, /const toastId = toast\.loading\('已提交，正在按当前规则集重算…'\)/)
  assert.match(source, /toast\.success\(`已按当前规则集重判并发布第 \$\{result\.reportVersion\} 版[\s\S]*?\{ id: toastId \}\)/)
  assert.match(source, /toast\.error\(e instanceof ApiError \? e\.message : '重新判读失败', \{ id: toastId \}\)/)
  // 进行中只把对应那一个按钮换成「正在提交…」，另一个保持原文案
  assert.match(source, /const exporting = ref<'pdf' \| 'zip' \| null>\(null\)/)
  assert.equal((source.match(/:disabled="exportBusy"/g) ?? []).length, 4)
  assert.doesNotMatch(source, /:disabled="exporting"/)
  assert.equal((source.match(/exporting === 'pdf' \? '正在提交…'/g) ?? []).length, 2)
  assert.equal((source.match(/exporting === 'zip' \? '正在提交…'/g) ?? []).length, 2)
  // 另外两个动作也必须有落点提示，不能只有按钮置灰
  assert.match(source, /toast\.success\(`已删除，清理作业 #\$\{job\.deletionJobId\}/)
  assert.match(source, /正在签发播放链接…/)
})

test('审计页筛选行不再被压成 0 宽（回归：曾塌成竖排并溢出卡片右缘）', () => {
  const filename = path.join(__dirname, '../src/views/audit/index.vue')
  const source = fs.readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0, 'audit 页模板解析失败')
  const script = compileScript(descriptor, { id: 'audit' })
  assert.equal(compileTemplate({
    source: descriptor.template.content,
    filename,
    id: 'audit',
    compilerOptions: { bindingMetadata: script.bindings },
  }).errors.length, 0, 'audit 页模板编译失败')

  // 1. form 必须自己拿到确定宽度：它的孩子 FieldGroup 自带 w-full，
  //    auto 宽度的 flex item 遇上百分比子元素会塌成 0px，筛选控件随即竖排并溢出卡片。
  assert.match(source, /class="flex flex-1 flex-wrap items-end justify-end gap-3"/)
  // 2. Field 的固定宽度必须写在包裹层上：Field 自带的 w-full 在构建产物里排在 .w-56 之后，
  //    直接写 Field class="w-56" 会被盖掉（构建产物里 .w-32 < .w-40 < .w-48 < .w-56 < .w-full）。
  assert.match(source, /<div class="w-56 shrink-0">/)
  assert.doesNotMatch(source, /<Field class="/)
  // 3. 下拉要填满这个固定宽度，否则触发器的 w-fit 会把「全部动作」截断。
  assert.match(source, /<SelectTrigger id="audit-action" class="w-full">/)
  // 4. 左侧标题块允许收缩换行，避免把筛选行挤出卡片。
  assert.match(source, /<div class="min-w-0">/)
})
