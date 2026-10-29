const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const flush = () => new Promise(resolve => setImmediate(resolve))

function harness(fetchTaskList) {
  let onShow
  const store = { token: 'account-A' }
  const source = readFileSync(path.join(__dirname, '../src/pages/home/index.vue'), 'utf8')
  const { descriptor, errors } = parse(source)
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'home-test' })
  assert.equal(compileTemplate({ source: descriptor.template.content, filename: 'home/index.vue', id: 'home-test', compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  const code = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText
  const dependencies = {
    vue,
    '@dcloudio/uni-app': { onShow: (cb) => { onShow = cb } },
    '@/stores/user': { useUserStore: () => store },
    '@/api/record': { fetchTaskList },
    '@/utils/format': { greeting: () => '早安' },
    '@/utils/taskStatus': { isRunning: () => false },
    '@/utils/h5Auth': { requireH5Login: token => Boolean(token) },
    // 首页首次进入会先弹三步引导；本文件只测列表状态机，按「已看过」处理
    '@/utils/onboard': { hasOnboarded: () => true },
    '@/utils/consentGate': { hasFullAccess: () => true },
  }
  const exports = {}
  vm.runInNewContext(code, { exports, require: name => dependencies[name] || {}, uni: { switchTab() {}, navigateTo() {} } }, { filename: 'home/index.vue' })
  const state = exports.default.setup({}, { expose() {} })
  return { state, store, show: () => onShow(), template: descriptor.template.content }
}

test('首屏取服务端分页总数而非预览条数；失败独立于真正空列表', async () => {
  let resolveFirst
  const h = harness(() => new Promise((resolve) => {
    resolveFirst = resolve
  }))
  h.show()
  assert.equal(h.state.loading.value, true)
  assert.equal(h.state.total.value, null)
  resolveFirst({ total: 41, items: Array.from({ length: 20 }, (_, id) => ({ id })) })
  await flush()
  assert.equal(h.state.total.value, 41)
  assert.equal(h.state.preview.value.length, 4)
  assert.match(h.template, /total === 0/)
  assert.match(h.template, /loadError/)

  const failed = harness(async () => {
    throw new Error('network')
  })
  failed.show()
  await flush()
  assert.equal(failed.state.loadError.value, true)
  assert.equal(failed.state.total.value, null)
  assert.equal(failed.state.records.value.length, 0)
})

test('切换账号时旧列表响应不可回填新账号', async () => {
  const pending = []
  const h = harness(() => new Promise(resolve => pending.push(resolve)))
  h.show()
  h.store.token = 'account-B'
  h.show()
  pending[0]({ total: 99, items: [{ id: 1 }] })
  await flush()
  assert.equal(h.state.total.value, null)
  pending[1]({ total: 2, items: [{ id: 2 }] })
  await flush()
  assert.equal(h.state.total.value, 2)
  assert.equal(h.state.records.value[0].id, 2)
})
