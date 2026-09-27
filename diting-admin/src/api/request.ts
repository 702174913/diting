import axios from 'axios'
import { clearAuth, getToken } from '@/utils/authToken'

/**
 * 服务端统一信封：{ code, message, data, symbol }。
 * 失败时 HTTP 状态码与 code 一致，symbol 是业务标识（LOGIN_FAILED / ACCOUNT_DISABLED 等）。
 * 这里负责把信封拆开：code=0 才 resolve data，其余一律抛出 ApiError，
 * 否则调用方拿到的会是整个信封（例如登录时读 result.token 得到 undefined）。
 */
interface Envelope<T> {
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

/** 后台 API 统一查询串：保留 0/false，跳过未提供与空字符串，编码参数名和值。 */
export function buildQuery(params: Record<string, string | number | boolean | null | undefined>): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      query.append(key, String(value))
    }
  }
  return query.toString()
}

export interface RequestOptions {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: Record<string, unknown>
  /** 不传则用本地存储的登录态（登录接口本身不需要） */
  token?: string
}

/**
 * 登录页的绝对路径。
 * 不能写死 '/login'：后台部署在 /admin/ 子路径下时，登录页是 /admin/login，
 * 写死会让「路径不等」恒为真，并把失效会话整页跳到域名根的 /login
 * （那里是别的站点或 404）。用构建时的 base 拼，改部署路径不用再动这里。
 */
const BASE_PATH = import.meta.env.BASE_URL || '/'
// BASE_URL 约定以 / 结尾（'/' 或 '/admin/'）；万一给成不带的，也要补上，
// 否则会拼出 'adminlogin' 这种既不是绝对路径、也不是有效相对路径的东西。
const LOGIN_PATH = `${BASE_PATH.endsWith('/') ? BASE_PATH : `${BASE_PATH}/`}login`

/** 会话失效或实时角色复核拒绝时清理登录态；整页跳转同时清掉旧角色和组件内存。 */
function handleUnauthorized() {
  clearAuth()
  if (window.location.pathname !== LOGIN_PATH) {
    // 固定同源路径，不接受接口响应/查询串提供的跳转地址；替换失效会话的历史入口。
    window.location.replace(LOGIN_PATH)
  }
}

function toApiError(status: number, body: unknown): ApiError {
  const envelope = body as Envelope<unknown> | undefined
  if (envelope && typeof envelope.code === 'number') {
    if (envelope.code === 401 || (envelope.code === 403
      && (envelope.symbol === 'ACCOUNT_DISABLED' || envelope.symbol === 'FORBIDDEN'))) {
      handleUnauthorized()
    }
    return new ApiError(envelope.code, envelope.message || '请求失败', envelope.symbol)
  }
  if (status === 401 || status === 403) {
    handleUnauthorized()
  }
  return new ApiError(status, `请求失败（HTTP ${status}）`)
}

export function request<T>(options: RequestOptions): Promise<T> {
  const token = options.token ?? getToken()
  return axios({
    // 各调用处的路径已自带 /api 前缀，baseURL 仅保留为部署期覆盖入口
    baseURL: import.meta.env.VITE_API_BASE ?? '',
    url: options.url,
    method: options.method ?? 'GET',
    data: options.data,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  }).then((res) => {
    const body = res.data as Envelope<T> | undefined
    if (!body || typeof body.code !== 'number') {
      throw new ApiError(res.status, '响应格式不符合预期')
    }
    if (body.code !== SUCCESS_CODE) {
      throw toApiError(res.status, body)
    }
    return body.data
  }).catch((error) => {
    if (error instanceof ApiError) {
      throw error
    }
    // 非 2xx：HTTP 状态与信封 code 一致，优先用信封里的 message/symbol
    const status = error?.response?.status
    if (typeof status === 'number') {
      throw toApiError(status, error.response?.data)
    }
    throw new ApiError(0, '网络异常，请稍后重试')
  })
}
