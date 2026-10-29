/**
 * 分享归因：分享路径带分享者 `user_id`，接收方进入时先落本地，
 * 等登录成功后随登录接口的 `parent_user_id` 一起提交，提交成功即清除。
 */
const PARENT_USER_ID_KEY = 'diting_parent_user_id'

function toUserId(value: unknown): number | null {
  const raw = Array.isArray(value) ? value[0] : value
  const id = typeof raw === 'string' ? Number(raw) : raw
  return typeof id === 'number' && Number.isSafeInteger(id) && id > 0 ? id : null
}

function readStorage(): unknown {
  try {
    return typeof uni?.getStorageSync === 'function' ? uni.getStorageSync(PARENT_USER_ID_KEY) : ''
  }
  catch {
    return ''
  }
}

/** 分享进入时调用：收到合法的 `user_id` 才覆盖，脏参数不影响已有归因 */
export function captureParentUserId(query?: Record<string, unknown> | null) {
  const id = toUserId(query?.user_id)
  if (!id) {
    return
  }
  try {
    uni.setStorageSync(PARENT_USER_ID_KEY, id)
  }
  catch {
    // 存储不可用时放弃归因，不阻断进入
  }
}

export function getParentUserId(): number | null {
  return toUserId(readStorage())
}

export function clearParentUserId() {
  try {
    if (typeof uni?.removeStorageSync === 'function') {
      uni.removeStorageSync(PARENT_USER_ID_KEY)
    }
  }
  catch {
    // 清除失败最多下次再带上，不阻断登录
  }
}

/** 给分享路径追加分享者 user_id；没有 user_id 时原样返回 */
export function sharePath(path: string, userId: number | null): string {
  if (!userId) {
    return path
  }
  return `${path}${path.includes('?') ? '&' : '?'}user_id=${userId}`
}
