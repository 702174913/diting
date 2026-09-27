import { request } from './request'
import type { AssetItem, DeletionStatus, Paged, ReportDetail, TaskItem, TaskStatus } from '@/types/api'

/** 分页列表：page 从 1 起；已删除的记录不会再出现 */
export function fetchTaskList(token: string, page = 1, pageSize = 20, status?: TaskStatus) {
  const query = [`page=${page}`, `pageSize=${pageSize}`]
  if (status) {
    query.push(`status=${status}`)
  }
  return request<Paged<TaskItem>>({
    url: `/api/task/list?${query.join('&')}`,
    token,
  })
}

/** 清理作业状态：删除后前端据此提示「清理中 / 已完成 / 失败可重试」 */
export function fetchDeletion(deletionJobId: number, token: string) {
  return request<DeletionStatus>({
    url: `/api/deletions/${deletionJobId}`,
    token,
  })
}

/** 全部抹除：二次确认后调用；返回清理作业号 */
export function eraseAll(token: string) {
  return request<DeletionStatus>({
    url: '/api/privacy/erase',
    method: 'DELETE',
    token,
  })
}

/** 记录下的资产（音频/图片）：服务端给短期地址，已过期或已清理的 url 为空 */
export function fetchAssets(taskId: number, token: string) {
  return request<AssetItem[]>({
    url: `/api/task/${taskId}/assets`,
    token,
  })
}

export function fetchTaskStatus(id: number, token: string) {
  return request<TaskItem>({
    url: `/api/task/${id}/status`,
    token,
  })
}

export function deleteRecording(id: number, token: string) {
  return request<{ deletionJobId: number, status: string }>({
    url: `/api/task/${id}`,
    method: 'DELETE',
    token,
  })
}

export function fetchReport(id: number, token: string, version?: number) {
  return request<ReportDetail>({
    url: version ? `/api/report/${id}?version=${version}` : `/api/report/${id}`,
    token,
  })
}

/**
 * 导出记录（异步作业）。
 * 创建作业只登记，完成需要轮询 `/api/exports/{exportId}`；
 * 完成前不返回下载地址，避免前端拿到空链接冒充成功。
 */
export function createExport(id: number, reportVersionId: number, token: string, withAudio = false) {
  return request<{ exportId: number, status: string, fileType: string }>({
    url: `/api/report/${id}/exports`,
    method: 'POST',
    data: { reportVersionId, withAudio },
    token,
  })
}

export interface ExportStatus {
  exportId: number
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  withAudio: boolean
  fileType: 'pdf' | 'zip'
  errorCode: string | null
  expiresAt: string | null
}

export function fetchExport(exportId: number, token: string) {
  return request<ExportStatus>({
    url: `/api/exports/${exportId}`,
    token,
  })
}

/**
 * 下载：作业完成后显式调用一次签发短时下载地址（状态查询不再返回地址）。
 * 未完成/过期由服务端明确报错；每次调用记一条签发审计。
 */
export function fetchExportDownload(exportId: number, token: string) {
  return request<{ exportId: number, fileType: string, downloadUrl: string, expiresAt: string | null }>({
    url: `/api/exports/${exportId}/download`,
    token,
  })
}

/** 轮询导出作业直到完成或失败；只查状态，不触发副作用 */
export async function waitExport(exportId: number, token: string, timeoutMs = 60000) {
  const startedAt = Date.now()
  let latest: ExportStatus | null = null
  while (Date.now() - startedAt < timeoutMs) {
    latest = await fetchExport(exportId, token)
    if (latest.status === 'COMPLETED' || latest.status === 'FAILED') {
      return latest
    }
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  return latest
}

export type { TaskStatus }
