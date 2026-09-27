import { request } from './request'
import type { QuotaSnapshot } from '@/types/api'

/**
 * 上传协议：服务端签发预签名 → 客户端**原始字节 PUT** → complete 校验 → 用 uploadId 登记任务。
 * 不再使用 uni.uploadFile（那是 multipart 表单，与预签名 PUT 的 method/body 不匹配）。
 */

export interface UploadSign {
  uploadId: number
  objectKey: string
  uploadUrl: string
  /** 固定为 PUT；发送时必须严格照用 */
  method: string
  headers: Record<string, string>
  expiresAt: string
  cleanupAfter: string
}

export interface UploadComplete {
  uploadId: number
  status: 'SIGNED' | 'VERIFIED' | 'CONSUMED'
  sizeBytes: number | null
  /** 真实时长由云端回填，上传阶段为 null，不伪造 */
  durationMs: number | null
  mediaFormat: string | null
  filename: string
  contentHash: string | null
}

/** 同意条款版本：条款文案变化时必须同步递增，服务端会记录该版本 */
export const CONSENT_VERSION = 'v1'

/** 客户端预检上限（服务端才是权威值：diting.media.max-size-bytes） */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024

/** 额度预检：提交前先看还能不能传，避免白传一次被拒 */
export function fetchQuota(token: string) {
  return request<QuotaSnapshot>({
    url: '/api/task/quota',
    token,
  })
}

/** 申请签名 */
export function signUpload(filename: string, declaredSize: number, token: string) {
  return request<UploadSign>({
    url: '/api/upload/sign',
    method: 'POST',
    data: { filename, consentVersion: CONSENT_VERSION, declaredSize },
    token,
  })
}

/** 签名过期后刷新同一会话（objectKey 不变） */
export function refreshUpload(uploadId: number, token: string) {
  return request<UploadSign>({
    url: '/api/upload/refresh',
    method: 'POST',
    data: { uploadId },
    token,
  })
}

/** 上传完成校验：对象存在、大小、真实格式由服务端判定 */
export function completeUpload(uploadId: number, token: string) {
  return request<UploadComplete>({
    url: '/api/upload/complete',
    method: 'POST',
    data: { uploadId },
    token,
  })
}

/**
 * 登记识别任务：只传 uploadId，服务端取可信 objectKey 并预占额度。
 * declaredDurationSec 只在客户端确实知道时长时才传（拿不到就省略，不要用 0 冒充）。
 */
export function submitTask(uploadIds: number[], declaredDurationSec: number | undefined, token: string,
  idempotencyKey?: string) {
  return request<{ id: number }>({
    url: '/api/task/submit',
    method: 'POST',
    data: {
      // 一次提交可含音频 ≤1 + 图片 ≤6；服务端按会话类型分型并落资产表
      uploadIds,
      declaredDurationSec: declaredDurationSec ?? null,
      idempotencyKey,
    },
    token,
  })
}


interface FileSystemManagerLike {
  readFile: (options: {
    filePath: string
    success: (res: { data: ArrayBuffer }) => void
    fail: (err: unknown) => void
  }) => void
}

/**
 * 原始字节 PUT。
 * - 微信小程序：文件读成 ArrayBuffer 后由 uni.request 发送二进制（拿不到上传百分比，只报状态）；
 * - H5：XHR 直接发送 File/Blob，可用 upload.onprogress 汇报进度。
 * 两条路径都不使用 FormData / multipart。
 */
export function uploadRaw(
  sign: UploadSign,
  filePath: string,
  onProgress?: (percent: number | null) => void,
): Promise<void> {
  const fs = (uni as unknown as { getFileSystemManager?: () => FileSystemManagerLike })
    .getFileSystemManager?.()
  if (fs) {
    return putViaArrayBuffer(sign, filePath, fs, onProgress)
  }
  return putViaXhr(sign, filePath, onProgress)
}

function putViaArrayBuffer(
  sign: UploadSign,
  filePath: string,
  fs: FileSystemManagerLike,
  onProgress?: (percent: number | null) => void,
): Promise<void> {
  onProgress?.(null)
  return new Promise<void>((resolve, reject) => {
    fs.readFile({
      filePath,
      success: (res) => {
        uni.request({
          url: sign.uploadUrl,
          method: 'PUT',
          header: sign.headers,
          data: res.data,
          success: (resp) => {
            if (resp.statusCode >= 200 && resp.statusCode < 300) {
              resolve()
              return
            }
            reject(new Error(`上传失败（HTTP ${resp.statusCode}）`))
          },
          fail: err => reject(err),
        })
      },
      fail: reject,
    })
  })
}

function putViaXhr(
  sign: UploadSign,
  filePath: string,
  onProgress?: (percent: number | null) => void,
): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    fetch(filePath)
      .then(res => res.blob())
      .then((blob) => {
        const xhr = new XMLHttpRequest()
        xhr.open(sign.method || 'PUT', sign.uploadUrl)
        Object.keys(sign.headers || {}).forEach((key) => {
          xhr.setRequestHeader(key, sign.headers[key])
        })
        xhr.upload.onprogress = (event) => {
          if (event.lengthComputable && event.total > 0) {
            onProgress?.(Math.round((event.loaded / event.total) * 100))
          }
        }
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            resolve()
            return
          }
          reject(new Error(`上传失败（HTTP ${xhr.status}）`))
        }
        xhr.onerror = () => reject(new Error('网络中断，请重试'))
        // 注意：发送的是原始 Blob，不包 FormData，Content-Type 用服务端签名时约定的值
        xhr.send(blob)
      })
      .catch(reject)
  })
}
