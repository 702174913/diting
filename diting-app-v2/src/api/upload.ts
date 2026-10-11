/**
 * 上传接口：听悟后端只提供 multipart 单文件上传 `/upload/upload_file`。
 * 为了不改动上传页现有的「登记 + 幂等 + 重试」交互，这里保留 sign/uploadRaw/complete/submit
 * 四个函数的签名，内部把预签名 PUT 降级为 multipart 上传：
 *   signUpload  只分配本地会话号（token 走上传地址 query）
 *   uploadRaw   真正发送 multipart（小程序 uni.uploadFile / H5 XHR+FormData）
 *   complete   从本地会话里取回服务端返回的文件名/URL
 *   submitTask 调用 /voice_translation/add 登记转写任务，并解析出本地记录 id
 */
import { configureMap } from './configure'
import { ApiError, BASE_URL } from './request'
import { addVoiceTranslation, listVoiceTranslations } from './tingwu'
import type { QuotaSnapshot } from '@/types/api'

export interface UploadSign {
  uploadId: number
  objectKey: string
  uploadUrl: string
  /** multipart 固定为 POST */
  method: string
  headers: Record<string, string>
  expiresAt: string
  cleanupAfter: string
}

export interface UploadComplete {
  uploadId: number
  status: 'SIGNED' | 'VERIFIED' | 'CONSUMED'
  sizeBytes: number | null
  durationMs: number | null
  mediaFormat: string | null
  filename: string
  contentHash: string | null
}

/** 同意条款版本：本后端不校验，保留常量供页面/文案使用 */
export const CONSENT_VERSION = 'v1'

/** 客户端预检上限（服务端才是权威值） */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024

interface UploadedFile {
  fileName: string
  originalName: string
  fileUrl: string
}

const signs = new Map<number, UploadSign>()
const uploads = new Map<number, UploadedFile>()
let counter = 0

