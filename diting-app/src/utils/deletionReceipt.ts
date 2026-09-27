/**
 * 抹除后原登录态必须撤销；仅把旧 token 连同作业号留作限权状态查询。
 * 服务端已将该 token 限定为本人 GET /api/deletions/{id}，不再允许读写业务数据。
 */
const KEY = 'diting_deletion_status_receipt'
const MAX_AGE_MS = 72 * 60 * 60 * 1000

export interface DeletionReceipt {
  jobId: number
  token: string
  createdAt: number
}

export function saveDeletionReceipt(jobId: number, token: string): DeletionReceipt {
  const receipt = { jobId, token, createdAt: Date.now() }
  uni.setStorageSync(KEY, receipt)
  return receipt
}

export function clearDeletionReceipt() {
  uni.removeStorageSync(KEY)
}

export function getDeletionReceipt(): DeletionReceipt | null {
  const value = uni.getStorageSync(KEY) as unknown
  if (!value || typeof value !== 'object') {
    return null
  }
  const receipt = value as Partial<DeletionReceipt>
  if (!Number.isSafeInteger(receipt.jobId) || !receipt.jobId || typeof receipt.token !== 'string'
    || !receipt.token || typeof receipt.createdAt !== 'number'
    || receipt.createdAt > Date.now() || Date.now() - receipt.createdAt > MAX_AGE_MS) {
    clearDeletionReceipt()
    return null
  }
  return receipt as DeletionReceipt
}
