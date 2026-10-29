/**
 * 冷启动授权门禁。
 *
 * 状态**只放内存**：小程序冷启动会重建 JS 上下文，所以「每次重新启动都要重新同意」
 * 天然成立，不需要也不能写持久化存储；同一次运行内切 tab、退到后台再回来不会重复打断。
 *
 * 三种情况：
 * - 还没选择（冷启动后）→ needsConsentGate() === true，启动页就是授权页；
 * - 已同意 → hasFullAccess() === true，上传/记录等完整功能可用；
 * - 已拒绝 → hasFullAccess() === false，允许浏览，但功能入口会引导回授权页。
 */
let decided = false
let agreed = false

export function needsConsentGate(): boolean {
  return !decided
}

export function hasFullAccess(): boolean {
  return decided && agreed
}

export function markAgreed() {
  decided = true
  agreed = true
}

export function markDeclined() {
  decided = true
  agreed = false
}

/** 仅测试与调试用：复位成「本次运行还没做过选择」 */
export function resetConsentGate() {
  decided = false
  agreed = false
}
