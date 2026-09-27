export type ReturnTab = 'home' | 'upload' | 'history' | 'about'

/** H5 未登录时直接进入账号页；小程序继续使用原有微信登录流程。 */
export function requireH5Login(token: string, returnTab: ReturnTab, resumePath?: string): boolean {
  // #ifdef H5
  if (!token) {
    if (getCurrentPages().at(-1)?.route !== 'pages/login/index') {
      const resume = resumePath ? `&resume=${resumePath}` : ''
      uni.navigateTo({ url: `/pages/login/index?returnTab=${returnTab}${resume}` })
    }
    return false
  }
  // #endif
  return true
}

/** 已有登录态因接口 401 失效时，回到正在看的页面对应的登录入口。 */
export function redirectAfterUnauthorized() {
  // #ifdef H5
  const current = getCurrentPages().at(-1)
  const route = current?.route
  if (!route || route === 'pages/login/index') {
    return
  }
  const id = Number((current as { options?: { id?: string } }).options?.id ?? 0)
  let resume: string | undefined
  if (id > 0 && route === 'pages/report/detail') {
    resume = `/pages/report/detail?id=${id}`
  }
  else if (id > 0 && route === 'pages/progress/index') {
    // 进度页已并入记录页：直接回跳合并后的那一页，少一跳
    resume = `/pages/report/detail?id=${id}`
  }
  else if (route === 'pages/privacy/index') {
    resume = '/pages/privacy/index'
  }

  let tab: ReturnTab = 'home'
  if (route === 'pages/upload/index') {
    tab = 'upload'
  }
  else if (route === 'pages/about/index' || route === 'pages/privacy/index') {
    tab = 'about'
  }
  else if (route === 'pages/history/index' || route === 'pages/report/detail' || route === 'pages/progress/index') {
    tab = 'history'
  }
  requireH5Login('', tab, resume)
  // #endif
}
