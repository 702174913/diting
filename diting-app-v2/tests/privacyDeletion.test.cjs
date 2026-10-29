const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const root = path.resolve(__dirname, '..')

function harness(memory, status, initialToken = 'user-token') {
  const file = path.join(root, 'src/pages/privacy/index.vue')
  const { descriptor, errors } = parse(readFileSync(file, 'utf8'))
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'privacy-test' })
  assert.equal(compileTemplate({ source: descriptor.template.content, filename: file, id: 'privacy-test', compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  const code = ts.transpileModule(script.content, { compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  } }).outputText
  const hooks = {}
  const user = {
    token: initialToken,
    clear() {
      this.token = ''
    },
  }
  const toasts = []
  const intervals = []
  const storage = {
    getStorageSync: key => memory.get(key),
    setStorageSync(key, value) { memory.set(key, value) },
    removeStorageSync(key) { memory.delete(key) },
  }
  const receiptCode = ts.transpileModule(readFileSync(path.join(root, 'src/utils/deletionReceipt.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const receiptExports = {}
  vm.runInNewContext(receiptCode, { exports: receiptExports, uni: storage }, { filename: 'deletionReceipt.ts' })
  const dependencies = {
    'vue': vue,
    '@dcloudio/uni-app': {
      onShow(cb) { hooks.show = cb },
      onHide(cb) { hooks.hide = cb },
      onUnload(cb) { hooks.unload = cb },
      onShareAppMessage() {},
    },
    '@/api/record': {
      eraseAll: async () => ({ deletionJobId: 7, status: 'RETRY_WAIT', pendingObjects: 2 }),
      fetchDeletion: async () => ({ deletionJobId: 7, status, pendingObjects: status === 'COMPLETED' ? 0 : 2 }),
    },
    '@/api/request': { ApiError: class extends Error {} },
    '@/stores/user': { useUserStore: () => user },
    '@/utils/deletionReceipt': receiptExports,
    '@/utils/errors': { messageOf: () => '网络错误' },
    '@/utils/h5Auth': { requireH5Login() { hooks.loginRedirected = true } },
    '@/utils/share': { useShareContent: () => () => ({}) },
  }
  const exports = {}
  const uni = {
    ...storage,
    showModal({ success }) { success({ confirm: true }) },
    showToast(item) { toasts.push(item.title) },
  }
  vm.runInNewContext(code, {
    exports,
    require: name => dependencies[name] || {},
    uni,
    setInterval(callback) {
      const interval = { callback, active: true }
      intervals.push(interval)
      return interval
    },
    clearInterval(interval) { interval.active = false },
  }, { filename: 'privacy/index.vue' })
  return { state: exports.default.setup({}, { expose() {} }), user, hooks, intervals, toasts, receipt: receiptExports }
}

test('撤销正常登录态但保留限权作业凭据，绝不提前显示清理完成', async () => {
  const memory = new Map()
  const h = harness(memory, 'RETRY_WAIT')
  h.hooks.show()
  await h.state.eraseAll()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(h.user.token, '')
  assert.match(h.state.cleanStatus.value, /清理中/)
  assert.doesNotMatch(h.state.cleanStatus.value, /已完成/)
  const receipt = memory.get('diting_deletion_status_receipt')
  assert.equal(receipt.jobId, 7)
  assert.equal(receipt.token, 'user-token')
  assert.equal(h.intervals.length, 1)
  h.hooks.hide()
  assert.equal(h.intervals[0].active, false)
})

test('重新进入可用旧凭据只查本人作业；完成后销毁凭据并停止轮询', async () => {
  const memory = new Map([['diting_deletion_status_receipt', { jobId: 7, token: 'old-limited-token', createdAt: Date.now() }]])
  const h = harness(memory, 'COMPLETED', '')
  h.hooks.show()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(h.hooks.loginRedirected, undefined)
  assert.equal(h.state.cleanStatus.value, '数据清理已完成')
  assert.equal(memory.has('diting_deletion_status_receipt'), false)
  assert.equal(h.intervals[0].active, false)
})

test('限权作业凭据过期后从本机移除，不无限期留存旧token', () => {
  const memory = new Map([['diting_deletion_status_receipt', {
    jobId: 7,
    token: 'expired',
    createdAt: Date.now() - 73 * 60 * 60 * 1000,
  }]])
  const h = harness(memory, 'RETRY_WAIT', '')
  assert.equal(h.receipt.getDeletionReceipt(), null)
  assert.equal(memory.has('diting_deletion_status_receipt'), false)
})

test('旧作业凭据的401不清除当前其他账号的登录态', async () => {
  const source = readFileSync(path.join(root, 'src/api/request.ts'), 'utf8')
    .replace('import.meta.env.VITE_BASE_URL', '\'\'')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  let cleared = 0
  vm.runInNewContext(code, {
    exports,
    require: () => ({ getToken: () => 'new-account-token', clearToken() { cleared++ } }),
    uni: { request({ success }) { success({ statusCode: 401, data: { code: 401, message: 'expired' } }) } },
  }, { filename: 'request.ts' })
  await assert.rejects(exports.request({ url: '/api/deletions/7', token: 'old-limited-token' }), error => error.code === 401)
  assert.equal(cleared, 0)
})
