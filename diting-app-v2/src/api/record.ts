/** 将 client 的音频/图片识别记录映射到现有列表和报告页面，不推断规则名或评分。 */
import { configureMap } from './configure'
import { ApiError, BASE_URL, unwrapEnvelope } from './request'
import {
  PAGE_SIZE,
  type AudioTranslationRaw,
  type DialogueRaw,
  getAudioTranslation,
  listAudioTranslations,
  parseDialogues,
  parseTranscriptionDialogues,
} from './tingwu'
import type { AssetItem, DeletionStatus, MatchItem, Paged, ReportDetail, Sentence, TaskItem, TaskStatus } from '@/types/api'

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

/**
 * 以整理后的完整对话为底，把匹配结果里的 model_ids / hit_texts 合并进来。
 * 同一对话的两份列表通常等长同序，按下标对齐；长度不一致时按原文内容匹配，
 * 这样即使 match_result 只保存命中项，未命中的句子也不会从「对话」里消失。
 */
function mergeDialogueHits(base: DialogueRaw[], matches: DialogueRaw[]): DialogueRaw[] {
  if (!base.length) {
    return matches
  }
  if (!matches.length) {
    return base
  }
  if (base.length === matches.length) {
    return base.map((dialogue, index) => ({
      ...dialogue,
      speaker: dialogue.speaker || matches[index].speaker,
      model_ids: matches[index].model_ids,
      models: matches[index].models,
      hit_texts: matches[index].hit_texts,
    }))
  }
  const remaining = [...matches]
  return base.map((dialogue) => {
    const index = remaining.findIndex(match => match.content === dialogue.content)
    if (index < 0) {
      return dialogue
    }
    const [match] = remaining.splice(index, 1)
    return {
      ...dialogue,
      speaker: dialogue.speaker || match.speaker,
      model_ids: match.model_ids,
      models: match.models,
      hit_texts: match.hit_texts,
    }
  })
}

function taskDialogues(task: AudioTranslationRaw): { dialogue: DialogueRaw, kind: 'AUDIO' | 'IMAGE' }[] {
  const audioMatches = parseDialogues(task.match_result)
  const pictureMatches = parseDialogues(task.picture_match_results)
  const audioTranscript = parseDialogues(task.handle_result)
  const audio = mergeDialogueHits(
    audioTranscript.length ? audioTranscript : parseTranscriptionDialogues(task.translation_result),
    audioMatches,
  )
  const pictures = mergeDialogueHits(
    parseDialogues(task.picture_handle_results),
    pictureMatches,
  )
  return [
    ...audio.map(dialogue => ({ dialogue, kind: 'AUDIO' as const })),
    ...pictures.map(dialogue => ({ dialogue, kind: 'IMAGE' as const })),
  ]
}

function hasHit(dialogue: DialogueRaw): boolean {
  return dialogue.model_ids.length > 0 || dialogue.models.length > 0 || dialogue.hit_texts.length > 0
}

/** 问题清单：直接取 match_result / picture_match_results 里带命中的项。 */
function buildMatches(task: AudioTranslationRaw): MatchItem[] {
  const rows: { dialogue: DialogueRaw, source: 'AUDIO' | 'IMAGE' }[] = [
    ...parseDialogues(task.match_result).map(dialogue => ({ dialogue, source: 'AUDIO' as const })),
    ...parseDialogues(task.picture_match_results).map(dialogue => ({ dialogue, source: 'IMAGE' as const })),
  ]
  return rows.filter(({ dialogue }) => hasHit(dialogue)).map(({ dialogue, source }, index) => ({
    id: index + 1,
    source,
    speaker: dialogue.speaker,
    content: dialogue.content,
    hitTexts: dialogue.hit_texts,
    rules: dialogue.models,
  }))
}

