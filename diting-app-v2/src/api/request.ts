/**
 * 服务端统一信封：{ code, msg, data }。
 * code === 200 表示成功，其余一律 reject 成 ApiError，避免调用方把失败当成功。
 * 登录态通过请求参数 `token`（query 或表单）传入，不是 Authorization 头；
 * 登录等接口使用 form-urlencoded。
 */
import { clearToken, getToken } from '@/utils/authToken'

export interface Envelope<T> {
  code: number
  msg: string
  data: T
}

const SUCCESS_CODE = 200

/**
 * 登录态失效的业务码：401（未授权）与 202（听悟后端「Token过期,请重新登录」）。
 * 命中后由 request() 清本地登录态并触发跳转登录。
 */
const TOKEN_EXPIRED_CODES = new Set([202, 401])

/** 业务码是否代表登录态失效，供调用方复用（避免把过期当普通失败提示） */
export function isTokenExpiredCode(code: number): boolean {
  return TOKEN_EXPIRED_CODES.has(code)
}

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
  /** 以 application/x-www-form-urlencoded 提交（后端 /user/login 等要求） */
  form?: boolean
}

/** 接口基址：默认打到本地后端，可用 VITE_BASE_URL 覆盖 */
export const BASE_URL = import.meta.env.VITE_BASE_URL || 'http://localhost:8081'

let unauthorizedHandler: (() => void) | null = null

/** 登录态失效（401）时由 store 注册的回调负责清理内存状态与引导重新登录 */
export function onUnauthorized(handler: () => void) {
  unauthorizedHandler = handler
}

function handleUnauthorized() {
  clearToken()
  unauthorizedHandler?.()
}

/** 把 HTTP/业务码翻译成本项目 errors.ts 认识的 symbol */
function symbolFor(code: number): string | null {
  switch (code) {
    case 202:
    case 401:
      return 'UNAUTHORIZED'
    case 403:
      return 'FORBIDDEN'
    case 409:
      return 'CONFLICT'
    case 422:
      return 'UNPROCESSABLE'
    case 429:
      return 'QUOTA_EXCEEDED'
    case 503:
      return 'UNAVAILABLE'
    default:
      return null
  }
}

function encodeForm(data: Record<string, unknown>): string {
  return Object.entries(data)
    .filter(([, value]) => value !== undefined && value !== null)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&')
}

/** 听悟后端从请求参数 `token`（query 或表单）取登录态，不是 Authorization 头 */
function withToken(url: string, token: string): string {
  if (!token) {
    return url
  }
  return `${url}${url.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`
}

export function request<T>(options: RequestOptions): Promise<T> {
  const token = options.token ?? getToken()
  const form = options.form === true
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: withToken(`${BASE_URL}${options.url}`, token),
      method: options.method ?? 'GET',
      data: form && options.data ? encodeForm(options.data) : options.data,
      header: {
        'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json',
      },
      success: (res) => {
        const body = res.data as Envelope<T> | undefined
        // 后端异常时 Spring 错误页会被统一信封包成 { code:200, data:{status:500,...} }，
        // 只看 code 会把 500 当成功。HTTP 状态才是权威，先按它拦。
        if (res.statusCode >= 400) {
          reject(new ApiError(res.statusCode, (body as { msg?: string } | undefined)?.msg || `请求失败（HTTP ${res.statusCode}）`))
          return
        }
        if (!body || typeof body.code !== 'number') {
          reject(new ApiError(res.statusCode, `响应格式不符合预期（HTTP ${res.statusCode}）`))
          return
        }
        if (body.code !== SUCCESS_CODE) {
          // 202/401 表示登录态失效（Token 过期、伪造、身份域不符）：清掉本地登录态，交给页面重新登录
          if (isTokenExpiredCode(body.code) && token === getToken()) {
            handleUnauthorized()
          }
          reject(new ApiError(body.code, body.msg || '请求失败', symbolFor(body.code)))
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
