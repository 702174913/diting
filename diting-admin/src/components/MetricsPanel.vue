<script setup lang="ts">
/**
 * 真实指标面板（开发文档 §10）。
 * 统计面已收敛为总览看板一个，本组件现在只服务 `section=overview`（prop 默认值）。
 * 每张卡片都显示来源状态：真实 / 演示 / 待采集 —— 不整页混用一个「真实」标签，
 * 也不把没有来源的数字包装成结论。
 *
 * 卡片形态对齐定稿：24px 内边距、弱化标签、金色大号数值（单位小一号）、
 * 较上一周期的涨跌、右上角微光；四列等宽，多于四张时换行并保持同宽。
 *
 * 涨跌怎么来的：拉一次「与当前窗口等长的前一个窗口」，按指标 key 做差 —— 零后端改动、数据真实。
 * 上一周期没有值或为 0 时不显示涨跌（否则会得到 ∞ 或一个无法解释的百分比）。
 * 颜色按指标极性，而不是无脑「涨绿跌红」：成本/耗时/失败类指标上涨染绿会直接误导阅读者。
 *
 * 序列用 shadcn-vue 的 Chart（Unovis）：单序列柱状 + 稀疏横轴 + 悬停提示；
 * 配色走项目已有的 --chart-1 金色令牌，与定稿一致。Panel 仍是原来的容器。
 */
import { ChartColumnIcon } from '@lucide/vue'
import { VisAxis, VisGroupedBar, VisXYContainer } from '@unovis/vue'
import { computed, onUnmounted, ref, watch } from 'vue'
import { toast } from 'vue-sonner'
import type { Breakdown, Metric, Series } from '@/api/stats'
import type { ChartConfig } from '@/components/ui/chart'
import { fetchStats } from '@/api/stats'
import { ApiError } from '@/api/request'
import EmptyState from '@/components/EmptyState.vue'
import Panel from '@/components/Panel.vue'
import { Card, CardContent } from '@/components/ui/card'
import {
  ChartContainer,
  ChartCrosshair,
  ChartTooltip,
  ChartTooltipContent,
  componentToString,
} from '@/components/ui/chart'
import { Skeleton } from '@/components/ui/skeleton'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { useUserStore } from '@/stores/user'
import { useViewStore } from '@/stores/view'

const props = withDefaults(defineProps<{ section?: string, period?: string }>(), {
  section: 'overview',
})

const view = useViewStore()
const effectivePeriod = computed(() => props.period ?? view.apiPeriod)
let loadGeneration = 0
const userStore = useUserStore()
const metrics = ref<Metric[]>([])
const series = ref<Series[]>([])
const breakdown = ref<Breakdown[]>([])
const error = ref('')
const loading = ref(false)

/**
 * 涨是坏消息的指标：失败、耗时、积压类。
 *  统计面收敛为总览看板后，只保留总览真的会出现的指标键；其余页面的键随页面一起下了线。
 */
const LOWER_IS_BETTER = new Set([
  'avgDuration',
  'failedTasks',
  'failureRate',
  'invalidTasks',
  'queueNow',
])

/**
 * 涨跌不代表好坏的指标：版本号、计数类、审计事件、风险计数等。
 *  当前总览的 12 个指标都有明确好坏方向，所以这里为空集；将来接入中性指标时在此登记，
 *  判断逻辑（changeClass/changeTitle）保持不变。
 */
const DIRECTION_NEUTRAL = new Set<string>([])

interface Change {
  percent: number
  direction: 'up' | 'down' | 'flat'
}

/** key → 涨跌。只在两个窗口都有可比值时才存在 */
const changes = ref<Record<string, Change>>({})

const hasData = computed(() => metrics.value.length > 0 || series.value.length > 0 || breakdown.value.length > 0)

