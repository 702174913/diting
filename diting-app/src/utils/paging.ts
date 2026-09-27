/**
 * 分页列表合并：按 id 去重、保持服务端顺序。
 * 重复加载同一页或弱网重试时，不能出现重复项。
 */
export interface Paged<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

export function mergePage<T extends { id: number }>(current: T[], incoming: Paged<T>): T[] {
  const seen = new Set(current.map(item => item.id))
  const merged = [...current]
  for (const item of incoming.items) {
    if (!seen.has(item.id)) {
      seen.add(item.id)
      merged.push(item)
    }
  }
  return merged
}

export function hasMore<T>(loaded: T[], incoming: Paged<T>): boolean {
  return loaded.length < incoming.total
}

export function nextPage(currentPage: number): number {
  return currentPage + 1
}
