// S02 前端限定验证：后台 request.ts（axios）的信封拆解 / 401 处理 / token 注入
import assert from 'node:assert/strict'

const storage = new Map()
globalThis.localStorage = {
  getItem: k => storage.get(k) ?? null,
  setItem: (k, v) => storage.set(k, v),
  removeItem: k => storage.delete(k),
}
const location = { pathname: '/overview', href: '' }
globalThis.window = { location }
globalThis.__axios = { status: 200, data: { code: 0, message: 'success', data: { ok: 1 } } }

const { request, ApiError } = await import('/tmp/s02-fe/admin-request.mjs')

// 1) 成功拆信封
assert.deepEqual(await request({ url: '/api/admin/dashboard' }), { ok: 1 })

// 2) 本地登录态自动注入；显式 token 优先
storage.set('diting_admin_token', 'admin-token-1')
await request({ url: '/api/admin/dashboard' })
assert.equal(globalThis.__axiosConfig?.headers?.Authorization, 'Bearer admin-token-1')

// 3) 业务失败（HTTP 200 + code!=0）：抛 ApiError 并保留 symbol
globalThis.__axios = { status: 200, data: { code: 409, message: '必须至少保留一个启用的 ADMIN', symbol: 'CONFLICT' } }
await assert.rejects(() => request({ url: '/api/admin/accounts/1', method: 'PUT' }), (e) => {
  assert.ok(e instanceof ApiError)
  assert.equal(e.code, 409)
  assert.equal(e.symbol, 'CONFLICT')
  return true
})

// 4) HTTP 401：清登录态 + 跳登录页 + 抛 ApiError
storage.set('diting_admin_role', 'ADMIN')
globalThis.__axios = {
  error: { response: { status: 401, data: { code: 401, message: '未登录或登录已过期', symbol: 'UNAUTHORIZED' } } },
}
await assert.rejects(() => request({ url: '/api/admin/dashboard' }), (e) => e instanceof ApiError && e.code === 401)
assert.equal(storage.get('diting_admin_token'), undefined, '401 后必须清掉 token')
assert.equal(storage.get('diting_admin_role'), undefined, '401 后必须清掉角色')
assert.equal(location.href, '/login', '401 后必须回登录页')

// 5) 限流 429：文案与 symbol 透传，不误判为登录失效
location.pathname = '/login'
globalThis.__axios = { error: { response: { status: 429, data: { code: 429, message: '失败次数过多，请 10 分钟后再试', symbol: 'TOO_MANY_REQUESTS' } } } }
await assert.rejects(() => request({ url: '/api/admin/login', method: 'POST' }), (e) => {
  assert.equal(e.symbol, 'TOO_MANY_REQUESTS')
  assert.match(e.message, /失败次数过多/)
  return true
})

// 6) 网络异常：可读文案
globalThis.__axios = { error: new Error('Network Error') }
await assert.rejects(() => request({ url: '/api/admin/dashboard' }), (e) => {
  assert.equal(e.code, 0)
  assert.match(e.message, /网络异常/)
  return true
})

console.log('admin request.ts 限定验证：6 组断言全部通过')
