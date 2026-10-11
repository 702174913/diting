import type { ReportDetail } from '@/types/api'

/** 版本 ID 不是成功凭证；句子数也不能代表通话级违规数。 */
export function reportPresentation(report: ReportDetail | null) {
  const published = report?.status === 'PUBLISHED' && report.reportVersionId !== null
  const failed = report?.status === 'FAILED' || (!published && report?.latestStatus === 'FAILED')
  const taskFailed = report?.taskStatus === 'FAILED' || report?.taskStatus === 'INVALID'
  const violationCount = report?.violationCount ?? 0
  const flaggedCount = report?.flaggedCount ?? 0
  const hasViolations = violationCount > 0 || flaggedCount > 0

  let headline = '还在准备记录'
  let lead = '识别与判读尚未完成，暂不能据此判断是否存在问题。'
  let versionLabel = ''
  if (published) {
    headline = hasViolations ? `发现 ${violationCount || flaggedCount} 项需要留意的情况` : '本轮规则未检出问题'
    lead = hasViolations
      ? '下面列出命中原文和通话级缺失项，请结合完整内容核对。'
      : '没有命中本次启用的规则，不代表内容一定没有问题。'
    versionLabel = report?.latestStatus === 'FAILED'
      ? '最新一次判读失败，当前展示之前成功发布的版本'
      : report?.latestStatus === 'ANALYZING'
        ? '最新一次判读尚未完成，当前展示之前成功发布的版本'
        : '已发布'
  }
  else if (failed || taskFailed) {
    headline = failed ? '记录生成失败' : '本次识别未完成'
    lead = '当前没有成功发布的报告，不能作为无违规结论，也不能导出。请重试或重新上传。'
    versionLabel = '未成功发布'
  }
  else if (report?.taskStatus === 'WAITING_ROLE') {
    lead = '正在根据转写结果生成报告；发布完成前不作无违规结论。'
  }
  return { published, headline, lead, versionLabel }
}
