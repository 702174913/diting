/**
 * 记录 / 报告 / 导出：把听悟后端的数据结构映射成本项目 UI 需要的 TaskItem / ReportDetail。
 *
 * 后端能力边界（第一版对接）：
 * - 列表：/voice_translation/list（数组，20 条/页，page 从 0 起）
 * - 状态：/voice_translation/get（「任务进行中」或 { speakerId: content }）
 * - 报告：没有结构化违规模型，这里把转写按说话人分组、按句切分，<em> 高亮转成区间
 * - 导出：只有同步的 /pdf/export（固定模板）
 * - 删除 / 抹除：后端无对应接口，明确提示暂不支持，不伪造成功
 */
import { ApiError, BASE_URL } from './request'
import {
  PAGE_SIZE,
  type VoiceTranslationRaw,
  cacheTask,
  findVoiceTranslation,
  getVoiceTranslation,
  listVoiceTranslations,
  parseTranscript,
} from './tingwu'
import type { AssetItem, DeletionStatus, Paged, ReportDetail, Sentence, TaskItem, TaskStatus } from '@/types/api'

export type { TaskStatus }

function mapStatus(status: string): TaskStatus {
  if (status === 'COMPLETED') {
    return 'COMPLETED'
  }
  if (status === 'FAILED') {
    return 'FAILED'
  }
  return 'TRANSCRIBING'
}

function countModels(modelName: string | null): number {
  if (!modelName) {
    return 0
  }
  return modelName.split(/[,，、]/).map(item => item.trim()).filter(Boolean).length
}

function toTaskItem(task: VoiceTranslationRaw): TaskItem {
  const flagged = countModels(task.model_name)
  const completed = task.task_status === 'COMPLETED'
  return {
    id: task.voice_translation_id,
    taskId: task.task_id,
    status: mapStatus(task.task_status),
    durationSec: task.total_duration ?? 0,
    createdAt: task.createtime ?? '',
    flaggedCount: flagged,
    violationCount: flagged,
    reportPublished: completed && Boolean(task.result),
  }
}

/** 分页列表：本后端不给总量，按 20 条/页推断是否还有下一页 */
export async function fetchTaskList(
  token: string,
  page = 1,
  pageSize = PAGE_SIZE,
  status?: TaskStatus,
): Promise<Paged<TaskItem>> {
  const list = await listVoiceTranslations(Math.max(0, page - 1), token)
  const items = list.map(toTaskItem)
  const filtered = status ? items.filter(item => item.status === status) : items
  const hasNext = items.length >= pageSize
  return {
    items: filtered,
    total: (page - 1) * pageSize + items.length + (hasNext ? 1 : 0),
    page,
    pageSize,
  }
}

export async function fetchTaskStatus(id: number, token: string): Promise<TaskItem> {
  const task = await findVoiceTranslation(id, token)
  if (!task) {
    throw new ApiError(404, '记录不存在或已删除')
  }
  return toTaskItem(task)
}

/** 本后端没有独立的资产接口（图片 OCR 未接入），返回空列表让报告页正常渲染 */
export function fetchAssets(_taskId: number, _token: string): Promise<AssetItem[]> {
  return Promise.resolve([])
}

function splitSentences(content: string): string[] {
  return content.match(/[^。！？!?\n]+[。！？!?]?/g) ?? []
}

/** 把 <em>高亮</em> 解析成纯文本 + UTF-16 左闭右开区间 */
function parseMarked(raw: string): { text: string, highlights: { start: number, end: number }[] } {
  const source = raw.trim()
  const highlights: { start: number, end: number }[] = []
  let text = ''
  const re = /<em>(.*?)<\/em>/g
  let last = 0
  let match = re.exec(source)
  while (match) {
    text += source.slice(last, match.index)
    const start = text.length
    text += match[1]
    highlights.push({ start, end: text.length })
    last = re.lastIndex
    match = re.exec(source)
  }
  text += source.slice(last)
  return { text, highlights }
}

