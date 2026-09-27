import { request } from './request'

export interface AgreementItem {
  key: string
  title: string
  version: number
}

export interface AgreementDetail extends AgreementItem {
  contentHtml: string
}

/** 协议列表（公开接口，供注册页展示用） */
export function fetchAgreements() {
  return request<AgreementItem[]>({ url: '/api/agreements' })
}

/** 注册时看到的版本号快照；提交时原样带回，后端校验版本一致 */
export function fetchAgreementVersions() {
  return request<Record<string, number>>({ url: '/api/agreements/versions' })
}

/** 协议正文（公开接口） */
export function fetchAgreementDetail(key: string) {
  return request<AgreementDetail>({ url: `/api/agreements/${key}` })
}
