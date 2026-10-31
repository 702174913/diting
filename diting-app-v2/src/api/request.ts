/**
 * 服务端统一信封：{ code, msg, data }。
 * code === 200 表示成功，其余一律 reject 成 ApiError，避免调用方把失败当成功。
 * 登录态通过请求参数 `token`（query 或表单）传入，不是 Authorization 头；
 * 登录等接口使用 form-urlencoded。
 */
import { clearToken, getToken } from '@/utils/authToken'

export interface Envelope<T> {
  code: number
  msg: string | null
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

/** 接口基址：默认打到本地后端，可用 VITE_BASE_URL 覆盖；去掉结尾斜杠，避免拼出 `//path` */
const RAW_BASE_URL = import.meta.env.VITE_BASE_URL || 'http://localhost:8081'
export const BASE_URL = RAW_BASE_URL.replace(/\/+$/, '')

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

/** 普通请求、multipart 上传和 PDF 错误信封共用同一鉴权失效处理。 */
export function unwrapEnvelope<T>(raw: unknown, statusCode: number, token: string): T {
  const body = raw as Envelope<T> | undefined
  const code = statusCode >= 400 ? statusCode : body?.code
  if (isTokenExpiredCode(code ?? 0) && token === getToken()) {
    handleUnauthorized()
  }
  if (statusCode >= 400) {
    throw new ApiError(statusCode, body?.msg || `请求失败（HTTP ${statusCode}）`, symbolFor(statusCode))
  }
  if (!body || typeof body.code !== 'number') {
    throw new ApiError(statusCode, `响应格式不符合预期（HTTP ${statusCode}）`)
  }
  if (body.code !== SUCCESS_CODE) {
    throw new ApiError(body.code, body.msg || '请求失败', symbolFor(body.code))
  }
  return body.data
}

/** H5 使用 fetch 显式 omit，包含同源场景；仅关闭 XHR withCredentials 不能禁用同源 cookie。 */
async function requestInBrowser<T>(options: RequestOptions, token: string): Promise<T> {
  const method = options.method ?? 'GET'
  const form = options.form === true
  let url = withToken(`${BASE_URL}${options.url}`, token)
  const init: RequestInit = { method, credentials: 'omit' }
  if (method === 'GET') {
    const query = options.data ? encodeForm(options.data) : ''
    if (query) {
      url += `${url.includes('?') ? '&' : '?'}${query}`
    }
  }
  else {
    init.headers = { 'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json' }
    init.body = options.data ? (form ? encodeForm(options.data) : JSON.stringify(options.data)) : undefined
  }
  try {
    const response = await fetch(url, init)
    let body: unknown
    try {
      body = await response.json()
    }
    catch {
      // HTTP 错误或非信封响应统一交给解析器报错。
    }
    return unwrapEnvelope<T>(body, response.status, token)
  }
  catch (error) {
    if (error instanceof ApiError) {
      throw error
    }
    throw new ApiError(0, '网络异常，请稍后重试')
  }
}

export function request<T>(options: RequestOptions): Promise<T> {
  const token = options.token ?? getToken()
  if (typeof document !== 'undefined' && typeof fetch === 'function') {
    return requestInBrowser<T>(options, token)
  }
  const form = options.form === true
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: withToken(`${BASE_URL}${options.url}`, token),
      method: options.method ?? 'GET',
      data: form && options.data ? encodeForm(options.data) : options.data,
      withCredentials: false,
      header: {
        'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json',
      },
      success: (res) => {
        try {
          resolve(unwrapEnvelope<T>(res.data, res.statusCode, token))
        }
        catch (error) {
          reject(error)
        }
      },
      fail: (err) => {
        reject(new ApiError(0, (err as { errMsg?: string })?.errMsg ?? '网络异常，请稍后重试'))
      },
    })
  })
}
