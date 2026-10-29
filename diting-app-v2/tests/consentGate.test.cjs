const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const SRC = path.join(__dirname, '../src')

function transpile(source, filename) {
  return ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  } }, { fileName: filename }).outputText
}

function loadUtil(rel, injected = {}) {
  const filename = path.join(SRC, rel)
  const exports = {}
  vm.runInNewContext(transpile(fs.readFileSync(filename, 'utf8'), filename), { exports, ...injected }, { filename })
  return exports
}

/** 每次「冷启动」都要拿到全新模块实例：状态在内存里，不能被前一個用例污染 */
function freshGate() {
  return loadUtil('utils/consentGate.ts')
}

function pageHarness(rel, options = {}) {
  const nav = []
  const hooks = {}
  const uni = {
    navigateTo: o => nav.push(o.url),
    switchTab: o => nav.push(o.url),
    redirectTo: o => nav.push(o.url),
    navigateBack: () => nav.push('back'),
    showToast() {},
    getSystemInfoSync: () => ({ statusBarHeight: 20, windowHeight: 844 }),
    ...options.uni,
  }
  const filename = path.join(SRC, rel)
  const source = fs.readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0, `${rel} 解析失败`)
  const script = compileScript(descriptor, { id: rel })
  const template = compileTemplate({
    source: descriptor.template.content,
    filename,
    id: rel,
    compilerOptions: { bindingMetadata: script.bindings },
  })
  assert.equal(template.errors.length, 0, `${rel} 模板编译失败`)

  const deps = {
    'vue': { ...vue, onUnmounted() {}, onMounted() {} },
    '@dcloudio/uni-app': Object.fromEntries(
      ['onLoad', 'onShow', 'onHide', 'onUnload'].map(name => [name, (cb) => { hooks[name] = cb }]),
    ),
    '@/components/StatusBar.vue': {},
    '@/components/HeroCard.vue': {},
    '@/components/RecordCard.vue': {},
    '@/components/UploadSelection.vue': {},
    '@/components/SurveySheet.vue': {},
    '@/components/ConsentSheet.vue': {},
    '@/utils/format': { greeting: () => '今天也在' },
    '@/stores/user': { useUserStore: () => options.store ?? { token: '', userInfo: null, consentRequired: false, ensureLogin: async () => true } },
    '@/utils/h5Auth': { requireH5Login: () => true },
    // 首页的首次引导与本文件无关：按「已看过」处理，避免抢在门禁断言之前跳页
    '@/utils/onboard': { hasOnboarded: () => true },
    '@/api/record': { fetchTaskList: async () => ({ items: [], total: 0 }) },
    '@/api/agreement': {
      fetchAgreements: async () => options.agreements ?? [
        { key: '软件许可及服务协议', title: '软件许可及服务协议', version: 1 },
        { key: '隐私保护指引', title: '隐私保护指引', version: 1 },
        { key: '个人信息收集与授权书', title: '个人信息收集与授权书', version: 1 },
        { key: '通话录音与语音识别授权协议', title: '通话录音与语音识别授权协议', version: 1 },
        { key: '第三方共享与 SDK 清单', title: '第三方共享与 SDK 清单', version: 1 },
      ],
    },
    ...options.deps,
  }
  deps['@/utils/consentGate'] ??= loadUtil('utils/consentGate.ts')

  const exports = {}
  vm.runInNewContext(transpile(script.content, filename), {
    exports,
    uni,
    console,
    getCurrentPages: () => options.pages ?? [{}],
    require: name => deps[name] ?? {},
  }, { filename })
  const state = exports.default.setup(options.props ?? {}, { expose() {} })
  return { hooks, state, nav, template: descriptor.template.content, source }
}

test('门禁状态只在内存里：每次冷启动都要重新同意', () => {
  const gate = freshGate()
  assert.equal(gate.needsConsentGate(), true)
  assert.equal(gate.hasFullAccess(), false)

  gate.markAgreed()
  assert.equal(gate.needsConsentGate(), false)
  assert.equal(gate.hasFullAccess(), true)

  // 重新加载模块 = 冷启动：上次同意过也不能免问
  const relaunched = freshGate()
  assert.equal(relaunched.needsConsentGate(), true)
  assert.equal(relaunched.hasFullAccess(), false)

  relaunched.markDeclined()
  assert.equal(relaunched.needsConsentGate(), false)
  assert.equal(relaunched.hasFullAccess(), false)
})