function toTaskItem(task: AudioTranslationRaw): TaskItem {
  const dialogues = taskDialogues(task)
  const completed = task.status === 7
  const flagged = buildMatches(task).length
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

/**
 * 详情页会同时拉报告、原件和任务状态，三处都要这条记录。
 * 只合并「同一时刻在飞」的请求：首个请求结束后仍按轮询节奏重新拉最新状态。
 */
let taskInFlight: { key: string, promise: Promise<AudioTranslationRaw> } | null = null

function requireTask(id: number, token: string): Promise<AudioTranslationRaw> {
  const key = `${id}:${token}`
  if (taskInFlight?.key === key) {
    return taskInFlight.promise
  }
  const promise = getAudioTranslation(id, token).then((task) => {
    if (!task) {
      throw new ApiError(404, '记录不存在或无权访问')
    }
    return task
  }).finally(() => {
    if (taskInFlight?.promise === promise) {
      taskInFlight = null
    }
  })
  taskInFlight = { key, promise }
  return promise
}

export async function fetchTaskStatus(id: number, token: string): Promise<TaskItem> {
  return toTaskItem(await requireTask(id, token))
}

function splitKeys(raw: string | null | undefined): string[] {
  return raw ? raw.split(',').map(item => item.trim()).filter(Boolean) : []
}

function mediaFormatOf(name: string): string | null {
  const extension = name.split('.').pop()?.toLowerCase()
  return extension && extension !== name ? extension : null
}

/** 配置里的 OSS 前缀 + 详情返回的对象名；对象名可能含中文或空格，按路径段编码。 */
function objectUrl(base: string, key: string): string | null {
  const prefix = base.trim()
  const objectKey = key.trim()
  if (!prefix || !objectKey) {
    return null
  }
  if (/^https?:\/\//i.test(objectKey)) {
    return objectKey
  }
  const joined = `${prefix.endsWith('/') ? prefix : `${prefix}/`}${objectKey.replace(/^\/+/, '')}`
  return joined.split('/').map((segment, index) => {
    if (index < 3) {
      return segment
    }
    try {
      return encodeURIComponent(decodeURIComponent(segment))
    }
    catch {
      return encodeURIComponent(segment)
    }
  }).join('/')
}

/** 原件预览：录音与图片的对象名来自记录详情，前缀来自配置 OSS_BASE_URL。 */
export async function fetchAssets(taskId: number, token: string): Promise<AssetItem[]> {
  const [task, config] = await Promise.all([
    requireTask(taskId, token),
    configureMap(token),
  ])
  const base = config.OSS_BASE_URL ?? ''
  const assets: AssetItem[] = []
  if (task.audio) {
    assets.push({
      assetId: assets.length + 1,
      kind: 'AUDIO',
      sequence: 1,
      filename: task.audio_name || task.audio,
      mediaFormat: mediaFormatOf(task.audio),
      sizeBytes: null,
      ocrStatus: '',
      purged: false,
      url: objectUrl(base, task.audio),
      urlExpiresAt: null,
    })
  }
  const pictures = splitKeys(task.pictures)
  const pictureNames = splitKeys(task.picture_names)
  pictures.forEach((key, index) => {
    assets.push({
      assetId: assets.length + 1,
      kind: 'IMAGE',
      sequence: index + 1,
      filename: pictureNames[index] || key,
      mediaFormat: mediaFormatOf(key),
      sizeBytes: null,
      ocrStatus: '',
      purged: false,
      url: objectUrl(base, key),
      urlExpiresAt: null,
    })
  })
  return assets
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
      rules: dialogue.models,
      notes: hasHit(dialogue) && dialogue.models.length === 0 ? ['命中规则，请留意'] : [],
    }
  })
}

