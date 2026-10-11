import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UserInfo } from '@/types/api'
import { fetchUserInfo, loginByWechat } from '@/api/auth'
import { ApiError, onUnauthorized } from '@/api/request'
import { clearToken, getToken, setToken } from '@/utils/authToken'
import { redirectAfterUnauthorized } from '@/utils/h5Auth'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(getToken())
  const userInfo = ref<UserInfo | null>(null)
  const consentRequired = ref(false)

  function setLogin(value: string) {
    token.value = value
    setToken(value)
    consentRequired.value = false
  }

  function clear() {
    token.value = ''
    userInfo.value = null
    consentRequired.value = false
    clearToken()
  }

  // 请求层遇到 401 会清掉本地登录态并回调这里，页面据此引导重新登录
  onUnauthorized(() => {
    token.value = ''
    userInfo.value = null
    redirectAfterUnauthorized()
  })

  /** 拉取并缓存用户信息，供各页面复用，避免重复请求 */
  async function loadUserInfo() {
    if (!token.value) {
      return
    }
    userInfo.value = await fetchUserInfo(token.value)
  }

  /**
   * 微信静默登录：wx.login 换 code，再换本服务自有登录态。
   * 非微信端（H5 预览）没有可用凭证，直接返回 false，由调用方提示。
   */
  async function loginSilently(): Promise<boolean> {
    try {
      const code = await new Promise<string>((resolve, reject) => {
        uni.login({
          provider: 'weixin',
          success: res => (res.code ? resolve(res.code) : reject(new Error('未取得 code'))),
          fail: reject,
        })
      })
      const result = await loginByWechat(code)
      setLogin(result.token)
      userInfo.value = result.userInfo
      return true
    }
    catch (error) {
      // 新微信首次创建账户须转人工可见协议页；网络/配置失败不误判为同意流程。
      consentRequired.value = error instanceof ApiError && error.code === 422
      return false
    }
  }

  /**
   * 静默登录：首启不打断任何操作，只在真正需要身份时（上传 / 看记录）落地。
   */
  async function ensureLogin(): Promise<boolean> {
    if (token.value) {
      return true
    }
    const signed = await loginSilently()
    if (!signed && consentRequired.value) {
      // #ifdef MP-WEIXIN
      uni.navigateTo({ url: '/pages/login/index' })
      // #endif
    }
    return signed
  }

  return { token, userInfo, consentRequired, setLogin, clear, loadUserInfo, ensureLogin }
})
