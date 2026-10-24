/** multipart 上传和分类提交；沿用页面现有的上传会话及重试交互。 */
import { configureMap } from './configure'
import { ApiError, BASE_URL, unwrapEnvelope } from './request'
import { addAudioTranslation } from './tingwu'
import { AUDIO_EXTENSIONS, IMAGE_EXTENSIONS, MAX_IMAGES, MAX_UPLOAD_BYTES } from '@/utils/choose'
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

export { MAX_UPLOAD_BYTES } from '@/utils/choose'

interface UploadedFile {
  fileName: string
  originalName: string
}

const signs = new Map<number, UploadSign>()
const uploads = new Map<number, UploadedFile>()
let counter = 0

/** 配置项转正整数，取不到或非法时用兜底值 */
function toPositiveInt(raw: string | undefined, fallback: number): number {
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? Math.trunc(value) : fallback
}

/** 额度预检：免费份数 / 录音时长上限 / 风险阈值来自 /configure/list，取不到用兜底值 */
export async function fetchQuota(_token: string): Promise<QuotaSnapshot> {
  const config = await configureMap(_token)
  const limit = toPositiveInt(config['每日免费份数'], 3)
  return {
    businessDate: new Date().toISOString().slice(0, 10),
    limit,
    reserved: 0,
    consumed: 0,
    remaining: limit,
    maxRecordingSeconds: toPositiveInt(config['最长录音时长'], 300),
    riskThreshold: toPositiveInt(config['风险阈值'], 0),
  }
}

/** 申请上传会话：分配本地 uploadId；token 通过上传地址的 query 传给后端 */
export function signUpload(filename: string, _declaredSize: number, token: string): Promise<UploadSign> {
  const extension = filename.split('.').pop()?.toLowerCase() ?? ''
  if (![...AUDIO_EXTENSIONS, ...IMAGE_EXTENSIONS].includes(extension)) {
    return Promise.reject(new ApiError(422, '仅支持 MP3 音频、JPG/PNG 图片'))
  }
  if (_declaredSize <= 0 || _declaredSize > MAX_UPLOAD_BYTES) {
    return Promise.reject(new ApiError(422, '文件为空或超过 10MB，请重新选择'))
  }
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

/** 同次提交允许最多一段 MP3 与最多六张图片（可混合）；返回后端给出的新记录 ID（拿不到时为 null）。 */
export async function submitTask(
  uploadIds: number[],
  declaredDurationSec: number | undefined,
  token: string,
  _idempotencyKey?: string,
): Promise<number | null> {
  if (uploadIds.length === 0) {
    throw new ApiError(400, '没有可提交的文件，请重新上传')
  }
  const files = uploadIds.map((id) => {
    const file = uploads.get(id)
    if (!file) {
      throw new ApiError(409, '上传会话已失效，请重新上传', 'CONFLICT')
    }
    return file
  })
  const audio = files.filter(file => /\.mp3$/i.test(file.originalName))
  const pictures = files.filter(file => /\.(jpg|png)$/i.test(file.originalName))
  if (audio.length + pictures.length !== files.length
    || audio.length > 1 || pictures.length > MAX_IMAGES) {
    throw new ApiError(422, '请提交最多一段 MP3 音频和最多六张 JPG/PNG 图片')
  }
  // 不选的类别传空串；文件名只作展示，上传返回名必须原样交给后端。
  const id = await addAudioTranslation({
    picture_names: pictures.map(file => file.originalName).join(','),
    pictures: pictures.map(file => file.fileName).join(','),
    audio_name: audio[0]?.originalName ?? '',
    audio: audio[0]?.fileName ?? '',
    audio_duration: audio.length && Number.isFinite(declaredDurationSec)
      ? Math.max(0, Math.ceil(declaredDurationSec!)) : 0,
  }, token)
  uploadIds.forEach((uploadId) => {
    uploads.delete(uploadId)
    signs.delete(uploadId)
  })
  return id
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

function acceptUploadResponse(sign: UploadSign, raw: string, statusCode = 200): void {
  let body: unknown
  try {
    body = JSON.parse(raw)
  }
  catch {
    throw new ApiError(statusCode, '上传响应格式不正确，请重试')
  }
  const token = decodeURIComponent(sign.uploadUrl.split('token=')[1] ?? '')
  const value = unwrapEnvelope<unknown>(body, statusCode, token)
  if (typeof value !== 'string' || !value.trim()) {
    throw new ApiError(500, '上传未返回文件名，请重试')
  }
  uploads.set(sign.uploadId, { fileName: value, originalName: sign.objectKey })
}

/**
 * 原始字节上传（实际为 multipart）：
 * - 微信小程序：uni.uploadFile 发送文件，可拿到上传进度；
 * - H5：fetch + FormData 明确禁止携带 cookie；无可靠百分比时只展示上传阶段。
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
  return uploadViaBrowser(sign, filePath, onProgress)
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
          acceptUploadResponse(sign, typeof res.data === 'string' ? res.data : '', res.statusCode ?? 200)
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

async function uploadViaBrowser(
  sign: UploadSign,
  filePath: string,
  onProgress?: (percent: number | null) => void,
): Promise<void> {
  onProgress?.(null)
  const source = await fetch(filePath, { credentials: 'omit' })
  if (!source.ok) {
    throw new ApiError(source.status, '读取所选文件失败，请重新选择')
  }
  const form = new FormData()
  form.append('file', await source.blob(), sign.objectKey)
  // 不手写 Content-Type，由浏览器生成 multipart boundary。
  const response = await fetch(sign.uploadUrl, {
    method: 'POST', credentials: 'omit', headers: sign.headers, body: form,
  })
  acceptUploadResponse(sign, await response.text(), response.status)
  onProgress?.(100)
}
