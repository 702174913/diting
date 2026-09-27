import { buildQuery, request } from './request'

/**
 * 后台管理接口（开发文档 §7.3）。
 * 所有权限判断都在服务端：这里的返回值只用于「能不能显示」，不是权限本身。
 */

export interface Paged<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

export interface RecordingItem {
  id: number
  userId: number
  status: string
  durationSec: number | null
  createdAt: string | null
  audioAvailable: boolean
}

export interface SentenceRange {
  sentenceId: number | null
  start: number
  end: number
}

export interface AdminSentence {
  id: number
  sequence: number
  speakerId: number | null
  role: 'AGENT' | 'CUSTOMER' | 'UNKNOWN' | null
  startMs: number
  endMs: number
  text: string
  highlights: SentenceRange[]
  notes: string[]
}

export interface AdminViolation {
  id: number
  ruleVersionId: number
  ruleName: string | null
  matchType: string | null
  sentenceId: number | null
  triggerSentenceId: number | null
  ranges: SentenceRange[]
  deduct: number | null
  note: string
}

export interface AdminReportDetail {
  id: number
  taskId: string
  reportNo: string
  taskStatus: string
  /** 导出接口必填的版本 id（后端一直在返，此前前端类型漏了） */
  reportVersionId: number | null
  reportVersion: number | null
  status: 'ANALYZING' | 'PUBLISHED' | 'FAILED' | null
  latestStatus: 'ANALYZING' | 'PUBLISHED' | 'FAILED' | null
  mappingRevision: number
  rolesConfirmed: boolean
  flaggedCount: number
  violationCount: number
  riskScore: number | null
  complianceScore: number | null
  threshold: number | null
  riskFlagged: boolean
  audioAvailable: boolean
  redacted: boolean
  notes: string[]
  sentences: AdminSentence[]
  violations: AdminViolation[]
}

export interface RuleItem {
  ruleId: number
  code: string
  version: number
  groupName: string | null
  name: string
  description: string
  matchType: string
  roleScope: string
  triggerRole: string | null
  keywords: string[]
  expectedKeywords: string[]
  windowSize: number | null
  deduct: number
  enabled: boolean
}

export interface PolicyConfig {
  version: number
  dailyFreeQuota: number
  riskThreshold: number
  maxRecordingMinutes: number
  businessDate?: string
}

export interface AuditItem {
  id: number
  actorDomain: string
  actorId: number | null
  action: string
  targetType: string
  targetId: string | null
  result: string
  detail: Record<string, unknown>
  createdAt: string | null
}

export function fetchRecordings(token: string, params: Record<string, string | number | undefined>) {
  const query = buildQuery(params)
  return request<Paged<RecordingItem>>({ url: `/api/admin/recordings?${query}`, token })
}

/** 任务资产（S16）：后台可看图片，地址短期有效且每次签发留痕 */
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

export function fetchRecordingDetail(token: string, id: number) {
  return request<AdminReportDetail>({ url: `/api/admin/recordings/${id}`, token })
}

export function fetchRecordingAudio(token: string, id: number) {
  return request<{ url: string, expiresAt: string, ttlMinutes: number }>({
    url: `/api/admin/recordings/${id}/audio`,
    token,
  })
}

/** 记录下的资产（音频/图片）：服务端签发短期地址；已过期或已清理的 url 为空 */
export function fetchRecordingAssets(token: string, id: number) {
  return request<AssetItem[]>({
    url: `/api/admin/recordings/${id}/assets`,
    token,
  })
}

export function deleteRecording(token: string, id: number) {
  return request<{ deletionJobId: number, status: string }>({
    url: `/api/admin/recordings/${id}`,
    method: 'DELETE',
    token,
  })
}

/** 重新判读：按当前启用的规则集重算并发布新报告版本（历史版本保留）；仅 ADMIN */
export function reanalyzeRecording(token: string, id: number) {
  return request<ReanalyzeResult>({
    url: `/api/admin/recordings/${id}/reanalyze`,
    method: 'POST',
    token,
  })
}

export interface ReanalyzeResult {
  reportVersion: number
  riskScore: number
  complianceScore: number
  ruleSetVersion: number | null
}

export function fetchRules(token: string, params: Record<string, string | number | undefined> = {}) {
  const query = buildQuery(params)
  return request<Paged<RuleItem>>({ url: `/api/admin/rules?${query}`, token })
}

export function createRule(token: string, rule: Record<string, unknown>) {
  return request<RuleItem>({ url: '/api/admin/rules', method: 'POST', data: rule, token })
}

export function updateRule(token: string, ruleId: number, expectedVersion: number, rule: Record<string, unknown>) {
  return request<RuleItem>({
    url: `/api/admin/rules/${ruleId}`,
    method: 'PUT',
    data: { expectedVersion, rule },
    token,
  })
}

export function disableRule(token: string, ruleId: number) {
  return request<RuleItem>({ url: `/api/admin/rules/${ruleId}`, method: 'DELETE', token })
}

/** 版本历史（新到旧）：历史报告引用的是某一版，改规则只会追加新行 */
export function fetchRuleVersions(token: string, ruleId: number) {
  return request<RuleItem[]>({ url: `/api/admin/rules/${ruleId}/versions`, token })
}

/** 当前启用规则的冻结快照：分析时必须先取快照，所以运营需要能核对「这次判读到底用了哪些规则」 */
export interface RuleSetSnapshot {
  ruleSetVersion: number
  rules: { ruleId: number, ruleVersionId: number, name: string, matchType: string, deduct: number }[]
}

