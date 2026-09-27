// S09 前端限定验证：轮询生命周期、分页去重、幂等键、错误文案、状态分流
import assert from 'node:assert/strict'

const polling = await import('/tmp/s09-fe/polling.mjs')
const paging = await import('/tmp/s09-fe/paging.mjs')
const idem = await import('/tmp/s09-fe/idempotency.mjs')
const status = await import('/tmp/s09-fe/taskStatus.mjs')
// 单一 bundle：确保 harness 构造的 ApiError 与 messageOf 内部的是同一个类
const { messageOf, isRetryable, ApiError } = await import('/tmp/s09-fe/app-errors.mjs')

// ---- APP-02 页面反复进入/退出不叠加定时器 ----
let calls = 0
const controller = polling.createPolling({
  intervalMs: 10,
  run: async () => { calls++; return true },
})
controller.start()
controller.start()
controller.start()
assert.equal(controller.isRunning(), true)
await new Promise(r => setTimeout(r, 35))
controller.stop()
const afterStop = calls
assert.ok(calls >= 1, '应至少拉取一次')
await new Promise(r => setTimeout(r, 30))
assert.equal(calls, afterStop, 'stop 之后不得再轮询')
assert.equal(controller.isRunning(), false)

// 终态自动停止：run 返回 false 时不再继续
let terminalCalls = 0
const terminal = polling.createPolling({
  intervalMs: 5,
  run: async () => { terminalCalls++; return terminalCalls < 2 },
})
terminal.start()
await new Promise(r => setTimeout(r, 40))
assert.equal(terminal.isRunning(), false, '终态后应自动停止')
assert.equal(terminalCalls, 2, '终态后不应继续轮询')

// ---- APP-06 分页合并按 id 去重 ----
const page1 = { items: [{ id: 1 }, { id: 2 }], total: 3, page: 1, pageSize: 2 }
const page2 = { items: [{ id: 2 }, { id: 3 }], total: 3, page: 2, pageSize: 2 }
let loaded = paging.mergePage([], page1)
assert.deepEqual(loaded.map(i => i.id), [1, 2])
loaded = paging.mergePage(loaded, page2)
assert.deepEqual(loaded.map(i => i.id), [1, 2, 3], '重复项必须被去掉')
assert.equal(paging.hasMore(loaded, page2), false)
assert.equal(paging.hasMore(loaded.slice(0, 2), page2), true)
assert.equal(paging.nextPage(2), 3)

// ---- APP-03 重复点击：同一个提交动作共用一个幂等键 ----
const key = idem.newIdempotencyKey('upload')
assert.match(key, /^upload-[a-z0-9]+-[a-z0-9]+$/)
assert.notEqual(idem.newIdempotencyKey('upload'), idem.newIdempotencyKey('upload'))

// ---- 状态分流：处理中进进度页、终态进报告 ----
assert.equal(status.isRunning('QUEUED'), true)
assert.equal(status.isRunning('PARSING'), true)
assert.equal(status.isRunning('WAITING_ROLE'), true)
assert.equal(status.isRunning('COMPLETED'), false)
assert.equal(status.isRunning('FAILED'), false)
assert.equal(status.needsRoleConfirm('WAITING_ROLE'), true)
assert.equal(status.isFailed('INVALID'), true)

// ---- APP-04/08 错误文案：能恢复的给恢复动作 ----
const quota = new ApiError(429, '今日可用份数已用完', 'QUOTA_EXCEEDED')
assert.match(messageOf(quota), /免费份数用完/)
assert.equal(isRetryable(quota), false)
const unauthorized = new ApiError(401, '未登录或登录已过期', 'UNAUTHORIZED')
assert.match(messageOf(unauthorized), /重新进入/)
const processing = new ApiError(409, '处理中', 'PROCESSING')
assert.equal(isRetryable(processing), true)
assert.match(messageOf(new Error('boom')), /网络不稳定|稍后重试/)
assert.match(messageOf({ errMsg: 'request:fail timeout' }), /网络不稳定/)

console.log('app 逻辑限定验证：轮询/分页/幂等/状态/错误文案 全部通过')
