/**
 * 协议接口：完全以听悟后端 `GET /configure/list` 为准。
 * 该接口返回系统配置键值对，其中「名称命中协议关键词」的条目就是一份协议：
 *   key   = 协议名称（如 软件许可及服务协议）
 *   value = 版本号（字符串数字）
 * 接口不提供正文，正文用本地按名称维护的文案；接口新增且本地没有的条目用通用正文兜底。
 * 接口不可用时回落到本地兜底清单，保证注册页/协议页仍能展示。
 */
import { fetchConfigure } from './configure'

export interface AgreementItem {
  key: string
  title: string
  version: number
}

export interface AgreementDetail extends AgreementItem {
  contentHtml: string
}

/** 配置项名称命中这些关键词即视为一份协议；业务参数（份数/阈值/时长）不含这些词 */
const AGREEMENT_KEYWORD = /协议|授权书|授权协议|指引|隐私|许可/

/** 接口不提供正文：按协议名称维护正文；key 与 title 一致 */
const CONTENT_BY_TITLE: Record<string, string> = {
  软件许可及服务协议: '<p>谛听用于催收通话录音的转写与质检。使用本服务即表示你确认对上传内容拥有合法处理权限。</p><p>本服务不构成法律意见，也不作为处理结果承诺。</p>',
  隐私保护指引: '<p>我们只在为你完成本次转写与质检所需的范围内处理你的录音与文字内容。</p><p>不会用于训练模型，不会提供给任何第三方。</p>',
  个人信息收集与授权书: '<p>你授权我们在服务期间处理本次上传的音频、图片及其转写结果，用于生成质检记录。</p><p>你可以随时发起删除。</p>',
  通话录音与语音识别授权协议: '<p>你确认已取得通话各方的必要授权，方可上传该段录音。</p><p>请勿上传你无权处理的通话录音。</p>',
}

/** 接口不可用时的兜底协议清单 */
const FALLBACK: AgreementItem[] = Object.keys(CONTENT_BY_TITLE).map(title => ({ key: title, title, version: 1 }))

function toVersion(raw: string): number {
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? Math.trunc(value) : 1
}

function toItems(items: { key?: unknown, value?: unknown }[]): AgreementItem[] {
  return items
    .filter(item => typeof item?.key === 'string' && AGREEMENT_KEYWORD.test(item.key as string))
    .map(item => ({ key: item.key as string, title: item.key as string, version: toVersion(String(item.value ?? '')) }))
}

async function loadAgreements(): Promise<AgreementItem[]> {
  try {
    const items = await fetchConfigure()
    const list = toItems(items)
    return list.length ? list : FALLBACK
  }
  catch {
    return FALLBACK
  }
}

export function fetchAgreements(): Promise<AgreementItem[]> {
  return loadAgreements()
}

export async function fetchAgreementVersions(): Promise<Record<string, number>> {
  const list = await loadAgreements()
  return list.reduce<Record<string, number>>((acc, item) => {
    acc[item.key] = item.version
    return acc
  }, {})
}

export async function fetchAgreementDetail(key: string): Promise<AgreementDetail> {
  const list = await loadAgreements()
  const hit = list.find(item => item.key === key)
  if (!hit) {
    throw new Error('协议不存在')
  }
  return {
    ...hit,
    contentHtml: CONTENT_BY_TITLE[hit.title] ?? `<p>《${hit.title}》</p><p>当前版本 V${hit.version}。</p>`,
  }
}
