/** client 模块原始接口。详情始终按记录 ID 请求，不复用可能过期或跨账号的列表缓存。 */
import { request } from './request'
import type { HitRule, LegalProvision } from '@/types/api'

export interface DialogueRaw {
  speaker: string
  content: string
  model_ids: number[]
  models: HitRule[]
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
  /** 新接口字段：与上面的 task_ 前缀字段一一对应，进入业务层前统一归一化 */
  audio_task_id?: string | null
  audio_task_status?: string | null
  audio_task_result?: string | null
  audio_translation_result?: string | null
  audio_handle_result?: string | null
  audio_match_result?: string | null
  questions?: string | null
}

/** 接口把 task_* 等字段改名为 audio_*；这里统一回落，列表与详情共用同一套业务字段 */
function normalizeTask(raw: AudioTranslationRaw): AudioTranslationRaw {
  return {
    ...raw,
    task_id: raw.audio_task_id ?? raw.task_id ?? null,
    task_status: raw.audio_task_status ?? raw.task_status ?? null,
    task_result: raw.audio_task_result ?? raw.task_result ?? null,
    translation_result: raw.audio_translation_result ?? raw.translation_result ?? null,
    handle_result: raw.audio_handle_result ?? raw.handle_result ?? null,
    match_result: raw.audio_match_result ?? raw.match_result ?? null,
  }
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
  return Array.isArray(items) ? items.map(normalizeTask) : []
}

export async function getAudioTranslation(id: number, token: string): Promise<AudioTranslationRaw | null> {
  const task = await request<AudioTranslationRaw | null>({
    url: '/audio_translation/get',
    data: { audio_translation_id: id },
    token,
  })
  return task ? normalizeTask(task) : null
}

/** add 现在会返回新记录 ID；兼容数字、数字字符串与对象三种返回形态。 */
function extractTranslationId(data: unknown): number | null {
  if (typeof data === 'number' && Number.isSafeInteger(data)) {
    return data
  }
  if (typeof data === 'string' && /^\d+$/.test(data.trim())) {
    return Number(data)
  }
  if (data && typeof data === 'object') {
    const raw = (data as { audio_translation_id?: unknown, id?: unknown }).audio_translation_id
      ?? (data as { id?: unknown }).id
    if (typeof raw === 'number' && Number.isSafeInteger(raw)) {
      return raw
    }
    if (typeof raw === 'string' && /^\d+$/.test(raw.trim())) {
      return Number(raw)
    }
  }
  return null
}

export async function addAudioTranslation(
  params: { picture_names: string, pictures: string, audio_name: string, audio: string, audio_duration: number, questions: string },
  token: string,
): Promise<number | null> {
  const data = await request<unknown>({ url: '/audio_translation/add', method: 'POST', form: true, data: params, token })
  return extractTranslationId(data)
}

export function getUser(token: string) {
  return request<UserRaw>({ url: '/user/get', token })
}

function parseProvisions(raw: unknown): LegalProvision[] {
  if (!Array.isArray(raw)) {
    return []
  }
  return raw.map((item) => {
    const provision = item as {
      legal_provision_id?: unknown
      name?: unknown
      which?: unknown
      content?: unknown
    }
    return {
      id: Number(provision?.legal_provision_id) || 0,
      name: typeof provision?.name === 'string' ? provision.name : '',
      which: typeof provision?.which === 'string' ? provision.which : '',
      content: typeof provision?.content === 'string' ? provision.content : '',
    }
  }).filter(item => item.name || item.which || item.content)
}

/** 同一条规则在 model_ids/models 里会重复出现，按 model_id 去重 */
function parseRules(raw: unknown): HitRule[] {
  if (!Array.isArray(raw)) {
    return []
  }
  const seen = new Set<number>()
  const rules: HitRule[] = []
  for (const item of raw) {
    const model = item as {
      model_id?: unknown
      serial_number?: unknown
      violation_type?: unknown
      violation_name?: unknown
      legalProvisions?: unknown
    }
    const modelId = Number(model?.model_id)
    if (!Number.isFinite(modelId) || seen.has(modelId)) {
      continue
    }
    seen.add(modelId)
    rules.push({
      modelId,
      serialNumber: typeof model?.serial_number === 'string' ? model.serial_number : '',
      violationType: typeof model?.violation_type === 'string' ? model.violation_type : '',
      violationName: typeof model?.violation_name === 'string' ? model.violation_name : '',
      provisions: parseProvisions(model?.legalProvisions),
    })
  }
  return rules
}

/**
 * 转写原文兜底：handle_result 尚未生成时，从 translation_result 的
 * `Transcription.Paragraphs[].Words[].Text` 按段落拼接出对话。
 * 只取文本与 SpeakerId，规则命中由 match_result 另行合并。
 */
export function parseTranscriptionDialogues(raw: string | null | undefined): DialogueRaw[] {
  if (!raw) {
    return []
  }
  try {
    const value = JSON.parse(raw) as {
      Transcription?: unknown
      transcription?: unknown
    }
    const transcription = (value?.Transcription ?? value?.transcription ?? value) as {
      Paragraphs?: unknown
      paragraphs?: unknown
    }
    const paragraphs = transcription?.Paragraphs ?? transcription?.paragraphs
    if (!Array.isArray(paragraphs)) {
      return []
    }
    return paragraphs.map((paragraph) => {
      const item = paragraph as {
        SpeakerId?: unknown
        speakerId?: unknown
        Speaker?: unknown
        speaker?: unknown
        Words?: unknown
        words?: unknown
      }
      const words = Array.isArray(item.Words) ? item.Words : Array.isArray(item.words) ? item.words : []
      const content = words.map((word) => {
        const text = (word as { Text?: unknown, text?: unknown })?.Text
          ?? (word as { text?: unknown })?.text
        return typeof text === 'string' ? text : ''
      }).join('').trim()
      const speaker = item.SpeakerId ?? item.speakerId ?? item.Speaker ?? item.speaker
      return {
        speaker: String(speaker ?? ''),
        content,
        model_ids: [],
        models: [],
        hit_texts: [],
      }
    }).filter(dialogue => dialogue.content.length > 0)
  }
  catch {
    return []
  }
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
      models: parseRules(item.models),
      hit_texts: Array.isArray(item.hit_texts) ? item.hit_texts.filter((text: unknown) => typeof text === 'string' && text.length > 0) : [],
    }))
  }
  catch {
    return []
  }
}