/** 数值展示：整数带千分位（定稿里 ¥486,300 这种写法） */
function valueText(metric: Metric) {
  if (metric.value === null) {
    return '暂无数据'
  }
  return Number(metric.value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

/**
 * 卡片底部的口径补注：只留分母、样本与说明；
 *  「来源表 + 时间字段」不再上卡片（来源可追到接口字段，不必占卡片位置）
 */
function detail(metric: Metric) {
  const parts: string[] = []
  if (metric.denominator !== null) {
    parts.push(`分母 ${metric.denominator}`)
  }
  if (metric.sampleSize !== null) {
    parts.push(`样本 ${metric.sampleSize}`)
  }
  if (metric.note) {
    parts.push(metric.note)
  }
  return parts.join('；')
}

function changeOf(metric: Metric) {
  return changes.value[metric.key] ?? null
}

function changeArrow(change: Change) {
  return change.direction === 'up' ? '▲' : change.direction === 'down' ? '▼' : '—'
}

/** 颜色按极性：只有「这个方向是好事」才染绿，方向无意义的用中性色 */
function changeClass(metric: Metric, change: Change) {
  if (change.direction === 'flat' || DIRECTION_NEUTRAL.has(metric.key)) {
    return 'text-muted-foreground'
  }
  const good = LOWER_IS_BETTER.has(metric.key)
    ? change.direction === 'down'
    : change.direction === 'up'
  return good ? 'text-up' : 'text-down'
}

function changeTitle(metric: Metric, change: Change) {
  const polarity = LOWER_IS_BETTER.has(metric.key)
    ? '这个指标下降是好事'
    : DIRECTION_NEUTRAL.has(metric.key) ? '这个指标涨跌不代表好坏' : '这个指标上涨是好事'
  return `较上一周期 ${change.percent > 0 ? '+' : ''}${change.percent}%；${polarity}`
}

/** Unovis 数据点：x 用序号（字符串标签由横轴 tick-format 回填），null 按 0 画 */
interface ChartPoint {
  x: number
  label: string
  value: number
}

function chartPoints(item: Series): ChartPoint[] {
  return item.points.map((point, index) => ({ x: index, label: point.label, value: point.value ?? 0 }))
}

/** 点位多时只显示部分横轴标签，避免 30 个日期挤成一团 */
function showLabel(index: number, total: number) {
  if (total <= 12) {
    return true
  }
  return index % Math.ceil(total / 6) === 0 || index === total - 1
}

/** 横轴只在这些序号上打刻度（与 showLabel 同口径） */
function chartTicks(item: Series): number[] {
  return item.points.map((_, index) => index).filter(index => showLabel(index, item.points.length))
}

function tickLabel(item: Series, x: number): string {
  return item.points[Math.round(x)]?.label ?? ''
}

/** 每张序列图一份 config：单序列，颜色统一走 --chart-1（金） */
function chartConfigFor(item: Series): ChartConfig {
  return { value: { label: item.label, color: 'var(--chart-1)' } }
}

function displayValue(value: number | null, unit: string) {
  return value === null ? '暂无数据' : `${value}${unit}`
}

function isoDate(date: Date) {
  return date.toISOString().slice(0, 10)
}

/** 与当前窗口等长的前一个窗口：[from - 长度, from - 1 天] */
function previousWindow(from: string, to: string) {
  const start = new Date(`${from}T00:00:00Z`)
  const end = new Date(`${to}T00:00:00Z`)
  const days = Math.round((end.getTime() - start.getTime()) / 86400000) + 1
  const previousTo = new Date(start.getTime() - 86400000)
  const previousFrom = new Date(previousTo.getTime() - (days - 1) * 86400000)
  return { from: isoDate(previousFrom), to: isoDate(previousTo) }
}

function diff(currentMetrics: Metric[], previousMetrics: Metric[]) {
  const previous = new Map(previousMetrics.map(metric => [metric.key, metric.value]))
  const result: Record<string, Change> = {}
  for (const metric of currentMetrics) {
    const before = previous.get(metric.key)
    if (metric.value === null || before === null || before === undefined || before === 0) {
      continue
    }
    const delta = metric.value - before
    const percent = Math.round((delta / Math.abs(before)) * 1000) / 10
    result[metric.key] = {
      percent,
      direction: Math.abs(percent) < 0.1 ? 'flat' : (delta > 0 ? 'up' : 'down'),
    }
  }
  return result
}

async function load() {
  const token = userStore.token
  if (!token) {
    return
  }
  const generation = ++loadGeneration
  const section = props.section
  const period = effectivePeriod.value
  loading.value = true
  error.value = ''
  metrics.value = []
  series.value = []
  breakdown.value = []
  changes.value = {}
  try {
    const current = await fetchStats(token, section, { period })
    if (generation !== loadGeneration || token !== userStore.token) {
      return
    }
    metrics.value = current.metrics
    series.value = current.series ?? []
    breakdown.value = current.breakdown ?? []

    // 涨跌来自第二次请求：失败不影响主数据，只是没有涨跌
    changes.value = {}
    try {
      const window = previousWindow(current.from, current.to)
      const previous = await fetchStats(token, section, window)
      if (generation === loadGeneration && token === userStore.token) {
        changes.value = diff(current.metrics ?? [], previous.metrics ?? [])
      }
    }
    catch {
      if (generation === loadGeneration) {
        changes.value = {}
      }
    }
  }
  catch (e) {
    if (generation !== loadGeneration) {
      return
    }
    error.value = e instanceof ApiError ? e.message : '指标加载失败'
    metrics.value = []
    series.value = []
    breakdown.value = []
    changes.value = {}
    toast.error(error.value)
  }
  finally {
    if (generation === loadGeneration) {
      loading.value = false
    }
  }
}

watch([() => props.section, effectivePeriod, () => userStore.token], load, { immediate: true })
onUnmounted(() => {
  loadGeneration++
})
defineExpose({ load })
</script>

<template>
  <div class="flex flex-col gap-4">
    <div
      v-if="loading && !hasData"
      class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4"
    >
      <Skeleton v-for="index in 4" :key="`metric-skeleton-${index}`" class="h-40 w-full" />
    </div>

    <div v-else-if="metrics.length" class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Card
        v-for="metric in metrics"
        :key="metric.key"
        class="relative min-h-40 gap-0 overflow-hidden py-6 shadow-none"
      >
        <!-- 右上角微光：定稿卡片的识别特征 -->
        <div
          aria-hidden="true"
          class="pointer-events-none absolute -top-24 -right-20 size-48 rounded-full bg-gold/10 blur-3xl"
        />

        <CardContent class="relative flex h-full flex-col px-6">
          <span class="text-sm text-muted-foreground">{{ metric.label }}</span>

          <div class="mt-4 flex items-baseline gap-1.5">
            <span
              v-if="metric.value !== null"
              class="font-heading text-4xl leading-none text-gold"
            >
              {{ valueText(metric) }}
            </span>
            <span v-else class="text-xl leading-none text-muted-foreground">
              暂无数据
            </span>
            <span
              v-if="metric.value !== null && metric.unit"
              class="font-heading text-lg text-gold/70"
            >
              {{ metric.unit }}
            </span>
          </div>

          <div
            v-if="changeOf(metric)"
            class="mt-3 flex flex-wrap items-baseline gap-x-1.5 text-sm"
            :class="changeClass(metric, changeOf(metric)!)"
            :title="changeTitle(metric, changeOf(metric)!)"
          >
            <span aria-hidden="true">{{ changeArrow(changeOf(metric)!) }}</span>
            <span class="tabular-nums">{{ changeOf(metric)!.percent > 0 ? '+' : '' }}{{ changeOf(metric)!.percent }}%</span>
            <span class="text-xs text-muted-foreground">较上一周期</span>
          </div>

          <!-- 口径补注收成一行（悬停看全文）：卡片主体保持「标签 → 数字 → 涨跌」的干净结构 -->
          <p
            v-if="detail(metric)"
            class="mt-auto truncate pt-3 text-xs leading-relaxed text-muted-foreground"
            :title="detail(metric)"
          >
            {{ detail(metric) }}
          </p>
        </CardContent>
      </Card>
    </div>

    <div v-if="series.length" class="grid gap-4 lg:grid-cols-2">
      <Panel
        v-for="item in series"
        :key="item.key"
        :title="item.label"
        :note="`单位：${item.unit}`"
      >
        <ChartContainer :config="chartConfigFor(item)" class="h-[180px] w-full">
          <VisXYContainer :data="chartPoints(item)">
            <VisGroupedBar
              :x="(d: ChartPoint) => d.x"
              :y="(d: ChartPoint) => d.value"
              color="var(--color-value)"
              :rounded-corners="3"
              :bar-padding="0.2"
            />
            <VisAxis
              type="x"
              :x="(d: ChartPoint) => d.x"
              :tick-line="false"
              :domain-line="false"
              :grid-line="false"
              :tick-values="chartTicks(item)"
              :tick-format="(tick: number | Date) => tickLabel(item, typeof tick === 'number' ? tick : 0)"
            />
            <VisAxis
              type="y"
              :tick-line="false"
              :domain-line="false"
              :grid-line="true"
              :tick-format="() => ''"
            />
            <ChartTooltip />
            <ChartCrosshair
              :template="componentToString(chartConfigFor(item), ChartTooltipContent, {
                labelFormatter: (tick: number | Date) => tickLabel(item, typeof tick === 'number' ? tick : 0),
              })"
            />
          </VisXYContainer>
        </ChartContainer>
        <p v-if="item.points.length && item.points.every(point => point.value === 0)" class="text-sm text-muted-foreground">
          本区间所有数据点均为 0，并非图表加载失败。
        </p>

        <p v-if="item.note" class="mt-2 text-xs leading-relaxed text-muted-foreground">
          {{ item.note }}
        </p>
      </Panel>
    </div>

    <div v-if="breakdown.length" class="grid gap-4 lg:grid-cols-2">
      <Panel
        v-for="item in breakdown"
        :key="item.key"
        :title="item.label"
        :note="`单位：${item.unit}`"
      >
        <!-- 表头与单元格样式跟其他页的 DataTable 保持同一套规范，不再另起一套紧凑样式 -->
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead class="text-xs font-medium text-muted-foreground">
                分组
              </TableHead>
              <TableHead class="text-right text-xs font-medium text-muted-foreground">
                数值
              </TableHead>
              <TableHead class="text-xs font-medium text-muted-foreground">
                说明
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="row in item.rows" :key="row.key">
              <TableCell class="whitespace-normal">
                {{ row.label }}
              </TableCell>
              <TableCell class="text-right whitespace-normal tabular-nums">
                {{ displayValue(row.value, '') }}
              </TableCell>
              <TableCell class="whitespace-normal text-muted-foreground">
                {{ row.note ?? '' }}
              </TableCell>
            </TableRow>
            <TableRow v-if="item.rows.length === 0">
              <TableCell colspan="3" class="text-center text-muted-foreground">
                区间内没有数据
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>

        <p v-if="item.note" class="mt-2 text-xs leading-relaxed text-muted-foreground">
          {{ item.note }}
        </p>
      </Panel>
    </div>

    <EmptyState v-if="!loading && error" title="指标加载失败" :description="error">
      <Button variant="outline" @click="load">
        重新加载
      </Button>
    </EmptyState>
    <EmptyState
      v-if="!loading && !hasData && !error"
      title="暂无可展示的指标"
      description="这个区间内没有数据，或该口径尚未接入。"
      :icon="ChartColumnIcon"
    />
  </div>
</template>
