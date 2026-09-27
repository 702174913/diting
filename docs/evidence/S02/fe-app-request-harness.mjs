// S02 前端限定验证：小程序 request.ts 的信封拆解 / 401 处理 / 默认 token 注入
import assert from 'node:assert/strict'

// --- 最小 uni 运行时桩 ---
const storage = new Map()
let lastRequest = null
let nextResponse = null
globalThis.uni = {
  getStorageSync: k => storage.get(k) ?? '',
  setStorageSync: (k, v) => storage.set(k, v),
  removeStorageSync: k => storage.delete(k),
  request: (options) => {
    lastRequest = options
    options.success(nextResponse)
  },
}
const { request, ApiError, onUnauthorized } = await import('/tmp/s02-fe/app-request.mjs')

// 1) 成功：拆出 data
nextResponse = { statusCode: 200, data: { code: 0, message: 'success', data: { id: 7 }, symbol: null } }
assert.deepEqual(await request({ url: '/api/task/list' }), { id: 7 })
assert.equal(lastRequest.header.Authorization, undefined, '未登录时不应带 Authorization')

// 2) 本地有 token 时自动注入
storage.set('diting_token', 'token-abc')
nextResponse = { statusCode: 200, data: { code: 0, message: 'success', data: [] } }
await request({ url: '/api/task/list' })
assert.equal(lastRequest.header.Authorization, 'Bearer token-abc')

// 3) 业务失败：抛 ApiError，保留 code/message/symbol
nextResponse = { statusCode: 429, data: { code: 429, message: '今日可用份数已用完', symbol: 'QUOTA_EXCEEDED' } }
await assert.rejects(() => request({ url: '/api/task/submit', method: 'POST' }), (e) => {
  assert.ok(e instanceof ApiError)
  assert.equal(e.code, 429)
  assert.equal(e.symbol, 'QUOTA_EXCEEDED')
  assert.equal(e.message, '今日可用份数已用完')
  return true
})

// 4) 401：清本地登录态 + 触发回调（页面据此重新登录）
let notified = false
onUnauthorized(() => { notified = true })
nextResponse = { statusCode: 401, data: { code: 401, message: '未登录或登录已过期', symbol: 'UNAUTHORIZED' } }
await assert.rejects(() => request({ url: '/api/task/list' }))
assert.equal(storage.get('diting_token'), undefined, '401 后必须清掉本地 token')
assert.ok(notified, '401 后必须回调 unauthorized handler')

// 5) 响应格式异常（例如网关 HTML）：给出可读错误而不是崩在 undefined
nextResponse = { statusCode: 502, data: '<html>bad gateway</html>' }
await assert.rejects(() => request({ url: '/api/task/list' }), (e) => {
  assert.ok(e instanceof ApiError)
  assert.match(e.message, /响应格式不符合预期/)
  return true
})

console.log('app request.ts 限定验证：5 组断言全部通过')
