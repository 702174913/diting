import type { TaskItem } from '@/types/api'

/** 列表结论须有已发布报告支撑；通话级缺失项不一定有命中句。 */
export function taskSummary(task: TaskItem): { variant: 'run' | 'warn' | 'pass', text: string } {
  if (task.status === 'WAITING_ROLE')
    return { variant: 'run', text: '正在生成报告' }
  if (task.status === 'FAILED')
    return { variant: 'run', text: '识别失败，可以再试一次' }
  if (task.status === 'INVALID')
    return { variant: 'run', text: '任务无效' }
  if (task.status === 'QUEUED' || task.status === 'UPLOADED')
    return { variant: 'run', text: '排队中，马上开始' }
  if (task.status !== 'COMPLETED')
    return { variant: 'run', text: '正在处理' }
  if (!task.reportPublished)
    return { variant: 'run', text: '尚无已发布报告' }
  const count = Math.max(task.violationCount ?? 0, task.flaggedCount ?? 0)
  return count > 0
    ? { variant: 'warn', text: `发现 ${count} 项需要留意的情况` }
    : { variant: 'pass', text: '本轮规则未检出问题' }
}
