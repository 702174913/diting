/**
 * 认证接口：听悟后端只有 /user/login（不存在则自动注册）与 /user/get。
 * 微信登录/绑定、独立注册、协议版本快照在本后端不存在，这里明确降级并给出提示，
 * 不伪造成功。账号密码走 form-urlencoded，返回体是 JWT 字符串。
 */
import { ApiError, request } from './request'
import { getUser } from './tingwu'
import type { LoginResult, UserInfo } from '@/types/api'
import { decodeJwtUserId } from '@/utils/jwt'

function toUserInfo(id: number, username: string, hasPassword: boolean): UserInfo {
  return { id, username, nickname: username, hasPassword, hasWechat: false }
}

export async function loginByPassword(username: string, password: string): Promise<LoginResult> {
  const token = await request<string>({
    url: '/user/login',
    method: 'POST',
    form: true,
    data: { username, password },
  })
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

/** 本后端不支持：保留原参数签名供 UI 调用，调用方据此提示改用账号密码 */
export function loginByWechat(
  _code?: string,
  _agreed?: boolean,
  _seenVersions?: Record<string, number>,
): Promise<LoginResult> {
  return Promise.reject(new ApiError(501, '当前服务暂不支持微信登录，请使用账号密码登录'))
}

export function bindWechat(_code?: string, _token?: string): Promise<UserInfo> {
  return Promise.reject(new ApiError(501, '当前服务暂不支持绑定微信'))
}

export async function fetchUserInfo(token: string): Promise<UserInfo> {
  const raw = await getUser(token)
  const id = raw.user_id ?? decodeJwtUserId(token) ?? 0
  return toUserInfo(id, raw.username ?? '', true)
}
