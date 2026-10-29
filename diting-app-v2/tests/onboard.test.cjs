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

/** 纯 TS 工具模块（无模板）："硬跑"一遍再断言，不做静态匹配 */
function loadUtil(rel, uni) {
  const filename = path.join(SRC, rel)
  const exports = {}
  vm.runInNewContext(transpile(fs.readFileSync(filename, 'utf8'), filename), { exports, uni }, { filename })
  return exports
}

/** 把页面当模块跑在受控沙箱里：uni 存根、依赖可替换、页面钩子可手动触发 */
function createHarness(rel, options = {}) {
  const nav = []
  const writes = options.writes ?? {}
  const counter = options.counter ?? {}
  const uni = {
    navigateTo: o => nav.push(o.url),
    switchTab: o => nav.push(o.url),
    redirectTo: o => nav.push(o.url),
    navigateBack: () => nav.push('back'),
    getStorageSync: key => writes[key] ?? '',
    setStorageSync: (key, value) => {
      writes[key] = value
    },
    removeStorageSync: (key) => {
      delete writes[key]
    },
    getSystemInfoSync: () => ({ windowHeight: 700, statusBarHeight: 20 }),
    showToast() {},
    ...options.uni,
  }
  const hooks = {}
  const filename = path.join(SRC, rel)
  const source = fs.readFileSync(filename, 'utf8')

  function evaluate(code, name) {
    const exports = {}
    vm.runInNewContext(code, { ...sandbox, exports }, { filename: name })
    return exports
  }

  function localUtil(name) {
    const file = path.join(SRC, `${name.slice(2)}.ts`)
    return evaluate(transpile(fs.readFileSync(file, 'utf8'), file), file)
  }

  const dependencies = {
    'vue': { ...vue, onUnmounted: (cb) => { hooks.onUnmounted = cb } },
    '@dcloudio/uni-app': Object.fromEntries(
      ['onLoad', 'onShow', 'onHide', 'onUnload'].map(name => [name, (cb) => { hooks[name] = cb }]),
    ),
    '@/stores/user': { useUserStore: () => options.store ?? { token: '', userInfo: null } },
    '@/utils/format': { greeting: () => '今天也在' },
    '@/utils/h5Auth': {
      requireH5Login: () => {
        counter.requireH5Login = (counter.requireH5Login ?? 0) + 1
        return options.h5Login ?? true
      },
      redirectAfterUnauthorized() {},
    },
    '@/api/record': {
      fetchTaskList: async () => {
        counter.fetchTaskList = (counter.fetchTaskList ?? 0) + 1
        return { items: [], total: 0 }
      },
    },
    ...options.deps,
  }

  const sandbox = {
    uni,
    getCurrentPages: () => options.pages ?? [{}, {}],
    getApp: () => ({ globalData: {} }),
    console,
    require: (name) => {
      if (dependencies[name]) {
        return dependencies[name]
      }
      if (name.startsWith('@/utils/')) {
        return localUtil(name)
      }
      // 组件与静态资源在断言里用不到，给空模块即可（不伪造其行为）
      return {}
    },
  }

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

  const component = evaluate(transpile(script.content, filename), filename).default
  const state = component.setup(options.props ?? {}, { expose() {} })
  return { hooks, nav, state, template: descriptor.template.content, source, writes, counter }
}

test('引导标记带版本号，缺失时不算看过', () => {
  const storage = {}
  const onboard = loadUtil('utils/onboard.ts', {
    getStorageSync: key => storage[key] ?? '',
    setStorageSync: (key, value) => {
      storage[key] = value
    },
  })
  assert.equal(onboard.hasOnboarded(), false)
  assert.equal(onboard.ONBOARD_VERSION, 1)
  onboard.markOnboarded()
  assert.equal(storage.diting_onboarded_v1, true)
  assert.equal(onboard.hasOnboarded(), true)
  // 版本升级后 key 换了，旧标记不该让新引导「被看不见」
  assert.equal(loadUtil('utils/onboard.ts', { getStorageSync: () => '', setStorageSync() {} }).hasOnboarded(), false)
  // 存了非 true 的值也不算看过（clearing/unserializable 都不该静默放行）
  assert.equal(loadUtil('utils/onboard.ts', { getStorageSync: () => 'false', setStorageSync() {} }).hasOnboarded(), false)
})