function buildSentences(transcript: Record<string, string>): Sentence[] {
  const sentences: Sentence[] = []
  let seq = 0
  for (const [speakerKey, content] of Object.entries(transcript)) {
    const speakerId = /^\d+$/.test(speakerKey) ? Number(speakerKey) : null
    for (const raw of splitSentences(String(content ?? ''))) {
      const parsed = parseMarked(raw)
      if (!parsed.text.trim()) {
        continue
      }
      seq++
      sentences.push({
        id: seq,
        sequence: seq,
        sourceAssetId: null,
        speakerId,
        role: 'UNKNOWN',
        startMs: 0,
        endMs: 0,
        text: parsed.text,
        speechSpeed: null,
        highlights: parsed.highlights.map(range => ({ sentenceId: seq, start: range.start, end: range.end })),
        ruleVersionIds: [],
        notes: parsed.highlights.length ? ['这句话命中了平台规则，请留意'] : [],
      })
    }
  }
  return sentences
}

export async function fetchReport(id: number, token: string, _version?: number): Promise<ReportDetail> {
  const task = await findVoiceTranslation(id, token)
  if (!task) {
    throw new ApiError(404, '记录不存在或已删除')
  }

  let transcript = parseTranscript(task.result)
  let status = task.task_status
  // 进行中：再问一次 /get，返回内容即视为已完成（列表的 result 可能有延迟）
  if (!transcript && status !== 'FAILED') {
    const live = await getVoiceTranslation(task.task_id, token)
    const liveTranscript = parseTranscript(live)
    if (liveTranscript) {
      transcript = liveTranscript
      status = 'COMPLETED'
      task.result = live as VoiceTranslationRaw['result']
      task.task_status = 'COMPLETED'
      cacheTask(task)
    }
  }

  const published = status === 'COMPLETED' && Boolean(transcript)
  const sentences = transcript ? buildSentences(transcript) : []
  const flaggedSentences = sentences.filter(item => item.notes.length > 0).length
  const flaggedCount = flaggedSentences || countModels(task.model_name)
  const mappedStatus = mapStatus(status)
  const speakerKeys = transcript ? Object.keys(transcript) : []
  const reportStatus: ReportDetail['status'] = published ? 'PUBLISHED' : mappedStatus === 'FAILED' ? 'FAILED' : 'ANALYZING'

  return {
    id: task.voice_translation_id,
    taskId: task.task_id,
    reportNo: task.task_id,
    taskStatus: mappedStatus,
    durationSec: task.total_duration ?? null,
    createdAt: task.createtime ?? '',
    reportVersionId: published ? 1 : null,
    reportVersion: published ? 1 : null,
    status: reportStatus,
    latestStatus: reportStatus,
    mappingRevision: 0,
    speakerMapping: speakerKeys.map((key) => {
      const speakerId = /^\d+$/.test(key) ? Number(key) : 0
      return { speakerId, role: 'UNKNOWN' as const }
    }),
    rolesConfirmed: true,
    sentenceCount: sentences.length,
    flaggedCount,
    violationCount: flaggedCount,
    riskScore: null,
    complianceScore: null,
    threshold: null,
    riskFlagged: flaggedCount > 0,
    publishedAt: published ? task.createtime ?? null : null,
    audioAvailable: false,
    notes: [],
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

/**
 * 签发下载地址：/pdf/export 需要鉴权头。
 * H5 端这里直接带鉴权拉一次字节流，转成同源 blob 地址交给页面的 fetch 另存；
 * 其他端把地址交给页面，由页面下载时补上鉴权头。
 */
export async function fetchExportDownload(
  exportId: number,
  token: string,
): Promise<{ exportId: number, fileType: string, downloadUrl: string, expiresAt: string | null }> {
  const url = `${BASE_URL}/pdf/export${token ? `?token=${encodeURIComponent(token)}` : ''}`
  if (typeof document !== 'undefined' && typeof fetch === 'function') {
    const res = await fetch(url)
    if (!res.ok) {
      throw new ApiError(res.status, '导出下载失败，请重试')
    }
    const objectUrl = URL.createObjectURL(await res.blob())
    return { exportId, fileType: 'pdf', downloadUrl: objectUrl, expiresAt: null }
  }
  return { exportId, fileType: 'pdf', downloadUrl: url, expiresAt: null }
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
