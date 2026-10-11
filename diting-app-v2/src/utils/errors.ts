import { ApiError } from '@/api/request'

/**
 * 把服务端错误翻译成用户能照着做的文案。
 * 原则：能恢复的要给出恢复动作，不能恢复的说清后果，绝不显示“未知错误”。
 */
export function messageOf(error: unknown): string {
  if (error instanceof ApiError) {
    // code=0 是客户端网络层失败（超时/断网/DNS）：errMsg 是 technical 文案，不能原样给用户看
    if (error.code === 0) {
      return '网络不稳定，请检查网络后重试'
    }
    switch (error.symbol) {
      case 'QUOTA_EXCEEDED':
        return '今天的免费份数用完了，明天再来或把录音合并成一段'
      case 'AUDIO_EXPIRED':
        return '原始录音已过保留期，请重新上传'
      case 'NOT_CONFIGURED':
        return '服务还没配置好，请联系管理员'
      case 'UNAUTHORIZED':
        return '登录已过期，请重新进入'
      case 'FORBIDDEN':
        return '没有权限执行这个操作'
      case 'CONFLICT':
        return '状态已变化，请刷新后重试'
      case 'PROCESSING':
        return '还在处理中，请稍后再看'
      case 'UNPROCESSABLE':
        return error.message || '这份文件暂时不能处理'
      default:
        return error.message || '操作失败，请稍后重试'
    }
  }
  const text = (error as { errMsg?: string })?.errMsg
  return text ? '网络不稳定，请检查后重试' : '操作失败，请稍后重试'
}

/** 是否值得提示用户「可重试」 */
export function isRetryable(error: unknown): boolean {
  if (error instanceof ApiError) {
    return error.code === 0 || error.code >= 500 || error.symbol === 'PROCESSING'
  }
  return true
}
