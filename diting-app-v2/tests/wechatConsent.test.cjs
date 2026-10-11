const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const root = path.resolve(__dirname, '..')
class ApiError extends Error {
  constructor(code) {
    super('协议已更新')
    this.code = code
  }
}

function componentHarness(loginByWechat) {
  const source = readFileSync(path.join(root, 'src/components/AccountLoginSheet.vue'), 'utf8')
  const { descriptor, errors } = parse(source)
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'consent-test' })
  assert.equal(compileTemplate({ source: descriptor.template.content, filename: 'AccountLoginSheet.vue', id: 'consent-test', compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  const code = ts.transpileModule(script.content, { compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  } }).outputText
  const versions = { service_terms: 1, privacy_guide: 2, personal_info_auth: 1, recording_auth: 3 }
  const list = Object.keys(versions).map(key => ({ key, title: key }))
  const user = {
    userInfo: null,
    setLogin(token) {
      this.token = token
    },
  }
  let mounted
  const emitted = []
  const dependencies = {
    'vue': { ...vue, onMounted(cb) { mounted = cb } },
    '@/api/agreement': { fetchAgreements: async () => list, fetchAgreementVersions: async () => versions },
    '@/api/auth': { loginByWechat, registerAccount() {}, loginByPassword() {} },
    '@/api/request': { ApiError },
    '@/stores/user': { useUserStore: () => user },
    '@/utils/errors': { messageOf: error => error.message },
  }
  const exports = {}
  const uni = {
    login({ success }) {
      success({ code: 'wx-verified' })
    },
    navigateTo() {},
  }
  vm.runInNewContext(code, { exports, require: name => dependencies[name] || {}, uni }, { filename: 'AccountLoginSheet.vue' })
  const state = exports.default.setup({ visible: true }, { expose() {}, emit: name => emitted.push(name) })
  return { state, user, emitted, mount: () => mounted(), template: descriptor.template.content }
}

test('微信首登必须勾选已载入四份协议，提交精确版本快照', async () => {
  const requests = []
  const h = componentHarness(async (code, agreed, versions) => {
    requests.push({ code, agreed, versions: { ...versions } })
    return { token: 'fresh-token', userInfo: { id: 8 } }
  })
  h.mount()
  await new Promise(resolve => setImmediate(resolve))
  await h.state.submitWechat()
  assert.equal(requests.length, 0)
  h.state.toggleAgreement()
  await h.state.submitWechat()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].agreed, true)
  assert.deepEqual(requests[0].versions, { service_terms: 1, privacy_guide: 2, personal_info_auth: 1, recording_auth: 3 })
  assert.equal(h.user.token, 'fresh-token')
  assert.deepEqual(h.emitted, ['success'])
  assert.match(h.template, /#ifdef MP-WEIXIN[\s\S]*?submitWechat/)
})

test('版本冲突后取消勾选，不能无声重试旧协议', async () => {
  const h = componentHarness(async () => {
    throw new ApiError(409)
  })
  h.mount()
  await new Promise(resolve => setImmediate(resolve))
  h.state.toggleAgreement()
  await h.state.submitWechat()
  assert.equal(h.state.agreed.value, false)
  assert.equal(h.user.token, undefined)
})

test('静默微信首登的 422 转协议页，云故障不误导为协议问题', async () => {
  const source = readFileSync(path.join(root, 'src/stores/user.ts'), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  } }).outputText
  for (const status of [422, 503]) {
    const navigations = []
    const exports = {}
    const dependencies = {
      'pinia': { defineStore: (_, setup) => setup },
      'vue': { ref: vue.ref },
      '@/api/auth': { fetchUserInfo() {}, loginByWechat: async () => { throw new ApiError(status) } },
      '@/api/request': { ApiError, onUnauthorized() {} },
      '@/utils/authToken': { getToken: () => '', setToken() {}, clearToken() {} },
      '@/utils/h5Auth': { redirectAfterUnauthorized() {} },
    }
    const uni = {
      login({ success }) {
        success({ code: 'new-code' })
      },
      navigateTo(data) {
        navigations.push(data.url)
      },
    }
    vm.runInNewContext(code, { exports, require: name => dependencies[name] || {}, uni }, { filename: 'user.ts' })
    const user = exports.useUserStore()
    assert.equal(await user.ensureLogin(), false)
    assert.equal(user.consentRequired.value, status === 422)
    assert.deepEqual(navigations, status === 422 ? ['/pages/login/index'] : [])
  }
})
