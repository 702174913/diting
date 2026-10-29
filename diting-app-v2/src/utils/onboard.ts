/**
 * 首次使用引导（三步介绍）的完成标记。
 *
 * key 带版本号：以后引导文案或步骤改了就把 ONBOARD_VERSION 加一，老用户会再看一次新引导，
 * 而不是永远看不到；用户清缓存或换设备则自然重弹。
 *
 * 只存一个本地布尔值：不发请求、不带上任何账号信息——引导先于登录展示，不能依赖登录态。
 */
export const ONBOARD_VERSION = 1

const ONBOARD_KEY = `diting_onboarded_v${ONBOARD_VERSION}`

export function hasOnboarded(): boolean {
  // uni 在键不存在时返回空字符串，不能只写成 Boolean()，否则 "false" 之类也会算「看过」
  return uni.getStorageSync(ONBOARD_KEY) === true
}

export function markOnboarded() {
  uni.setStorageSync(ONBOARD_KEY, true)
}
