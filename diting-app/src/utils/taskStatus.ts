/**
 * 任务状态（与后端 TaskStatus 一一对应，开发文档 §5.2）。
 * 旧值 PENDING/ONGOING 已由迁移脚本转换，前端不再出现这两者。
 */
export type TaskStatus
  = | 'UPLOADED'
    | 'QUEUED'
    | 'SUBMITTING'
    | 'TRANSCRIBING'
    | 'PARSING'
    | 'WAITING_ROLE'
    | 'ANALYZING'
    | 'COMPLETED'
    | 'FAILED'
    | 'INVALID'
    | 'DELETING'
    | 'DELETED'

const TERMINAL: TaskStatus[] = ['COMPLETED', 'FAILED', 'INVALID', 'DELETED']

/** 仍在处理中：卡片显示「处理中」，进度页继续轮询 */
export function isRunning(status: TaskStatus): boolean {
  return !TERMINAL.includes(status) && status !== 'DELETING'
}

/**
 * 历史 WAITING_ROLE 由后台自动发布报告，和其他处理中状态一样继续轮询。
 */
export function needsPolling(status: TaskStatus): boolean {
  return isRunning(status)
}

export function isFailed(status: TaskStatus): boolean {
  return status === 'FAILED' || status === 'INVALID'
}

/** 面向用户的状态文案（不暴露内部术语） */
export function statusLabel(status: TaskStatus): string {
  switch (status) {
    case 'UPLOADED':
    case 'QUEUED':
      return '排队中'
    case 'SUBMITTING':
      return '正在提交'
    case 'TRANSCRIBING':
      return '正在转写'
    case 'PARSING':
      return '正在整理文字'
    case 'WAITING_ROLE':
      return '正在生成报告'
    case 'ANALYZING':
      return '正在对照规范'
    case 'COMPLETED':
      return '已完成'
    case 'FAILED':
      return '处理失败'
    case 'INVALID':
      return '没有可用语音'
    case 'DELETING':
      return '正在删除'
    case 'DELETED':
      return '已删除'
    default:
      return '处理中'
  }
}
