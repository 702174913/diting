/** 将 client 的音频/图片识别记录映射到现有列表和报告页面，不推断规则名或评分。 */
import { ApiError, BASE_URL, unwrapEnvelope } from './request'
import {
  PAGE_SIZE,
  type AudioTranslationRaw,
  type DialogueRaw,
  getAudioTranslation,
  listAudioTranslations,
  parseDialogues,
} from './tingwu'
import type { AssetItem, DeletionStatus, Paged, ReportDetail, Sentence, TaskItem, TaskStatus } from '@/types/api'

export type { TaskStatus }

/** task_status 只代表转写阶段，必须以业务 status=7/9 判断结束。 */
function mapStatus(status: number): TaskStatus {
  switch (status) {
    case 0: return 'QUEUED'
    case 2: return 'TRANSCRIBING'
    case 3: return 'PARSING'
    case 5: return 'ANALYZING'
    case 7: return 'COMPLETED'
    case 9: return 'FAILED'
    default: return 'ANALYZING'
  }
}

function taskDialogues(task: AudioTranslationRaw): { dialogue: DialogueRaw, kind: 'AUDIO' | 'IMAGE' }[] {
  const audioMatches = parseDialogues(task.match_result)
  const pictureMatches = parseDialogues(task.picture_match_results)
  const audio = audioMatches.length ? audioMatches : parseDialogues(task.handle_result)
  const pictures = pictureMatches.length ? pictureMatches : parseDialogues(task.picture_handle_results)
  return [
    ...audio.map(dialogue => ({ dialogue, kind: 'AUDIO' as const })),
    ...pictures.map(dialogue => ({ dialogue, kind: 'IMAGE' as const })),
  ]
}

function hasHit(dialogue: DialogueRaw): boolean {
  return dialogue.model_ids.length > 0 || dialogue.hit_texts.length > 0
}

function toTaskItem(task: AudioTranslationRaw): TaskItem {
  const dialogues = taskDialogues(task)
  const completed = task.status === 7
  const flagged = dialogues.filter(item => hasHit(item.dialogue)).length
  return {
    id: task.audio_translation_id,
    taskId: task.task_id ?? '',
    status: mapStatus(task.status),
    durationSec: task.audio_duration ?? 0,
    createdAt: task.createtime ?? '',
    sentenceCount: completed ? dialogues.length : undefined,
    flaggedCount: completed ? flagged : undefined,
    violationCount: completed ? flagged : undefined,
    reportPublished: completed,
  }
}

/** UI 页码从 1 起，后端从 0 起且固定 20 条；后端没有总量字段。 */
export async function fetchTaskList(
  token: string,
  page = 1,
  _pageSize = PAGE_SIZE,
  status?: TaskStatus,
): Promise<Paged<TaskItem>> {
  const list = await listAudioTranslations(Math.max(0, page - 1), token)
  const items = list.map(toTaskItem)
  return {
    items: status ? items.filter(item => item.status === status) : items,
    total: (page - 1) * PAGE_SIZE + items.length + (items.length === PAGE_SIZE ? 1 : 0),
    page,
    pageSize: PAGE_SIZE,
  }
}

async function requireTask(id: number, token: string): Promise<AudioTranslationRaw> {
  const task = await getAudioTranslation(id, token)
  if (!task) {
    throw new ApiError(404, '记录不存在或无权访问')
  }
  return task
}

export async function fetchTaskStatus(id: number, token: string): Promise<TaskItem> {
  return toTaskItem(await requireTask(id, token))
}

/** 当前不对接原件预览，也不拼接未经提供的文件访问地址。 */
export function fetchAssets(_taskId: number, _token: string): Promise<AssetItem[]> {
  return Promise.resolve([])
}

function buildSentences(task: AudioTranslationRaw): Sentence[] {
  return taskDialogues(task).map(({ dialogue, kind }, index) => {
    const id = index + 1
    const ranges: { start: number, end: number }[] = []
    // 原文作为纯文本展示，只对实际出现的 hit_texts 做高亮，不解释 HTML。
    for (const hit of dialogue.hit_texts) {
      let start = dialogue.content.indexOf(hit)
      while (start >= 0) {
        ranges.push({ start, end: start + hit.length })
        start = dialogue.content.indexOf(hit, start + hit.length)
      }
    }
    const merged: typeof ranges = []
    for (const range of ranges.sort((a, b) => a.start - b.start)) {
      const previous = merged[merged.length - 1]
      if (previous && range.start <= previous.end) {
        previous.end = Math.max(previous.end, range.end)
      }
      else {
        merged.push({ ...range })
      }
    }
    return {
      id,
      sequence: id,
      sourceAssetId: null,
      sourceKind: kind,
      speaker: dialogue.speaker,
      speakerId: /^\d+$/.test(dialogue.speaker) ? Number(dialogue.speaker) : null,
      role: 'UNKNOWN',
      startMs: 0,
      endMs: 0,
      hasTiming: false,
      text: dialogue.content,
      speechSpeed: null,
      highlights: merged.map(range => ({ sentenceId: id, ...range })),
      ruleVersionIds: dialogue.model_ids,
      modelIds: dialogue.model_ids,
      hitTexts: dialogue.hit_texts,
      notes: hasHit(dialogue) ? ['命中规则，请留意'] : [],
    }
  })
}

