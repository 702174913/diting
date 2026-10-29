/**
 * 协议接口：完全以听悟后端 `GET /configure/list` 为准。
 * 该接口返回系统配置键值对，其中「名称命中协议关键词」的条目就是一份协议：
 *   key   = 协议名称（如 软件许可及服务协议）
 *   value = 协议正文 HTML（后台富文本维护，原样渲染）
 * 版本号不在独立字段里，从正文的「版本编号：V1.0」解析；解析不到按 1 处理。
 * 接口不可用时回落到本地兜底清单，保证注册页/协议页仍能展示。
 */
import { fetchPublicConfigure } from './configure'

export interface AgreementItem {
  key: string
  title: string
  version: number
}

export interface AgreementDetail extends AgreementItem {
  contentHtml: string
}

/** 配置项名称命中这些关键词即视为一份协议；业务参数（份数/阈值/时长）不含这些词 */
const AGREEMENT_KEYWORD = /协议|授权书|指引|隐私|许可|共享|清单/

/** 接口不可用时的兜底正文；接口可用时一律使用接口下发的 value */
const FALLBACK_CONTENT: Record<string, string> = {
  软件许可及服务协议: '<p>谛听用于催收通话录音的转写与质检。使用本服务即表示你确认对上传内容拥有合法处理权限。</p><p>本服务不构成法律意见，也不作为处理结果承诺。</p>',
  隐私保护指引: '<p>我们只在为你完成本次转写与质检所需的范围内处理你的录音与文字内容。</p><p>不会用于训练模型，不会提供给任何第三方。</p>',
  个人信息收集与授权书: '<p>你授权我们在服务期间处理本次上传的音频、图片及其转写结果，用于生成质检记录。</p><p>你可以随时发起删除。</p>',
  通话录音与语音识别授权协议: '<p>你确认已取得通话各方的必要授权，方可上传该段录音。</p><p>请勿上传你无权处理的通话录音。</p>',
}

const FALLBACK: AgreementItem[] = Object.keys(FALLBACK_CONTENT)
  .map(title => ({ key: title, title, version: 1 }))

interface ConfigureItem {
  key?: unknown
  value?: unknown
}

interface AgreementSource {
  items: AgreementItem[]
  contentByKey: Record<string, string>
}

function isAgreementItem(item: ConfigureItem): item is { key: string, value?: unknown } {
  return typeof item?.key === 'string' && AGREEMENT_KEYWORD.test(item.key)
}

function toVersion(html: string): number {
  const match = html.match(/(?:版本编号|版本号|版本)\s*[：:]\s*V?(\d+(?:\.\d+)?)/i)
    ?? html.match(/V(\d+(?:\.\d+)?)/i)
  const value = match ? Number(match[1]) : Number.NaN
  return Number.isFinite(value) && value > 0 ? Math.trunc(value) : 1
}

function toSource(items: ConfigureItem[]): AgreementSource | null {
  const list = items.filter(isAgreementItem)
  if (list.length === 0) {
    return null
  }
  const contentByKey: Record<string, string> = {}
  for (const item of list) {
    contentByKey[item.key] = typeof item.value === 'string' ? item.value : ''
  }
  return {
    contentByKey,
    items: list.map(item => ({
      key: item.key,
      title: item.key,
      version: toVersion(contentByKey[item.key] ?? ''),
    })),
  }
}

async function loadSource(): Promise<AgreementSource> {
  try {
    const source = toSource(await fetchPublicConfigure())
    if (source) {
      return source
    }
  }
  catch {
    // 接口不可用时走兜底，保证注册页仍可展示协议入口
  }
  return { items: FALLBACK, contentByKey: FALLBACK_CONTENT }
}

export async function fetchAgreements(): Promise<AgreementItem[]> {
  return (await loadSource()).items
}

export async function fetchAgreementVersions(): Promise<Record<string, number>> {
  const { items } = await loadSource()
  return items.reduce<Record<string, number>>((acc, item) => {
    acc[item.key] = item.version
    return acc
  }, {})
}

export async function fetchAgreementDetail(key: string): Promise<AgreementDetail> {
  const source = await loadSource()
  const hit = source.items.find(item => item.key === key)
  if (!hit) {
    throw new Error('协议不存在')
  }
  return {
    ...hit,
    contentHtml: source.contentByKey[hit.key]
      || FALLBACK_CONTENT[hit.title]
      || `<p>《${hit.title}》</p><p>当前版本 V${hit.version}。</p>`,
  }
}
