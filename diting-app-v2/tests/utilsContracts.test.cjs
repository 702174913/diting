const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function loadUtil(file, injected = {}) {
  const source = readFileSync(path.join(__dirname, '../src/utils', file), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  } }).outputText
  const exports = {}
  vm.runInNewContext(code, { exports, ...injected }, { filename: file })
  return exports
}

test('历史 WAITING_ROLE 由后台推进，终态及删除停止轮询', () => {
  const status = loadUtil('taskStatus.ts')
  assert.equal(status.needsPolling('WAITING_ROLE'), true)
  assert.equal(status.statusLabel('WAITING_ROLE'), '正在生成报告')
  assert.equal(status.needsPolling('TRANSCRIBING'), true)
  for (const terminal of ['COMPLETED', 'FAILED', 'INVALID', 'DELETING', 'DELETED']) {
    assert.equal(status.needsPolling(terminal), false, terminal)
  }
  assert.equal(status.statusLabel('INVALID'), '没有可用语音')
  assert.equal(status.isFailed('INVALID'), true)
})

test('任务列表类型不再声明服务端不存在的进度百分比', () => {
  const source = readFileSync(path.join(__dirname, '../src/types/api.ts'), 'utf8')
  const taskItem = source.match(/export interface TaskItem \{[\s\S]*?\n\}/)?.[0]
  assert.ok(taskItem)
  assert.doesNotMatch(taskItem, /\bprogress\??:/)
  for (const target of ['components/RecordCard.vue', 'pages/upload/index.vue', 'pages/report/detail.vue']) {
    const page = readFileSync(path.join(__dirname, '../src', target), 'utf8')
    assert.doesNotMatch(page, /(?:task|item|res)\.progress\b/)
  }
})

test('进度页只剩重定向：轮询与状态只有记录页一份', () => {
  const page = readFileSync(path.join(__dirname, '../src/pages/progress/index.vue'), 'utf8')
  assert.match(page, /redirectTo/)
  assert.doesNotMatch(page, /setInterval|createPolling|fetchTaskStatus|fetchReport/)
})

test('网络及服务失败可重试，冲突与额度不足不误导自动重试', () => {
  class ApiError extends Error {
    constructor(code, symbol) {
      super('服务详情')
      this.code = code
      this.symbol = symbol
    }
  }
  const errors = loadUtil('errors.ts', { require: () => ({ ApiError }) })
  assert.equal(errors.messageOf(new ApiError(0, null)), '网络不稳定，请检查网络后重试')
  assert.equal(errors.messageOf(new ApiError(429, 'QUOTA_EXCEEDED')).includes('明天再来'), true)
  assert.equal(errors.isRetryable(new ApiError(503, null)), true)
  assert.equal(errors.isRetryable(new ApiError(409, 'CONFLICT')), false)
  assert.equal(errors.isRetryable(new ApiError(429, 'PROCESSING')), true)
})

test('离开页面后旧请求的错误不再提示，也不重新启动请求', async () => {
  let rejectRun
  let errors = 0
  let requests = 0
  const polling = loadUtil('polling.ts', {
    setInterval: callback => ({ callback }),
    clearInterval() {},
  }).createPolling({
    intervalMs: 1000,
    run() {
      requests++
      return new Promise((_, reject) => {
        rejectRun = reject
      })
    },
    onError() {
      errors++
    },
  })
  polling.start()
  polling.stop()
  rejectRun(new Error('old response'))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(errors, 0)
  assert.equal(requests, 1)
})

test('轮询在请求未返回时重启：旧终态不能停止新定时器，新轮立即拉取', async () => {
  const timers = []
  const resolves = []
  let requests = 0
  const polling = loadUtil('polling.ts', {
    setInterval(callback) {
      const timer = { callback, cancelled: false }
      timers.push(timer)
      return timer
    },
    clearInterval(timer) {
      timer.cancelled = true
    },
  }).createPolling({
    intervalMs: 1000,
    run() {
      requests++
      return new Promise(resolve => resolves.push(resolve))
    },
  })
  polling.start()
  assert.equal(requests, 1)
  polling.start()
  assert.equal(timers[0].cancelled, true)
  resolves[0](false)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(timers[1].cancelled, false)
  assert.equal(requests, 2)
  resolves[1](true)
  await new Promise(resolve => setImmediate(resolve))
  polling.stop()
  assert.equal(timers[1].cancelled, true)
})