export async function fetchReport(id: number, token: string, _version?: number): Promise<ReportDetail> {
  const task = await requireTask(id, token)
  const published = task.status === 7
  const sentences = buildSentences(task)
  const flaggedCount = sentences.filter(item => item.notes.length > 0).length
  const reportStatus = published ? 'PUBLISHED' : task.status === 9 ? 'FAILED' : 'ANALYZING'
  return {
    id: task.audio_translation_id,
    taskId: task.task_id ?? '',
    reportNo: task.task_id || String(task.audio_translation_id),
    taskStatus: mapStatus(task.status),
    durationSec: task.audio_duration ?? null,
    createdAt: task.createtime ?? '',
    reportVersionId: published ? 1 : null,
    reportVersion: published ? 1 : null,
    status: reportStatus,
    latestStatus: reportStatus,
    mappingRevision: 0,
    speakerMapping: [],
    rolesConfirmed: true,
    sentenceCount: sentences.length,
    flaggedCount,
    violationCount: flaggedCount,
    riskScore: null,
    complianceScore: null,
    threshold: null,
    riskFlagged: flaggedCount > 0,
    publishedAt: published ? task.createtime : null,
    audioAvailable: false,
    notes: ['PDF 导出当前为示例报告，暂不对应本条记录。'],
    sentences,
    violations: [],
  }
}

// ---------------- 导出（同步 PDF） ----------------

export interface ExportStatus {
  exportId: number
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  withAudio: boolean
  fileType: 'pdf' | 'zip'
  errorCode: string | null
  expiresAt: string | null
}

/** 后端只有一次性的 /pdf/export，这里虚拟一个立即完成的作业以复用页面流程 */
export function createExport(
  _id: number,
  _reportVersionId: number,
  _token: string,
  withAudio = false,
): Promise<{ exportId: number, status: string, fileType: string }> {
  if (withAudio) {
    return Promise.reject(new ApiError(501, '当前仅支持导出 PDF 示例报告'))
  }
  return Promise.resolve({ exportId: 1, status: 'COMPLETED', fileType: withAudio ? 'zip' : 'pdf' })
}

export function fetchExport(exportId: number, _token: string): Promise<ExportStatus> {
  return Promise.resolve({
    exportId,
    status: 'COMPLETED',
    withAudio: false,
    fileType: 'pdf',
    errorCode: null,
    expiresAt: null,
  })
}

/** 轮询导出作业：本后端同步可用，直接返回终态；保留签名以复用页面逻辑 */
export async function waitExport(exportId: number, token: string, _timeoutMs = 60000): Promise<ExportStatus | null> {
  return fetchExport(exportId, token)
}

/** PDF 当前只接受 token、返回示例报告；后续后端提供参数后再关联记录。 */
export async function fetchExportDownload(
  exportId: number,
  token: string,
): Promise<{ exportId: number, fileType: string, downloadUrl: string, expiresAt: string | null }> {
  const url = `${BASE_URL}/pdf/export${token ? `?token=${encodeURIComponent(token)}` : ''}`
  if (typeof document !== 'undefined' && typeof fetch === 'function') {
    const res = await fetch(url, { credentials: 'omit' })
    if (res.headers.get('content-type')?.includes('json')) {
      unwrapEnvelope(await res.json(), res.status, token)
      throw new ApiError(500, '导出接口未返回 PDF 文件')
    }
    if (!res.ok) {
      throw new ApiError(res.status, '导出下载失败，请重试')
    }
    const blob = await res.blob()
    if (!(await blob.slice(0, 5).text()).startsWith('%PDF-')) {
      // 某些服务错误仍返回 HTTP 200，不能把 JSON/HTML 当作 PDF 下载。
      throw new ApiError(500, '导出接口未返回 PDF 文件')
    }
    const objectUrl = URL.createObjectURL(blob)
    return { exportId, fileType: 'pdf', downloadUrl: objectUrl, expiresAt: null }
  }
  return { exportId, fileType: 'pdf', downloadUrl: url, expiresAt: null }
}

/** 小程序下载成功不代表拿到了 PDF：HTTP 200 也可能是 code=202 的 JSON。 */
export async function validateDownloadedPdf(filePath: string, statusCode: number, token: string): Promise<void> {
  if (statusCode >= 400) {
    unwrapEnvelope(null, statusCode, token)
  }
  const fs = (uni as unknown as {
    getFileSystemManager?: () => {
      readFile: (options: {
        filePath: string
        success: (res: { data: ArrayBuffer }) => void
        fail: () => void
      }) => void
    }
  }).getFileSystemManager?.()
  if (!fs?.readFile) {
    throw new ApiError(501, '当前平台暂不支持读取导出文件')
  }
  const data = await new Promise<ArrayBuffer>((resolve, reject) => {
    fs.readFile({ filePath, success: res => resolve(res.data), fail: () => reject(new ApiError(500, '读取 PDF 失败，请重试')) })
  })
  const bytes = new Uint8Array(data)
  if (String.fromCharCode(...bytes.slice(0, 5)) === '%PDF-') {
    return
  }
  let body: unknown
  try {
    const text = decodeURIComponent(Array.from(bytes, byte => `%${byte.toString(16).padStart(2, '0')}`).join(''))
    body = JSON.parse(text)
  }
  catch {
    throw new ApiError(500, '导出接口未返回 PDF 文件')
  }
  unwrapEnvelope(body, statusCode, token)
  throw new ApiError(500, '导出接口未返回 PDF 文件')
}

// ---------------- 删除 / 抹除（后端未提供） ----------------

export function deleteRecording(_id: number, _token: string): Promise<{ deletionJobId: number, status: string }> {
  return Promise.reject(new ApiError(501, '当前服务暂不支持删除记录'))
}

export function fetchDeletion(_deletionJobId: number, _token: string): Promise<DeletionStatus> {
  return Promise.reject(new ApiError(501, '当前服务暂不支持查询清理状态'))
}

export function eraseAll(_token: string): Promise<DeletionStatus> {
  return Promise.reject(new ApiError(501, '当前服务暂不支持全部抹除'))
}
