/**
 * 登录态存储。
 * request 层与 store 共用同一份 key：两处各写一个字符串迟早会写歪（比如清了一处、另一处还在）。
 */
const TOKEN_KEY = 'diting_token'

export function getToken(): string {
  return uni.getStorageSync(TOKEN_KEY) ?? ''
}

export function setToken(value: string) {
  uni.setStorageSync(TOKEN_KEY, value)
}

export function clearToken() {
  uni.removeStorageSync(TOKEN_KEY)
}
