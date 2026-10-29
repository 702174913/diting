/**
 * 认证接口：听悟后端 /user/login（不存在则自动注册，已扩展支持微信 code）与 /user/get。
 * 账号密码与微信 code 均走 form-urlencoded，返回体是 JWT 字符串。
 */
import { ApiError, request } from './request'
import { getUser } from './tingwu'
import type { LoginResult, UserInfo } from '@/types/api'
import { decodeJwtUserId } from '@/utils/jwt'
import { clearParentUserId, getParentUserId } from '@/utils/referral'

function toUserInfo(id: number, username: string, hasPassword: boolean): UserInfo {
  return { id, username, nickname: username, hasPassword, hasWechat: false }
}

/** 分享进入的归因只在本次登录成功时提交一次，失败时保留以便重试 */
function loginData(data: Record<string, unknown>): Record<string, unknown> {
  const parentUserId = getParentUserId()
  return parentUserId ? { ...data, parent_user_id: parentUserId } : data
}

export async function loginByPassword(username: string, password: string): Promise<LoginResult> {
  const token = await request<string>({
    url: '/user/login',
    method: 'POST',
    form: true,
    token: '',
    data: loginData({ username, password }),
  })
  clearParentUserId()
  const id = decodeJwtUserId(token) ?? 0
  return { token, userInfo: toUserInfo(id, username, true), isNewUser: false }
}

/** 后端 /user/login 会在用户名不存在时自动注册，注册与登录是同一入口 */
export function registerAccount(
  username: string,
  password: string,
  _agreed: boolean,
  _seenVersions: Record<string, number>,
): Promise<LoginResult> {
  return loginByPassword(username, password)
}

/**
 * 微信 code 登录：与账号密码共用 `/user/login`，后端按 code 换 openid 后下发 JWT。
 * agreed/seenVersions 保留参数签名，供调用方沿用登录页的协议快照。
 */
export async function loginByWechat(
  code?: string,
  _agreed?: boolean,
  _seenVersions?: Record<string, number>,
): Promise<LoginResult> {
  if (!code) {
    throw new ApiError(400, '未取得微信授权码')
  }
  const token = await request<string>({
    url: '/user/login',
    method: 'POST',
    form: true,
    token: '',
    data: loginData({ code }),
  })
  clearParentUserId()
  const id = decodeJwtUserId(token) ?? 0
  return {
    token,
    userInfo: { id, username: '', nickname: '', hasPassword: false, hasWechat: true },
    isNewUser: false,
  }
}

export function bindWechat(_code?: string, _token?: string): Promise<UserInfo> {
  return Promise.reject(new ApiError(501, '当前服务暂不支持绑定微信'))
}

export async function fetchUserInfo(token: string): Promise<UserInfo> {
  const raw = await getUser(token)
  const id = raw.user_id ?? decodeJwtUserId(token) ?? 0
  return toUserInfo(id, raw.username ?? '', true)
}
