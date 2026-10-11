/**
 * 听悟后端的低层客户端与类型：只做「请求 + 缓存 + 原始数据」，
 * 结构到本项目 UI 的映射放在 record.ts / upload.ts，避免 UI 直接依赖后端字段命名（snake_case）。
 */
import { request } from './request'

export interface VoiceTranslationRaw {
  voice_translation_id: number
  user_id: number
  task_id: string
  task_status: string
  result: string | Record<string, string> | null
  model_name: string | null
  original_file_name: string | null
  file_url: string | null
  total_duration: number | null
  createtime: string | null
}

export interface UserRaw {
  user_id: number
  username: string
  password?: string
  createtime?: string
}

/** 列表接口不返回总量，按固定 20 条分页 */
export const PAGE_SIZE = 20

const cache = new Map<number, VoiceTranslationRaw>()

export function cacheTask(task: VoiceTranslationRaw) {
  cache.set(task.voice_translation_id, task)
}

export function getCachedTask(id: number): VoiceTranslationRaw | undefined {
  return cache.get(id)
}

export function listVoiceTranslations(page: number, token: string) {
  return request<VoiceTranslationRaw[]>({
    url: `/voice_translation/list?page=${page}`,
    token,
  }).then((items) => {
    const list = items ?? []
    list.forEach(cacheTask)
    return list
  })
}

/**
 * 按 voice_translation_id 找到记录：先用列表缓存，未命中再逐页拉取（最多 20 页兜底）。
 * 记录页可能被直接打开（分享/回跳），不能假设列表已加载过。
 */
export async function findVoiceTranslation(id: number, token: string): Promise<VoiceTranslationRaw | null> {
  const cached = getCachedTask(id)
  if (cached) {
    return cached
  }
  for (let page = 0; page < 20; page++) {
    const items = await listVoiceTranslations(page, token)
    const hit = getCachedTask(id)
    if (hit) {
      return hit
    }
    if (items.length < PAGE_SIZE) {
      break
    }
  }
  return null
}

/** 创建转写任务；返回阿里云任务 ID（字符串） */
export function addVoiceTranslation(
  params: { fileName: string, originalFileName: string, fileUrl: string, totalDuration: number },
  token: string,
) {
  return request<string>({
    url: '/voice_translation/add',
    method: 'POST',
    form: true,
    token,
    data: {
      file_name: params.fileName,
      original_file_name: params.originalFileName,
      file_url: params.fileUrl,
      total_duration: params.totalDuration,
    },
  })
}

/** 查询转写结果：进行中返回字符串「任务进行中」，完成返回 { speakerId: content } */
export function getVoiceTranslation(taskId: string, token: string) {
  return request<string | Record<string, string>>({
    url: `/voice_translation/get?taskId=${encodeURIComponent(taskId)}`,
    token,
  })
}

export function getUser(token: string) {
  return request<UserRaw>({
    url: '/user/get',
    token,
  })
}

/** 解析 result 字段：字符串、对象、「任务进行中」都兼容；拿不到内容返回 null */
export function parseTranscript(raw: VoiceTranslationRaw['result']): Record<string, string> | null {
  if (!raw) {
    return null
  }
  if (typeof raw === 'object') {
    return raw
  }
  const text = raw.trim()
  if (!text || text === '任务进行中' || !text.startsWith('{')) {
    return null
  }
  try {
    const parsed = JSON.parse(text) as Record<string, string>
    return typeof parsed === 'object' && parsed !== null ? parsed : null
  }
  catch {
    return null
  }
}