export async function fetchReport(id: number, token: string, _version?: number): Promise<ReportDetail> {
  const task = await requireTask(id, token)
  const published = task.status === 7
  const sentences = buildSentences(task)
  const matches = buildMatches(task)
  const flaggedCount = matches.length
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
    audioAvailable: Boolean(task.audio),
    notes: [],
    sentences,
    violations: [],
    matches,
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
    return Promise.reject(new ApiError(501, '当前仅支持导出 PDF 报告'))
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

/**
 * 导出指定记录的 PDF：/pdf/export 接受 token 与 audio_translation_id，
 * 后者用于导出本条记录的报告；不传时退回后端默认行为。
 */
function exportPdfUrl(token: string, audioTranslationId?: number): string {
  const query = [
    token ? `token=${encodeURIComponent(token)}` : '',
    typeof audioTranslationId === 'number' && Number.isSafeInteger(audioTranslationId) && audioTranslationId > 0
      ? `audio_translation_id=${audioTranslationId}`
      : '',
  ].filter(Boolean).join('&')
  return `${BASE_URL}/pdf/export${query ? `?${query}` : ''}`
}

function startsWithPdf(bytes: Uint8Array): boolean {
  return String.fromCharCode(...bytes.slice(0, 5)) === '%PDF-'
}

function fileSystem() {
  return (uni as unknown as {
    getFileSystemManager?: () => {
      readFile: (options: {
        filePath: string
        success: (res: { data: ArrayBuffer }) => void
        fail: () => void
      }) => void
      writeFile?: (options: {
        filePath: string
        data: ArrayBuffer
        success: () => void
        fail: () => void
      }) => void
    }
  }).getFileSystemManager?.()
}

function readFileBytes(filePath: string): Promise<Uint8Array> {
  const fs = fileSystem()
  if (!fs?.readFile) {
    return Promise.reject(new ApiError(501, '当前平台暂不支持读取文件'))
  }
  return new Promise<Uint8Array>((resolve, reject) => {
    fs.readFile({
      filePath,
      success: res => resolve(new Uint8Array(res.data)),
      fail: () => reject(new ApiError(500, '读取文件失败，请重试')),
    })
  })
}

function downloadToTemp(url: string): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    uni.downloadFile({
      url,
      success: (res) => {
        if (res.statusCode >= 400) {
          reject(new ApiError(res.statusCode, '下载失败，请重试'))
          return
        }
        resolve(res.tempFilePath)
      },
      fail: () => reject(new ApiError(0, '网络异常，请稍后重试')),
    })
  })
}

/** 导出记录的 PDF 字节：H5 直接拉流，小程序先下载临时文件再读取。 */
export async function fetchExportPdfBytes(audioTranslationId: number, token: string): Promise<Uint8Array> {
  const url = exportPdfUrl(token, audioTranslationId)
  if (typeof document !== 'undefined' && typeof fetch === 'function') {
    let res: Response
    try {
      res = await fetch(url, { credentials: 'omit' })
    }
    catch {
      throw new ApiError(0, '导出下载失败，请检查网络后重试')
    }
    if (res.headers.get('content-type')?.includes('json')) {
      unwrapEnvelope(await res.json(), res.status, token)
      throw new ApiError(500, '导出接口未返回 PDF 文件')
    }
    if (!res.ok) {
      throw new ApiError(res.status, '导出下载失败，请重试')
    }
    const bytes = new Uint8Array(await res.arrayBuffer())
    if (!startsWithPdf(bytes)) {
      throw new ApiError(500, '导出接口未返回 PDF 文件')
    }
    return bytes
  }
  const bytes = await readFileBytes(await downloadToTemp(url))
  if (startsWithPdf(bytes)) {
    return bytes
  }
  try {
    const text = decodeURIComponent(Array.from(bytes, byte => `%${byte.toString(16).padStart(2, '0')}`).join(''))
    unwrapEnvelope(JSON.parse(text), 200, token)
  }
  catch {
    // 解析失败统一按非 PDF 处理
  }
  throw new ApiError(500, '导出接口未返回 PDF 文件')
}

/** 原件字节：录音与图片都在配置的 OSS 下，按地址直接取。 */
export async function fetchOriginalBytes(url: string, _token: string): Promise<Uint8Array> {
  if (typeof document !== 'undefined' && typeof fetch === 'function') {
    let res: Response
    try {
      res = await fetch(url, { credentials: 'omit' })
    }
    catch {
      throw new ApiError(0, '原件下载失败，请检查网络或存储跨域配置')
    }
    if (!res.ok) {
      throw new ApiError(res.status, '原件下载失败，请重试')
    }
    return new Uint8Array(await res.arrayBuffer())
  }
  return readFileBytes(await downloadToTemp(url))
}

export async function fetchExportDownload(
  exportId: number,
  token: string,
  audioTranslationId?: number,
): Promise<{ exportId: number, fileType: string, downloadUrl: string, expiresAt: string | null }> {
  const url = exportPdfUrl(token, audioTranslationId)
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
