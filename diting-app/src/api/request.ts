/**
 * 服务端统一信封：{ code, message, data, symbol }。
 * 失败时 HTTP 状态码与 code 一致（401/403/404/409/422/429/503），symbol 是业务标识
 * （LOGIN_FAILED / QUOTA_EXCEEDED 等）。这里把信封拆开：
 * code=0 才 resolve data，其余一律 reject 成 ApiError，避免调用方把失败当成功。
 */
import { clearToken, getToken } from '@/utils/authToken'

export interface Envelope<T> {
  code: number
  message: string
  data: T
  symbol?: string | null
}

const SUCCESS_CODE = 0

export class ApiError extends Error {
  readonly code: number
  readonly symbol: string | null

  constructor(code: number, message: string, symbol?: string | null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.symbol = symbol ?? null
  }
}

export interface RequestOptions {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: Record<string, unknown>
  /** 不传则用本地存储的登录态；显式传入用于少数需要切换身份的调用 */
  token?: string
}

const BASE_URL = import.meta.env.VITE_BASE_URL

let unauthorizedHandler: (() => void) | null = null

/** 登录态失效（401）时由 store 注册的回调负责清理内存状态与引导重新登录 */
export function onUnauthorized(handler: () => void) {
  unauthorizedHandler = handler
}

function handleUnauthorized() {
  clearToken()
  unauthorizedHandler?.()
}

export function request<T>(options: RequestOptions): Promise<T> {
  const token = options.token ?? getToken()
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}${options.url}`,
      method: options.method ?? 'GET',
      data: options.data,
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      success: (res) => {
        const body = res.data as Envelope<T> | undefined
        if (!body || typeof body.code !== 'number') {
          reject(new ApiError(res.statusCode, `响应格式不符合预期（HTTP ${res.statusCode}）`))
          return
        }
        if (body.code !== SUCCESS_CODE) {
          // 401 表示登录态失效（过期、伪造、身份域不符）：清掉本地登录态，交给页面重新登录
          // 旧账号的删除状态凭据只用于显式查询；它过期不能清掉当前其他账号的登录态。
          if (body.code === 401 && token === getToken()) {
            handleUnauthorized()
          }
          reject(new ApiError(body.code, body.message || '请求失败', body.symbol))
          return
        }
        resolve(body.data)
      },
      fail: (err) => {
        reject(new ApiError(0, (err as { errMsg?: string })?.errMsg ?? '网络异常，请稍后重试'))
      },
    })
  })
}