test('授权页列出协议清单，同意与不同意都放行离开', async () => {
  const first = pageHarness('pages/consent/index.vue')
  assert.equal(first.state.loading.value, true)
  await first.hooks.onLoad({})
  assert.equal(first.state.loading.value, false)
  assert.equal(first.state.agreements.value.length, 5)
  assert.match(first.template, /同意以上 \{\{ agreements\.length \}\} 份条款/)
  assert.match(first.template, /不同意，先看看/)

  // 冷启动（没有上一页）→ 回首页
  first.state.agree()
  assert.deepEqual(first.nav, ['/pages/home/index'])

  // 被功能入口拦回来（栈内有页）→ 回原页
  const second = pageHarness('pages/consent/index.vue', { pages: [{}, {}] })
  await second.hooks.onLoad({})
  second.state.decline()
  assert.deepEqual(second.nav, ['back'])
})

test('协议来自接口，数量不写死', async () => {
  const five = pageHarness('pages/consent/index.vue')
  await five.hooks.onLoad({})
  assert.equal(five.state.agreements.value.length, 5)

  const broken = pageHarness('pages/consent/index.vue', {
    deps: { '@/api/agreement': { fetchAgreements: async () => [] } },
  })
  await broken.hooks.onLoad({})
  // 取不到就明确说取不到，并把「同意」按钮禁用——不能让人同意一份没看过的条款
  assert.equal(broken.state.agreements.value.length, 0)
  assert.match(broken.template, /协议暂时取不到/)
  assert.match(broken.template, /:disabled="loading \|\| agreements\.length === 0"/)
})

test('授权页是冷启动第一屏', () => {
  const pages = JSON.parse(fs.readFileSync(path.join(SRC, 'pages.json'), 'utf8'))
  assert.equal(pages.pages[0].path, 'pages/consent/index')
  assert.equal(pages.pages[1].path, 'pages/home/index')
})

test('未同意时上传入口被拦到授权页，首页仍可浏览', () => {
  const gate = freshGate()
  gate.markDeclined()

  const upload = pageHarness('pages/upload/index.vue', {
    deps: {
      '@/utils/consentGate': gate,
      '@/utils/demo': { DEMO_MODE: false },
      '@/utils/survey': loadUtil('utils/survey.ts'),
    },
  })
  upload.state.pendingFiles.value = [{ name: 'a.mp3', path: 'p1', size: 1024 }]
  upload.state.openSurvey()
  assert.equal(upload.state.surveyVisible.value, false, '受限时连问卷都不该开')
  assert.deepEqual(upload.nav, ['/pages/consent/index'])

  // 就算绕过界面直接触发提交，仍然要被门禁拦回授权页
  upload.state.confirmUpload()
  assert.deepEqual(upload.nav, ['/pages/consent/index', '/pages/consent/index'])

  const home = pageHarness('pages/home/index.vue', { deps: { '@/utils/consentGate': gate } })
  home.hooks.onShow()
  assert.equal(home.state.restricted.value, true)
  assert.match(home.template, /去授权/)
  home.state.openConsent()
  assert.deepEqual(home.nav, ['/pages/consent/index'])
})

test('同意之后上传不再被门禁拦', async () => {
  const gate = freshGate()
  gate.markAgreed()
  const upload = pageHarness('pages/upload/index.vue', {
    deps: {
      '@/utils/consentGate': gate,
      '@/utils/demo': { DEMO_MODE: false },
      '@/utils/survey': loadUtil('utils/survey.ts'),
    },
  })
  upload.state.pendingFiles.value = [{ name: 'a.mp3', path: 'p1', size: 1024 }]
  upload.state.openSurvey()
  assert.equal(upload.state.surveyVisible.value, true)
  assert.deepEqual(upload.nav, [])
})
