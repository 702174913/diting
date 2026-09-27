import { buildQuery, request } from './request'

/**
 * 真实统计（开发文档 §10）：每个指标都带来源表、分母与来源状态（REAL/DEMO/PENDING）。
 * 统计面已收敛为总览看板一个：后端只保留 `section=overview`，其余分组（growth/finance/ops/
 * violations/quality/compliance）连同对应页面已整体下线，旧路径回 404。
 */
export interface Metric {
  key: string
  label: string
  value: number | null
  unit: string
  sourceTable: string | null
  timeField: string | null
  denominator: number | null
  sampleSize: number | null
  source: 'REAL' | 'DEMO' | 'PENDING'
  note: string | null
}

/** 序列上的一个点：label 用于展示，value 允许为 0（补零后的真实 0，不是缺数据） */
export interface Point {
  key: string
  label: string
  value: number | null
}

export interface Series {
  key: string
  label: string
  unit: string
  points: Point[]
  note: string | null
}

/** 分组明细的一行：value 是主数值，note 放口径补充（涉及通话数、占比等） */
export interface Row {
  key: string
  label: string
  value: number | null
  note: string | null
}

export interface Breakdown {
  key: string
  label: string
  unit: string
  rows: Row[]
  note: string | null
}

export interface StatsSection {
  section: string
  period: string
  from: string
  to: string
  metrics: Metric[]
  series: Series[]
  breakdown: Breakdown[]
}

/** 质检运营的实时队列接口（/api/admin/ops/queue）已随后台下线，此处不再保留客户端封装 */

export function fetchStats(token: string, section = 'overview', params: { period?: string, from?: string, to?: string } = {}) {
  const query = buildQuery(params)
  return request<StatsSection>({
    url: `/api/admin/stats/${section}${query ? `?${query}` : ''}`,
    token,
  })
}
