import dayjs from 'dayjs'

/** 解析服务端 LocalDateTime.toString()，兼容秒/分为 0 时被省略的 ISO 片段 */
function parse(input: string | number | undefined) {
  if (input === undefined || input === '') {
    return dayjs('')
  }
  if (typeof input === 'number') {
    return dayjs(input)
  }
  return dayjs(input.replace(' ', 'T'))
}

/** 9月20日 下午 2:26 */
export function formatDateTime(input: string | number | undefined): string {
  const d = parse(input)
  if (!d.isValid()) {
    return ''
  }
  const hour = d.hour()
  const hour12 = hour % 12 === 0 ? 12 : hour % 12
  return `${d.month() + 1}月${d.date()}日 ${hour < 12 ? '上午' : '下午'} ${hour12}:${String(d.minute()).padStart(2, '0')}`
}

/** 9月20日 */
export function formatDate(input: string | number | undefined): string {
  const d = parse(input)
  return d.isValid() ? `${d.month() + 1}月${d.date()}日` : ''
}

/** 3 分 12 秒 */
export function formatDuration(sec: number | undefined): string {
  const total = Math.max(0, Math.round(sec ?? 0))
  const min = Math.floor(total / 60)
  const rest = total % 60
  return min > 0 ? `${min} 分 ${rest} 秒` : `${rest} 秒`
}

/** 00:41 —— 句子的毫秒时间戳转 mm:ss */
export function formatClock(ms: number | undefined): string {
  const total = Math.max(0, Math.floor((ms ?? 0) / 1000))
  const min = Math.floor(total / 60)
  const sec = total % 60
  return `${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}

const DAY_PARTS: [number, string][] = [
  [6, '凌晨'],
  [11, '上午'],
  [13, '中午'],
  [18, '下午'],
]

/** 周日，上午好 */
export function greeting(now: Date = new Date()): string {
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()]
  const hour = now.getHours()
  const hit = DAY_PARTS.find(([end]) => hour < end)
  return `${week}，${hit ? hit[1] : '晚上'}好`
}