export function fetchRuleSnapshot(token: string) {
  return request<RuleSetSnapshot>({ url: '/api/admin/rules/snapshot', method: 'POST', token })
}

/** 批量导入的单行结果：line 含表头，从 1 起，方便对着自己的文件定位 */
export interface RuleImportRow {
  line: number
  code: string
  name: string
  action: 'CREATE' | 'UPDATE' | 'ERROR' | string
  currentVersion: number | null
  messages: string[]
}

export interface RuleImportPrecheck {
  total: number
  createCount: number
  updateCount: number
  errorCount: number
  rows: RuleImportRow[]
}

export interface RuleImportApply {
  created: number
  updated: number
  failed: number
  failures: RuleImportRow[]
}

/** 预检：只看不写，把「新增几行 / 改版几行 / 哪行错」先摆出来 */
export function precheckRuleImport(token: string, content: string) {
  return request<RuleImportPrecheck>({
    url: '/api/admin/rules/import/precheck',
    method: 'POST',
    data: { content },
    token,
  })
}

/** 落库：服务端重新解析，不信前端传来的预检结果 */
export function applyRuleImport(token: string, content: string) {
  return request<RuleImportApply>({
    url: '/api/admin/rules/import',
    method: 'POST',
    data: { content },
    token,
  })
}

export function fetchConfig(token: string) {
  return request<PolicyConfig>({ url: '/api/admin/config', token })
}

export function saveConfig(token: string, expectedVersion: number, config: Omit<PolicyConfig, 'version' | 'businessDate'>) {
  return request<PolicyConfig>({
    url: '/api/admin/config',
    method: 'POST',
    data: { expectedVersion, ...config },
    token,
  })
}

export function fetchAuditLogs(token: string, params: Record<string, string | number | undefined> = {}) {
  const query = buildQuery(params)
  return request<Paged<AuditItem>>({ url: `/api/admin/audit-logs?${query}`, token })
}

/** 后台账号列表项（不含密码哈希） */
export interface AdminAccountItem {
  id: number
  username: string
  nickname: string
  role: 'ADMIN' | 'OPERATOR' | 'INVESTOR' | string
  enabled: boolean
  createdAt: string | null
  /** 非空表示已注销（软删除） */
  deletedAt: string | null
}

export function fetchAccounts(
  token: string,
  params: { page?: number, pageSize?: number, role?: string, enabled?: string, includeDeleted?: boolean } = {},
) {
  const query = buildQuery(params)
  return request<Paged<AdminAccountItem>>({ url: `/api/admin/accounts?${query}`, token })
}

export function createAccount(
  token: string,
  payload: { username: string, password: string, nickname?: string, role: string },
) {
  return request<AdminAccountItem>({ url: '/api/admin/accounts', method: 'POST', data: { ...payload }, token })
}

/** 修改昵称/角色/启用状态/密码；传 password 即重置密码（服务端按 PasswordPolicy 校验） */
export function updateAccount(
  token: string,
  id: number,
  payload: { nickname?: string, role?: string, enabled?: boolean, password?: string },
) {
  return request<AdminAccountItem>({ url: `/api/admin/accounts/${id}`, method: 'PUT', data: { ...payload }, token })
}

/** 注销账号（软删除）：标记 + 停用 + 用户名脱敏，行保留供审计追溯 */
export function deleteAccount(token: string, id: number) {
  return request<AdminAccountItem>({ url: `/api/admin/accounts/${id}`, method: 'DELETE', token })
}

/** 导出作业（S11）：异步生成 PDF/ZIP；完成后调 /download 显式签发下载地址 */
export interface ExportJob {
  exportId: number
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string
  withAudio: boolean
  /** withAudio 为真时是 zip，否则 pdf */
  fileType: string
  errorCode: string | null
  createdAt: string | null
  expiresAt: string | null
}

/**
 * 创建导出作业（仅 ADMIN，后端 @RequireRole(ADMIN)：OPERATOR 调任何后台导出路由都 403）。
 * withAudio 打成 ZIP 带原件；ADMIN 原文导出，与录音详情一致。
 */
export function createRecordingExport(
  token: string,
  id: number,
  payload: { reportVersionId: number, withAudio?: boolean },
) {
  return request<ExportJob>({
    url: `/api/admin/recordings/${id}/exports`,
    method: 'POST',
    data: { ...payload },
    token,
  })
}

/** 导出状态：只返回状态与期限，不含下载地址；作业归属校验在服务端（非 ADMIN 角色 403，不属于当前账号 404） */
export function fetchExportJob(token: string, exportId: number) {
  return request<ExportJob>({ url: `/api/admin/exports/${exportId}`, token })
}

/**
 * 后台下载：作业完成后显式调用一次签发短时下载地址。
 * 状态轮询不再签发、不写审计，点下载才记一条 EXPORT_DOWNLOAD。
 */
export function fetchExportDownload(token: string, exportId: number) {
  return request<{ exportId: number, fileType: string, downloadUrl: string, expiresAt: string | null }>({
    url: `/api/admin/exports/${exportId}/download`,
    token,
  })
}

/** 用户协议（S19）：正文由 admin 富文本维护，注册时快照版本 */
export interface AgreementItem {
  key: string
  title: string
  version: number
  contentHtml: string
}

export function fetchAgreements(token: string) {
  return request<AgreementItem[]>({ url: '/api/admin/agreements', token })
}

export function saveAgreement(token: string, key: string, title: string, contentHtml: string) {
  return request<AgreementItem>({
    url: `/api/admin/agreements/${key}`,
    method: 'PUT',
    data: { title, contentHtml },
    token,
  })
}
