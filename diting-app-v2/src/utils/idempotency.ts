/**
 * 提交幂等键：一次「提交动作」只生成一个键，重试沿用同一个键。
 * 这样即使用户连点或弱网重试，服务端也只会登记一个任务、扣一份额度。
 */
export function newIdempotencyKey(prefix = 'app'): string {
  const random = Math.random().toString(36).slice(2, 10)
  return `${prefix}-${Date.now().toString(36)}-${random}`
}
