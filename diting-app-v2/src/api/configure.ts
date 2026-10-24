/**
 * 系统配置：听悟后端 `GET /configure/list` 返回一串 { key, value }。
 * key 是配置项名称（中文），value 是字符串值。协议清单/版本、免费份数、录音时长上限、
 * 风险阈值都从这里取。列表不带总量、需要 token，做进程内缓存避免每页重复请求。
 */
import { request } from './request'
import { getToken } from '@/utils/authToken'

export interface ConfigureItem {
  key: string
  value: string
}

let cached: Promise<ConfigureItem[]> | null = null
let cachedToken = ''
let publicCached: Promise<ConfigureItem[]> | null = null

/** 只缓存同一身份的成功请求；失败可重试，登录前不发受保护请求。 */
export function fetchConfigure(token = getToken()): Promise<ConfigureItem[]> {
  if (!token) {
    return Promise.reject(new Error('请先登录后获取配置'))
  }
  if (!cached || cachedToken !== token) {
    cachedToken = token
    const pending = request<ConfigureItem[]>({ url: '/configure/list', token })
      .then(items => Array.isArray(items) ? items : [])
      .catch((error) => {
        if (cached === pending) {
          cached = null
          cachedToken = ''
        }
        throw error
      })
    cached = pending
  }
  return cached
}

/** 公开配置：协议正文在登录前也要读取，接口本身允许无 token 访问。 */
export function fetchPublicConfigure(): Promise<ConfigureItem[]> {
  if (!publicCached) {
    const pending = request<ConfigureItem[]>({ url: '/configure/list', token: '' })
      .then(items => Array.isArray(items) ? items : [])
      .catch((error) => {
        if (publicCached === pending) {
          publicCached = null
        }
        throw error
      })
    publicCached = pending
  }
  return publicCached
}

export function clearConfigureCache(): void {
  cached = null
  cachedToken = ''
  publicCached = null
}

/** 转成 配置项名称 -> 值 的字典；拿不到时返回空对象，调用方用兜底值 */
export async function configureMap(token = getToken()): Promise<Record<string, string>> {
  try {
    const items = await fetchConfigure(token)
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