test('引导页三步齐全，末步按钮改为开始使用', () => {
  const h = createHarness('pages/welcome/index.vue')
  assert.equal(h.state.steps.length, 3)
  assert.deepEqual(Array.from(h.state.steps, step => step.no), ['第一步', '第二步', '第三步'])
  assert.equal(h.state.primaryLabel.value, '下一步')
  h.state.next()
  assert.equal(h.state.current.value, 1)
  h.state.current.value = 2
  assert.equal(h.state.isLast.value, true)
  assert.equal(h.state.primaryLabel.value, '开始使用')
  // 手滑滑到上一屏要能回退，而不是只能往前
  h.state.onChange({ detail: { current: 0 } })
  assert.equal(h.state.current.value, 0)
  assert.match(h.template, /@change="onChange"/)
})

test('跳过与看完同样只记完成并返回来处', () => {
  const h = createHarness('pages/welcome/index.vue')
  assert.match(h.template, /@click="finish"/)
  h.state.finish()
  assert.equal(h.writes.diting_onboarded_v1, true)
  assert.deepEqual(h.nav, ['back'])

  // 冷启动直接落在引导页时没有上一页，回首页兜底
  const cold = createHarness('pages/welcome/index.vue', { pages: [{}] })
  cold.state.finish()
  assert.deepEqual(cold.nav, ['/pages/home/index'])
})

test('引导页只做教学：不请求接口、不写登录态', () => {
  const h = createHarness('pages/welcome/index.vue')
  assert.doesNotMatch(h.source, /@\/api\//)
  assert.doesNotMatch(h.source, /diting_token|setToken|ensureLogin/)
  h.state.next()
  assert.deepEqual(h.nav, [])
})

test('首页首次进入先弹引导，不再同轮触发登录跳转与列表请求', () => {
  const h = createHarness('pages/home/index.vue')
  h.hooks.onShow()
  assert.deepEqual(h.nav, ['/pages/welcome/index'])
  // 引导页整屏覆盖，这一轮不该同时打列表接口
  assert.equal(h.counter.fetchTaskList ?? 0, 0)

  // 从引导页回来会再触发一次 onShow：不再重弹，正常加载
  h.hooks.onShow()
  assert.deepEqual(h.nav, ['/pages/welcome/index'])
})

test('已看过引导的用户直接进首页，不被打断', () => {
  const h = createHarness('pages/home/index.vue', { writes: { diting_onboarded_v1: true } })
  h.hooks.onShow()
  assert.deepEqual(h.nav, [])
})

test('每步都是真实页面截图 + 圈注指引', () => {
  const page = createHarness('pages/welcome/index.vue')
  assert.deepEqual(Array.from(page.state.steps, step => step.kind), ['upload', 'read', 'export'])
  assert.match(page.template, /<GuideScreen :kind="step\.kind" \/>/)

  const expected = {
    upload: '入口',
    read: '依据',
    export: '导出 PDF',
  }
  for (const [kind, keyword] of Object.entries(expected)) {
    const screen = createHarness('components/GuideScreen.vue', { props: { kind } })
    const shot = screen.state.shot.value
    assert.ok(shot.legend.includes(keyword), `${kind} 的指引没有说到要点：${shot.legend}`)
    assert.equal(shot.no, String(['upload', 'read', 'export'].indexOf(kind) + 1))
    // 圈注坐标是百分比，必须落在截图内，否则圈到屏幕外
    for (const [key, value] of Object.entries(shot.ring)) {
      assert.ok(value > 0 && value <= 100, `${kind}.ring.${key}=${value}`)
    }
    assert.ok(shot.ring.x + shot.ring.w <= 100, `${kind} 圈注超出右边界`)
    assert.ok(shot.ring.y + shot.ring.h <= 100, `${kind} 圈注超出下边界`)
  }

  // 截图素材必须真的在仓库里，否则引导页会变成一块白框
  for (const file of ['step-upload.jpg', 'step-read.jpg', 'step-export.jpg'])
    assert.ok(fs.existsSync(path.join(SRC, 'static/guide', file)), `缺少截图 ${file}`)
})

test('安心页保留重看入口，引导页已注册路由', () => {
  const about = createHarness('pages/about/index.vue')
  assert.match(about.template, /@click="openGuide"/)
  about.state.openGuide()
  assert.deepEqual(about.nav, ['/pages/welcome/index'])

  const pages = JSON.parse(fs.readFileSync(path.join(SRC, 'pages.json'), 'utf8'))
  const welcome = pages.pages.find(page => page.path === 'pages/welcome/index')
  assert.ok(welcome, 'pages.json 未注册引导页')
  assert.equal(welcome.style.navigationStyle, 'custom')
  // 冷启动第一屏是授权页，首页紧随其后：引导页不能抢首位，否则冷启动直接进引导
  assert.equal(pages.pages[0].path, 'pages/consent/index')
  assert.equal(pages.pages[1].path, 'pages/home/index')
})
