/**
 * 服务端枚举 → 中文文案与徽标配色。
 *
 * 为什么要集中一处：这些值是英文码（TRANSCRIBING / COMPLETED / PUBLISHED …），
 * 页面直接渲染等于把内部状态机暴露给使用者；而各页各写一份又必然走样
 * （同一状态在不同页叫不同名字）。集中成一张表，筛选下拉、表格、徽标口径就一致了。
 *
 * 只做**展示**映射：提交给接口的值仍然是英文码，筛选下拉的 value 也是英文码。
 */

export type BadgeVariant =
  | 'default' | 'secondary' | 'outline' | 'up' | 'warn' | 'down' | 'gold' | 'destructive'

/** 任务状态（对齐后端 TaskStatus） */
export const TASK_STATUS_TEXT: Record<string, string> = {
  UPLOADED: '已上传',
  QUEUED: '排队中',
  SUBMITTING: '提交中',
  TRANSCRIBING: '转写中',
  PARSING: '解析中',
  WAITING_ROLE: '正在生成报告',
  ANALYZING: '判读中',
  COMPLETED: '已完成',
  FAILED: '处理失败',
  INVALID: '无有效语音',
  DELETING: '删除中',
}

/** 任务状态配色：只有真正收尾成功才给绿色，失败给红，其余按“进行中/需关注”处理 */
export const TASK_STATUS_VARIANT: Record<string, BadgeVariant> = {
  COMPLETED: 'up',
  FAILED: 'down',
  INVALID: 'warn',
  WAITING_ROLE: 'warn',
  DELETING: 'warn',
  UPLOADED: 'secondary',
}

/** 报告状态 */
export const REPORT_STATUS_TEXT: Record<string, string> = {
  ANALYZING: '判读中',
  PUBLISHED: '已发布',
  FAILED: '判读失败',
}

export const REPORT_STATUS_VARIANT: Record<string, BadgeVariant> = {
  PUBLISHED: 'up',
  FAILED: 'down',
  ANALYZING: 'warn',
}

/** 提交内容的媒体类型 */
export const MEDIA_KIND_TEXT: Record<string, string> = {
  AUDIO: '录音',
  IMAGE: '图片',
  MIXED: '录音 + 图片',
}

/** 图片 OCR 状态（音频资产恒为 SKIPPED） */
export const OCR_STATUS_TEXT: Record<string, string> = {
  PENDING: '待识别',
  RUNNING: '识别中',
  DONE: '已识别',
  EMPTY: '未识别到文字',
  FAILED: '识别失败',
  SKIPPED: '不需要识别',
}

export const OCR_STATUS_VARIANT: Record<string, BadgeVariant> = {
  DONE: 'up',
  EMPTY: 'warn',
  FAILED: 'down',
  RUNNING: 'warn',
}

/** 导出作业状态 */
export const EXPORT_STATUS_TEXT: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '生成中',
  COMPLETED: '已完成',
  FAILED: '生成失败',
}

/** 删除（清理）作业状态 */
export const DELETION_STATUS_TEXT: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  RETRY_WAIT: '等待重试',
  COMPLETED: '已完成',
  FAILED: '失败',
}

/** 上传会话状态 */
export const UPLOAD_STATUS_TEXT: Record<string, string> = {
  SIGNED: '已签发',
  VERIFIED: '已校验',
  CONSUMED: '已使用',
}

/** 额度流水状态 */
export const QUOTA_STATE_TEXT: Record<string, string> = {
  RESERVED: '预占',
  CONSUMED: '已消耗',
  RELEASED: '已释放',
}

/** 审计结果：DENIED 是“越权被拦下”，不是“失败” */
export const AUDIT_RESULT_TEXT: Record<string, string> = {
  SUCCESS: '成功',
  DENIED: '被拒绝（越权拦截）',
  FAILED: '失败',
}

export const AUDIT_RESULT_VARIANT: Record<string, BadgeVariant> = {
  SUCCESS: 'up',
  DENIED: 'warn',
  FAILED: 'down',
}

/** 审计动作码（取自后端 AuditLogService.ACTION_*） */
export const AUDIT_ACTION_TEXT: Record<string, string> = {
  SPEAKER_MAP_CONFIRM: '确认说话人角色',
  AUDIO_PRESIGN: '调取录音原件',
  ASSET_PRESIGN: '调取图片原件',
  CONFIG_SAVE: '保存策略配置',
  EXPORT_CREATE: '创建导出任务',
  EXPORT_DOWNLOAD: '下载导出产物',
  RULE_CREATE: '新增规则',
  RULE_UPDATE: '修改规则',
  RULE_DISABLE: '停用规则',
  RULE_IMPORT: '批量导入规则',
  RULE_IMPORT_PRECHECK: '批量导入预检',
  TASK_DELETE: '删除质检记录',
  ERASE_ALL: '抹除全部数据',
  ACCOUNT_CREATE: '新增后台账号',
  ACCOUNT_UPDATE: '修改后台账号',
  ACCOUNT_DELETE: '注销后台账号',
  ADMIN_LOGIN: '后台登录',
}

/** 审计对象类型 */
export const AUDIT_TARGET_TEXT: Record<string, string> = {
  TASK: '质检记录',
  TASK_ASSET: '图片资产',
  APP_CONFIG: '策略配置',
  RULE: '规则',
  RULE_SET: '规则集',
  ADMIN_USER: '后台账号',
  REPORT: '报告',
  USER: '用户',
}

/** 说话人角色（规则判读依赖它） */
export const SPEAKER_ROLE_TEXT: Record<string, string> = {
  AGENT: '催收方',
  CUSTOMER: '客户',
  UNKNOWN: '未确认',
}

/** 后台账号角色 */
export const ADMIN_ROLE_TEXT: Record<string, string> = {
  ADMIN: '技术管理员',
  OPERATOR: '运营',
  INVESTOR: '投资人',
}

/** 规则匹配类型 */
export const MATCH_TYPE_TEXT: Record<string, string> = {
  'hit': '关键词命中',
  'require': '触发后需应答',
  'must-have': '通话必需表述',
}

/** 规则约束的角色范围 */
export const RULE_SCOPE_TEXT: Record<string, string> = {
  ANY: '任意角色',
  AGENT: '催收方',
  CUSTOMER: '客户',
  UNKNOWN: '未知角色',
}

/** 指标来源（真实 / 演示 / 待采集） */
export const METRIC_SOURCE_TEXT: Record<string, string> = {
  REAL: '真实',
  DEMO: '演示',
  PENDING: '待采集',
}

/** 来源徽标配色：真实=绿、演示=黄、待采集=灰 */
export const METRIC_SOURCE_VARIANT: Record<string, BadgeVariant> = {
  REAL: 'up',
  DEMO: 'warn',
  PENDING: 'secondary',
}

/** 取展示文案；空值给「—」，未知值原样返回（宁可露出英文码，也不要静默显示空白） */
export function labelOf(map: Record<string, string>, value?: string | null) {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  return map[value] ?? value
}

/** 取徽标配色 */
export function variantOf(
  map: Record<string, BadgeVariant>,
  value?: string | null,
  fallback: BadgeVariant = 'secondary',
) {
  if (!value) {
    return fallback
  }
  return map[value] ?? fallback
}

/** 由映射生成下拉选项：label 中文、value 仍是英文码 */
export function optionsOf(map: Record<string, string>) {
  return Object.entries(map).map(([value, label]) => ({ value, label }))
}
