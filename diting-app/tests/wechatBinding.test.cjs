const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

function harness(bindWechat, options = {}) {
  const store = { token: options.token ?? 'account-A', userInfo: { id: 7, hasWechat: false }, loadUserInfo: async () => {} }
  let onShowCallback
  let loginChecks = 0
  const calls = []
  const toasts = []
  const source = readFileSync(path.join(__dirname, '../src/pages/about/index.vue'), 'utf8')
  const { descriptor, errors } = parse(source)
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'about-test' })
  assert.equal(compileTemplate({ source: descriptor.template.content, filename: 'about/index.vue', id: 'about-test', compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  const code = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText
  const dependencies = {
    vue,
    '@dcloudio/uni-app': { onShow(callback) { onShowCallback = callback } },
    '@/stores/user': { useUserStore: () => store },
    '@/api/auth': { bindWechat },
    '@/utils/errors': { messageOf: () => '已绑定其他账号' },
    '@/utils/h5Auth': { requireH5Login() {
      loginChecks++
      return true
    } },
    '@/utils/deletionReceipt': { getDeletionReceipt: () => options.receipt ?? null },
  }
  const exports = {}
  const uni = {
    login({ success }) {
      calls.push('login')
      success({ code: 'wx-code' })
    },
    showToast(data) {
      toasts.push(data)
    },
    navigateTo() {},
  }
  vm.runInNewContext(code, { exports, require: name => dependencies[name] || {}, uni }, { filename: 'about/index.vue' })
  return {
    store,
    state: exports.default.setup({}, { expose() {} }),
    calls,
    toasts,
    template: descriptor.template.content,
    show() { onShowCallback() },
    loginChecks: () => loginChecks,
  }
}

test('已有账号绑定微信后更新身份提示，不能重复发起授权', async () => {
  const requests = []
  const h = harness(async (code, token) => {
    requests.push({ code, token })
    return { id: 7, hasWechat: true }
  })
  await h.state.bindCurrentWechat()
  assert.deepEqual(requests, [{ code: 'wx-code', token: 'account-A' }])
  assert.equal(h.store.userInfo.hasWechat, true)
  await h.state.bindCurrentWechat()
  assert.equal(requests.length, 1)
  assert.match(h.template, /#ifdef MP-WEIXIN/)
})

test('抹除后旧凭据允许从安心页进入清理状态而非强制 H5 登录', () => {
  const pending = harness(async () => {}, { token: '', receipt: { jobId: 7 } })
  pending.show()
  assert.equal(pending.loginChecks(), 0)
  const noReceipt = harness(async () => {}, { token: '', receipt: null })
  noReceipt.show()
  assert.equal(noReceipt.loginChecks(), 1)
})

test('响应到达时若已切换账号，不可把旧微信绑定到新账号展示', async () => {
  let resolveBind
  const h = harness(() => new Promise((resolve) => {
    resolveBind = resolve
  }))
  const pending = h.state.bindCurrentWechat()
  await Promise.resolve()
  h.store.token = 'account-B'
  resolveBind({ id: 7, hasWechat: true })
  await pending
  assert.equal(h.store.userInfo.hasWechat, false)
  assert.equal(h.toasts.length, 0)
})
