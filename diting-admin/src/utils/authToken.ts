/**
 * 后台登录态存储（localStorage）。
 * request 层、store、路由守卫共用同一份 key，避免三处各写一个字符串写歪。
 */
const TOKEN_KEY = 'diting_admin_token'
const ROLE_KEY = 'diting_admin_role'
const NICK_KEY = 'diting_admin_nickname'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function getRole(): string {
  return localStorage.getItem(ROLE_KEY) ?? ''
}

export function getNickname(): string {
  return localStorage.getItem(NICK_KEY) ?? ''
}

export function saveAuth(token: string, role: string, nickname: string) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(ROLE_KEY, role)
  localStorage.setItem(NICK_KEY, nickname)
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ROLE_KEY)
  localStorage.removeItem(NICK_KEY)
}
