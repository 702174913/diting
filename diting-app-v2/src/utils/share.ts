import { useUserStore } from '@/stores/user'
import { sharePath } from '@/utils/referral'
import { decodeJwtUserId } from '@/utils/jwt'

/**
 * 生成分享内容：路径带上当前登录用户的 user_id，
 * 接收方进入后由 `captureParentUserId` 接住，登录时作为 parent_user_id 上报。
 *
 * 注意：`onShareAppMessage` 必须在页面文件里直接调用（小程序编译器按源码静态识别
 * 页面运行时钩子），所以这里只返回内容工厂，由页面注册钩子。
 */
export function useShareContent(path: string, title: string) {
  const userStore = useUserStore()
  return () => {
    const id = userStore.userInfo?.id || decodeJwtUserId(userStore.token)
    const userId = typeof id === 'number' && Number.isSafeInteger(id) && id > 0 ? id : null
    return { title, path: sharePath(path, userId) }
  }
}
