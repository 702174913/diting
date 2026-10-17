/** client 模块原始接口。详情始终按记录 ID 请求，不复用可能过期或跨账号的列表缓存。 */
import { request } from './request'

export interface DialogueRaw {
  speaker: string
  content: string
  model_ids: number[]
  hit_texts: string[]
}

export interface AudioTranslationRaw {
  audio_translation_id: number
  user_id: number
  status: number
  task_id: string | null
  task_status: string | null
  task_result: string | null
  translation_result: string | null
  handle_result: string | null
  match_result: string | null
  picture_handle_results: string | null
  picture_match_results: string | null
  picture_names: string | null
  pictures: string | null
  audio_name: string | null
  audio: string | null
  audio_duration: number | null
  createtime: string | null
}

export interface UserRaw {
  user_id: number
  username: string
  password?: string
  createtime?: string
}

export const PAGE_SIZE = 20

export async function listAudioTranslations(page: number, token: string): Promise<AudioTranslationRaw[]> {
  const items = await request<AudioTranslationRaw[]>({
    url: '/audio_translation/list',
    data: { page },
    token,
  })
  return Array.isArray(items) ? items : []
}

export function getAudioTranslation(id: number, token: string): Promise<AudioTranslationRaw | null> {
  return request({
    url: '/audio_translation/get',
    data: { audio_translation_id: id },
    token,
  })
}

export function addAudioTranslation(
  params: { picture_names: string, pictures: string, audio_name: string, audio: string, audio_duration: number },
  token: string,
): Promise<null> {
  return request({ url: '/audio_translation/add', method: 'POST', form: true, data: params, token })
}

export function getUser(token: string) {
  return request<UserRaw>({ url: '/user/get', token })
}

/** 结果字段是 JSON 字符串；空值、非法 JSON 和非法元素不影响其余结果展示。 */
export function parseDialogues(raw: string | null | undefined): DialogueRaw[] {
  if (!raw) {
    return []
  }
  try {
    const value: unknown = JSON.parse(raw)
    if (!Array.isArray(value)) {
      return []
    }
    return value.filter(item => item && typeof item.content === 'string').map(item => ({
      speaker: String(item.speaker ?? ''),
      content: item.content,
      model_ids: Array.isArray(item.model_ids) ? item.model_ids.filter((id: unknown) => typeof id === 'number' && Number.isFinite(id)) : [],
      hit_texts: Array.isArray(item.hit_texts) ? item.hit_texts.filter((text: unknown) => typeof text === 'string' && text.length > 0) : [],
    }))
  }
  catch {
    return []
  }
}
