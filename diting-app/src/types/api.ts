import type { TaskStatus } from '@/utils/taskStatus'

export interface UserInfo {
  id: number
  username: string
  nickname: string
  hasPassword: boolean
  hasWechat: boolean
}

export interface LoginResult {
  token: string
  userInfo: UserInfo
  isNewUser: boolean
}

export type { TaskStatus } from '@/utils/taskStatus'

export interface TaskItem {
  id: number
  taskId: string
  status: TaskStatus
  durationSec: number
  createdAt: string
  /** 命中规则的句子数（「找到 N 处要留意的话」），未完成时为空 */
  flaggedCount?: number
  /** 转写出的句子总数（「听完的句子」） */
  sentenceCount?: number
  /** 最新已发布版本的违规总数，包含通话级缺失项 */
  violationCount?: number
  /** 没有已发布版本时，零计数不能解释为没有违规 */
  reportPublished?: boolean
}

/** 高亮区间：原文 UTF-16、左闭右开，直接可 slice */
export interface SentenceRange {
  sentenceId: number | null
  start: number
  end: number
}

export interface Sentence {
  id: number
  sequence: number
  /** OCR 句子对应的图片资产 ID；音频句子为 null */
  sourceAssetId: number | null
  speakerId: number | null
  /** 历史报告的业务角色；新任务不推断身份，界面直接展示 SpeakerId */
  role: 'AGENT' | 'CUSTOMER' | 'UNKNOWN' | null
  startMs: number
  endMs: number
  text: string
  speechSpeed: number | null
  /** 命中高亮（来自报告版本，不是缓存的 highlight 字符串） */
  highlights: SentenceRange[]
  ruleVersionIds: number[]
  /** 说明：为什么这几句要留意（没有命中时为空数组） */
  notes: string[]
}

/** 命中规则绑定的法律条款（V13）：服务端按规则当前绑定实时读取，不是发布时的快照 */
export interface ReportLegalClause {
  id: number
  lawName: string
  articleNo: string
  content: string
}

/** 一条命中/缺失证据：引用规则版本，带原文高亮 */
export interface Violation {
  id: number
  ruleVersionId: number
  ruleName: string | null
  matchType: 'hit' | 'require' | 'must-have' | null
  scope: string | null
  sentenceId: number | null
  triggerSentenceId: number | null
  evidenceSentenceIds: number[]
  ranges: SentenceRange[]
  deduct: number | null
  note: string
  /** 该规则当前绑定的条款；未绑定为空数组 */
  legalClauses: ReportLegalClause[]
}

export interface SpeakerRole {
  speakerId: number
  role: 'AGENT' | 'CUSTOMER' | 'UNKNOWN'
}

export interface ReportDetail {
  id: number
  taskId: string
  reportNo: string
  taskStatus: string
  durationSec: number | null
  createdAt: string
  /** 当前展示的报告版本（历史版本可读，最新一次重算失败时与 latestStatus 不同） */
  reportVersionId: number | null
  reportVersion: number | null
  status: 'ANALYZING' | 'PUBLISHED' | 'FAILED' | null
  latestStatus: 'ANALYZING' | 'PUBLISHED' | 'FAILED' | null
  mappingRevision: number
  speakerMapping: SpeakerRole[]
  rolesConfirmed: boolean
  sentenceCount: number
  /** 命中句数（按句子去重） */
  flaggedCount: number
  /** 违规定义数（含通话级缺失，因此可能大于命中句数） */
  violationCount: number
  /** 未发布时为空，不伪造 0 */
  riskScore: number | null
  complianceScore: number | null
  threshold: number | null
  riskFlagged: boolean
  publishedAt: string | null
  /** 原件是否仍在保留期（false 表示文本仍可读但录音已不可用） */
  audioAvailable: boolean
  notes: string[]
  sentences: Sentence[]
  violations: Violation[]
}

export interface UploadSign {
  uploadId: number
  objectKey: string
  uploadUrl: string
  /** 固定 PUT：原始字节上传，不是 multipart 表单 */
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

/** 分页信封（与后端 PageResult 一致） */
export interface Paged<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

/** 清理作业状态：只含状态与计数，不含原文或对象 key */
export interface DeletionStatus {
  deletionJobId: number
  targetType: 'TASK' | 'ERASE'
  status: 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'COMPLETED' | 'FAILED'
  attemptCount: number
  pendingObjects: number
  createdAt: string | null
  finishedAt: string | null
}

/** 任务资产（S16）：音频与图片；url 为短期地址，已过期或已清理时为空 */
export interface AssetItem {
  assetId: number
  kind: 'AUDIO' | 'IMAGE'
  sequence: number
  filename: string
  mediaFormat: string | null
  sizeBytes: number | null
  ocrStatus: string
  purged: boolean
  url: string | null
  urlExpiresAt: string | null
}

/** 额度概览：剩余 = 上限 - 预占 - 已消费 */
export interface QuotaSnapshot {
  businessDate: string
  limit: number
  reserved: number
  consumed: number
  remaining: number
  maxRecordingMinutes: number
  riskThreshold: number
}
