import { request } from './request'
import type { LoginResult, UserInfo } from '@/types/api'

export function loginByWechat(code: string, agreed = false, seenVersions?: Record<string, number>) {
  return request<LoginResult>({
    url: '/api/auth/wechat',
    method: 'POST',
    data: { code, agreed, seenVersions },
  })
}

export function loginByPassword(username: string, password: string) {
  return request<LoginResult>({
    url: '/api/auth/login',
    method: 'POST',
    data: { username, password },
  })
}

export function registerAccount(username: string, password: string, agreed: boolean, seenVersions: Record<string, number>) {
  return request<LoginResult>({
    url: '/api/auth/register',
    method: 'POST',
    data: { username, password, agreed, seenVersions },
  })
}

export function bindWechat(code: string, token: string) {
  return request<UserInfo>({
    url: '/api/auth/bind-wechat',
    method: 'POST',
    data: { code },
    token,
  })
}

export function fetchUserInfo(token: string) {
  return request<UserInfo>({
    url: '/api/auth/userinfo',
    token,
  })
}