function delay(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

/** 配置项转正整数，取不到或非法时用兜底值 */
function toPositiveInt(raw: string | undefined, fallback: number): number {
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? Math.trunc(value) : fallback
}

/** 额度预检：免费份数 / 录音时长上限 / 风险阈值来自 /configure/list，取不到用兜底值 */
export async function fetchQuota(_token: string): Promise<QuotaSnapshot> {
  const config = await configureMap()
  const limit = toPositiveInt(config['每日免费份数'], 3)
  return {
    businessDate: new Date().toISOString().slice(0, 10),
    limit,
    reserved: 0,
    consumed: 0,
    remaining: limit,
    maxRecordingMinutes: toPositiveInt(config['最长录音时长'], 120),
    riskThreshold: toPositiveInt(config['风险阈值'], 0),
  }
}

/** 申请上传会话：分配本地 uploadId；token 通过上传地址的 query 传给后端 */
export function signUpload(filename: string, _declaredSize: number, token: string): Promise<UploadSign> {
  const uploadId = ++counter
  const sign: UploadSign = {
    uploadId,
    objectKey: filename,
    uploadUrl: `${BASE_URL}/upload/upload_file${token ? `?token=${encodeURIComponent(token)}` : ''}`,
    method: 'POST',
    headers: {},
    expiresAt: '',
    cleanupAfter: '',
  }
  signs.set(uploadId, sign)
  return Promise.resolve(sign)
}

/** 预签名刷新在本后端无意义：返回同一会话，重试沿用即可 */
export function refreshUpload(uploadId: number, token: string): Promise<UploadSign> {
  const existing = signs.get(uploadId)
  if (existing) {
    return Promise.resolve(existing)
  }
  return signUpload('', 0, token)
}

/** 上传完成校验：直接回读 multipart 上传时落库的结果 */
export function completeUpload(uploadId: number, _token: string): Promise<UploadComplete> {
  const stored = uploads.get(uploadId)
  if (!stored) {
    return Promise.reject(new ApiError(409, '上传会话已失效，请重新上传', 'CONFLICT'))
  }
  return Promise.resolve({
    uploadId,
    status: 'VERIFIED',
    sizeBytes: null,
    durationMs: null,
    mediaFormat: null,
    filename: stored.originalName,
    contentHash: null,
  })
}

/**
 * 登记识别任务：只接收单段录音。
 * 图片（多文件）在本后端没有对应能力，明确提示暂不支持，不伪造成功。
 */
export async function submitTask(
  uploadIds: number[],
  declaredDurationSec: number | undefined,
  token: string,
  _idempotencyKey?: string,
): Promise<{ id: number }> {
  if (uploadIds.length === 0) {
    throw new ApiError(400, '没有可提交的文件，请重新上传')
  }
  if (uploadIds.length > 1) {
    throw new ApiError(422, '当前服务暂不支持图片识别，请上传录音', 'UNPROCESSABLE')
  }
  const stored = uploads.get(uploadIds[0])
  if (!stored) {
    throw new ApiError(409, '上传会话已失效，请重新上传', 'CONFLICT')
  }
  const taskId = await addVoiceTranslation({
    fileName: stored.fileName,
    originalFileName: stored.originalName,
    fileUrl: stored.fileUrl,
    totalDuration: declaredDurationSec ?? 0,
  }, token)
  const id = await resolveCreatedId(taskId, token)
  return { id }
}

/** 创建任务后，列表里有轻微延迟：轮询几次把新建记录的本地 id 找回来 */
async function resolveCreatedId(taskId: string, token: string): Promise<number> {
  for (let attempt = 0; attempt < 3; attempt++) {
    const items = await listVoiceTranslations(0, token)
    const hit = items.find(item => item.task_id === taskId)
    if (hit) {
      return hit.voice_translation_id
    }
    await delay(800)
  }
  return 0
}

interface UploadFileResult {
  statusCode?: number
  data?: string
}

interface FileSystemManagerLike {
  readFile: (options: {
    filePath: string
    success: (res: { data: ArrayBuffer }) => void
    fail: (err: unknown) => void
  }) => void
}

function fileNameFromValue(value: string, fallback: string): string {
  if (/^https?:\/\//i.test(value)) {
    const clean = value.split('?')[0]
    return clean.split('/').pop() || fallback
  }
  return value || fallback
}

function acceptUploadResponse(sign: UploadSign, raw: string): void {
  let body: { code?: number, msg?: string, data?: unknown } | undefined
  try {
    body = JSON.parse(raw)
  }
  catch {
    body = undefined
  }
  if (!body || typeof body.code !== 'number' || body.code !== 200) {
    throw new ApiError(body?.code ?? 500, body?.msg || '上传失败，请重试')
  }
  const value = typeof body.data === 'string' ? body.data : ''
  uploads.set(sign.uploadId, {
    fileName: fileNameFromValue(value, sign.objectKey),
    originalName: sign.objectKey,
    fileUrl: value,
  })
}

/**
 * 原始字节上传（实际为 multipart）：
 * - 微信小程序：uni.uploadFile 发送文件，可拿到上传进度；
 * - H5：XHR + FormData 发送 Blob，可用 upload.onprogress 汇报进度。
 */
export function uploadRaw(
  sign: UploadSign,
  filePath: string,
  onProgress?: (percent: number | null) => void,
): Promise<void> {
  const fs = (uni as unknown as { getFileSystemManager?: () => FileSystemManagerLike })
    .getFileSystemManager?.()
  if (fs) {
    return uploadViaUni(sign, filePath, onProgress)
  }
  return uploadViaXhr(sign, filePath, onProgress)
}

function uploadViaUni(
  sign: UploadSign,
  filePath: string,
  onProgress?: (percent: number | null) => void,
): Promise<void> {
  onProgress?.(null)
  return new Promise<void>((resolve, reject) => {
    const task = uni.uploadFile({
      url: sign.uploadUrl,
      filePath,
      name: 'file',
      header: sign.headers,
      success: (res: UploadFileResult) => {
        try {
          acceptUploadResponse(sign, typeof res.data === 'string' ? res.data : '')
          resolve()
        }
        catch (error) {
          reject(error instanceof Error ? error : new Error('上传失败，请重试'))
        }
      },
      fail: err => reject(err instanceof Error ? err : new Error('上传失败，请重试')),
    })
    task?.onProgressUpdate?.((update) => {
      onProgress?.(Math.round(update.progress || 0))
    })
  })
}

function uploadViaXhr(
  sign: UploadSign,
  filePath: string,
  onProgress?: (percent: number | null) => void,
): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    fetch(filePath)
      .then(res => res.blob())
      .then((blob) => {
        const form = new FormData()
        form.append('file', blob, sign.objectKey)
        const xhr = new XMLHttpRequest()
        xhr.open('POST', sign.uploadUrl)
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
            try {
              acceptUploadResponse(sign, xhr.responseText)
              resolve()
            }
            catch (error) {
              reject(error instanceof Error ? error : new Error('上传失败，请重试'))
            }
            return
          }
          reject(new Error(`上传失败（HTTP ${xhr.status}）`))
        }
        xhr.onerror = () => reject(new Error('网络中断，请重试'))
        xhr.send(form)
      })
      .catch(error => reject(error instanceof Error ? error : new Error('上传失败，请重试')))
  })
}
