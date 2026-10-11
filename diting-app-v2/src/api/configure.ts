/**
 * 系统配置：听悟后端 `GET /configure/list` 返回一串 { key, value }。
 * key 是配置项名称（中文），value 是字符串值。协议清单/版本、免费份数、录音时长上限、
 * 风险阈值都从这里取。列表不带总量、无需登录，做进程内缓存避免每页重复请求。
 */
import { request } from './request'

export interface ConfigureItem {
  key: string
  value: string
}

let cached: Promise<ConfigureItem[]> | null = null

/** 拉取配置列表。失败会 reject（调用方自行决定兜底），成功的空数组也会被缓存 */
export function fetchConfigure(): Promise<ConfigureItem[]> {
  if (!cached) {
    cached = request<ConfigureItem[]>({ url: '/configure/list' })
      .then(items => Array.isArray(items) ? items : [])
  }
  return cached
}

export function clearConfigureCache(): void {
  cached = null
}

/** 转成 配置项名称 -> 值 的字典；拿不到时返回空对象，调用方用兜底值 */
export async function configureMap(): Promise<Record<string, string>> {
  try {
    const items = await fetchConfigure()
    return items.reduce<Record<string, string>>((acc, item) => {
      if (item && item.key) {
        acc[item.key] = String(item.value ?? '')
      }
      return acc
    }, {})
  }
  catch {
    return {}
  }
}
