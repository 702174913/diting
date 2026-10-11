/**
 * 从 JWT 里取出 user_id。
 * 后端文档说明 Token 由 JWT 生成且包含 user_id；用它免去额外一次「当前用户」查询，
 * 也让只有 token 的调用方（如列表接口）能带上 user_id。
 * 只读取，不校验签名——签名校验由服务端负责。
 */
const BASE64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

function base64Decode(input: string): string {
  const normalized = input.replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '')
  let output = ''
  let buffer = 0
  let bits = 0
  for (const char of normalized) {
    const value = BASE64_CHARS.indexOf(char)
    if (value === -1) {
      continue
    }
    buffer = (buffer << 6) | value
    bits += 6
    if (bits >= 8) {
      bits -= 8
      output += String.fromCharCode((buffer >> bits) & 0xFF)
    }
  }
  return output
}

/** 解析失败或没有 user_id 时返回 null，调用方据此提示重新登录 */
export function decodeJwtUserId(token: string): number | null {
  try {
    const payload = token.split('.')[1]
    if (!payload) {
      return null
    }
    const json = base64Decode(payload)
    const claims = JSON.parse(json) as { user_id?: unknown, userId?: unknown, uid?: unknown, payload?: unknown }
    // 听悟后端把用户 id 放在自定义 claim `payload` 里（字符串形式，如 "6"）。
    const raw = claims.user_id ?? claims.userId ?? claims.uid ?? claims.payload
    const id = typeof raw === 'string' ? Number(raw) : raw
    return typeof id === 'number' && Number.isSafeInteger(id) ? id : null
  }
  catch {
    return null
  }
}
