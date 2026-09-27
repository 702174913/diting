<script setup lang="ts">
/**
 * 录音资产（开发文档 §4.2、§7.3）。
 *
 * 列表按用户/状态/日期筛选分页；详情改为弹窗，内部按 Tabs 分区
 * （逐句转写 / 命中证据 / 图片资产 / 原件与导出）。
 * 弹窗状态同步到地址栏 ?detail=<id>，刷新与后退都能回到同一条。
 *
 * 说话人直接显示听悟 SpeakerId，不推断业务角色。
 *
 * 页面只负责呈现：真正的权限判断在服务端，看不到的按钮即使被改出来也会被 403。
 */
import type { ColumnDef } from '@tanstack/vue-table'
import { BanIcon, DownloadIcon, FileDownIcon, ImageIcon, MicIcon, PackageIcon, RefreshCwIcon } from '@lucide/vue'
import { computed, h, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import {
  createRecordingExport,
  deleteRecording,
  fetchRecordingAssets,
  fetchRecordingAudio,
  fetchRecordingDetail,
  fetchRecordings,
  reanalyzeRecording,
} from '@/api/admin'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import DataTablePagination from '@/components/DataTablePagination.vue'
import EmptyState from '@/components/EmptyState.vue'
import Hint from '@/components/Hint.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Spinner } from '@/components/ui/spinner'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  DELETION_STATUS_TEXT,
  MATCH_TYPE_TEXT,
  OCR_STATUS_TEXT,
  REPORT_STATUS_TEXT,
  TASK_STATUS_TEXT,
  TASK_STATUS_VARIANT,
  labelOf,
  optionsOf,
  variantOf,
} from '@/lib/enums'
import { displaySentenceNote } from '@/lib/recordingNotes'
import { useExportTaskStore } from '@/stores/exportTask'
import { useUserStore } from '@/stores/user'
import type { AdminReportDetail, AssetItem, RecordingItem } from '@/api/admin'

const userStore = useUserStore()
const exportStore = useExportTaskStore()
const route = useRoute()
const router = useRouter()

const items = ref<RecordingItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const filters = ref({ userId: '', status: '', from: '', to: '' })
const loading = ref(false)

const detail = ref<AdminReportDetail | null>(null)
const detailId = ref<number | null>(null)
const images = ref<AssetItem[]>([])
const audioUrl = ref('')
const audioTtl = ref<number | null>(null)
/** 播放器报错（链接过期/对象不可读）：不静默，配着「重新签发」一起给人看 */
const audioError = ref('')
const leasing = ref(false)
/** 正在提交的导出类型：null = 空闲。既用来置灰按钮，也让对应那一个换成进行中文案 */
const exporting = ref<'pdf' | 'zip' | null>(null)
const exportBusy = computed(() => exporting.value !== null)
const reanalyzing = ref(false)
const deleteOpen = ref(false)
const deleting = ref(false)
const activeTab = ref('transcript')

const isAdmin = computed(() => userStore.role === 'ADMIN')

/** 弹窗开合直接映射到 ?detail=<id>，刷新/后退都能复原 */
const detailOpen = computed({
  get: () => Boolean(route.query.detail),
  set: (value: boolean) => {
    if (!value) {
      closeDetail()
    }
  },
})

async function load() {
  const token = userStore.token
  if (!token) {
    return
  }
  loading.value = true
  try {
    const result = await fetchRecordings(token, {
      page: page.value,
      pageSize: pageSize.value,
      userId: filters.value.userId,
      status: filters.value.status,
      from: filters.value.from,
      to: filters.value.to,
    })
    items.value = result.items
    total.value = result.total
  }
  catch (e) {
    items.value = []
    total.value = 0
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  }
  finally {
    loading.value = false
  }
}

async function loadDetail(id: number) {
  const token = userStore.token
  if (!token) {
    return
  }
  detailId.value = id
  audioUrl.value = ''
  audioTtl.value = null
  audioError.value = ''
  images.value = []
  activeTab.value = 'transcript'
  try {
    detail.value = await fetchRecordingDetail(token, id)
  }
  catch (e) {
    detail.value = null
    toast.error(e instanceof ApiError ? e.message : '详情加载失败')
  }
  void loadImages(id)
}

/** 图片资产单独拉：地址由服务端签发且每次签发留痕，不塞进报告详情里 */
async function loadImages(id: number) {
  const token = userStore.token
  if (!token) {
    return
  }
  try {
    const list = await fetchRecordingAssets(token, id)
    images.value = list.filter(item => item.kind === 'IMAGE')
  }
  catch {
    // 资产拉取失败不影响报告查看
    images.value = []
  }
}

function openDetail(id: number) {
  void router.replace({ query: { ...route.query, detail: String(id) } })
}

function closeDetail() {
  const next = { ...route.query }
  delete next.detail
  void router.replace({ query: next })
}

watch(() => route.query.detail, (value) => {
  const id = value ? Number(value) : null
  if (!id) {
    detail.value = null
    detailId.value = null
    audioUrl.value = ''
    audioTtl.value = null
    audioError.value = ''
    images.value = []
    return
  }
  void loadDetail(id)
}, { immediate: true })

/**
 * 原件签发：仅 ADMIN；过期或已清理会被服务端拒绝。
 * silent = true 用于「进原件页签自动补一次」：不弹成功提示，但要看的都在页面上。
 */
async function leaseAudio(silent = false) {
  const token = userStore.token
  if (!token || detailId.value === null) {
    return
  }
  leasing.value = true
  try {
    const result = await fetchRecordingAudio(token, detailId.value)
    audioUrl.value = result.url
    audioTtl.value = result.ttlMinutes
    audioError.value = ''
    if (!silent) {
      toast.success(`链接有效期 ${result.ttlMinutes} 分钟，签发已留痕`)
    }
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '签发失败')
  }
  finally {
    leasing.value = false
  }
}

/**
 * 进「原件」页签就自动签发一次：想看原件的人不该再多点一步，
 * 审计依旧每次签发留痕（静默只是不弹 toast）。已过期也不会重拉，除非用户自己再点。
 */
watch(activeTab, (tab) => {
  if (tab !== 'origin' || audioUrl.value || leasing.value) {
    return
  }
  if (!isAdmin.value || !detail.value?.audioAvailable) {
    return
  }
  void leaseAudio(true)
})

/** 播放器失败：15 分钟 TTL 到期是最常见原因，直接指到重新签发 */
function onAudioError() {
  audioError.value = '这条链接已失效或录音读不出来，点上方「重新签发原件」再试一次。'
}

async function startExport(withAudio: boolean) {
  const token = userStore.token
  const current = detail.value
  if (!token || !current || exportBusy.value) {
    return
  }
  if (current.reportVersionId === null) {
    toast.error('这条记录还没有可导出的报告版本')
    return
  }
  exporting.value = withAudio ? 'zip' : 'pdf'
  // 点下去先给一条进行中提示：提交、生成、自动下载是三段时间，
  // 只把按钮置灰看着像没反应。同 id 替换，不会叠出第二条。
  const toastId = toast.loading(withAudio ? '正在提交含原件打包任务…' : '正在提交导出任务…')
  try {
    const job = await createRecordingExport(token, current.id, {
      reportVersionId: current.reportVersionId,
      withAudio,
    })
    const result = await exportStore.track(job, {
      recordingId: current.id,
      reportNo: current.reportNo,
      reportVersion: current.reportVersion,
    })
    // 服务端会复用有效产物：此时作业建完就是完成态。文案必须区分「正在下载」与「去导出任务下载」
    const ready = job.status === 'COMPLETED'
    toast.success(ready
      ? (result.downloaded
          ? `已加入下载任务（作业 #${job.exportId}）：产物已就绪，已开始下载`
          : `已加入下载任务（作业 #${job.exportId}）：产物已就绪，在顶栏「导出任务」里下载`)
      : (withAudio
          ? '已加入下载任务（含原件 ZIP），生成完成后会自动下载；进度见顶栏「导出任务」'
          : '已加入下载任务（PDF 报告），生成完成后会自动下载；进度见顶栏「导出任务」'), { id: toastId })
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '导出提交失败', { id: toastId })
  }
  finally {
    exporting.value = null
  }
}

/** 重新判读：按当前启用的规则集重算并发布新报告版本（旧版本仍可回读）；仅 ADMIN */
async function reanalyze() {
  const token = userStore.token
  const current = detail.value
  if (!token || !current || reanalyzing.value) {
    return
  }
  reanalyzing.value = true
  // 重判要按当前规则集算一遍：先给「已提交」，同 id 换成结果或失败
  const toastId = toast.loading('已提交，正在按当前规则集重算…')
  try {
    const result = await reanalyzeRecording(token, current.id)
    // 只刷新报告数据，不清掉已签发的原件链接与当前页签
    detail.value = await fetchRecordingDetail(token, current.id)
    toast.success(`已按当前规则集重判并发布第 ${result.reportVersion} 版：风险分 ${result.riskScore}、合规分 ${result.complianceScore}`, { id: toastId })
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '重新判读失败', { id: toastId })
  }
  finally {
    reanalyzing.value = false
  }
}

async function remove() {
  const token = userStore.token
  if (!token || detailId.value === null || deleting.value) {
    return
  }
  deleting.value = true
  try {
    const job = await deleteRecording(token, detailId.value)
    toast.success(`已删除，清理作业 #${job.deletionJobId}（${labelOf(DELETION_STATUS_TEXT, job.status)}）`)
    deleteOpen.value = false
    closeDetail()
    await load()
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  }
  finally {
    deleting.value = false
  }
}

/** 毫秒转秒：详情按秒读更顺，也不再出现“38000–44000ms”这种原始值 */
function toSeconds(ms: number) {
  return (Math.max(0, ms) / 1000).toFixed(1)
}

function speakerLabelOf(speakerId: number | null) {
  return speakerId === null ? '说话人未知' : `说话人 ${speakerId}`
}

interface TextPart {
  text: string
  hit: boolean
}

/**
 * 按证据区间把句子切成片段，命中词单独染色。
 * 区间是服务端给的原文 UTF-16 左闭右开 —— JS 字符串恰好就是 UTF-16，slice 可以直接用。
 */
function segments(text: string, ranges: { start: number, end: number }[] | undefined): TextPart[] {
  if (!ranges || ranges.length === 0) {
    return [{ text, hit: false }]
  }
  const parts: TextPart[] = []
  let cursor = 0
  for (const range of [...ranges].sort((a, b) => a.start - b.start)) {
    if (range.start > cursor) {
      parts.push({ text: text.slice(cursor, range.start), hit: false })
    }
    parts.push({ text: text.slice(range.start, range.end), hit: true })
    cursor = Math.max(cursor, range.end)
  }
  if (cursor < text.length) {
    parts.push({ text: text.slice(cursor), hit: false })
  }
  return parts
}

/** 证据对应的原句：优先证据句，其次触发句（require 的缺失项没有证据句） */
function violationSentence(violation: { sentenceId: number | null, triggerSentenceId: number | null }) {
  const id = violation.sentenceId ?? violation.triggerSentenceId
  if (id === null || id === undefined) {
    return null
  }
  return detail.value?.sentences.find(item => item.id === id) ?? null
}

/** 筛选下拉：界面用中文、提交的是英文码；reka-ui 不接受空字符串选项，用 ALL 当“全部”哨兵 */
const statusFilter = computed({
  get: () => filters.value.status || 'ALL',
  set: (value: string) => {
    filters.value.status = value === 'ALL' ? '' : value
  },
})
const statusOptions = optionsOf(TASK_STATUS_TEXT)

function mmss(seconds: number | null) {
  if (seconds === null || seconds === undefined) {
    return '—'
  }
  const total = Math.floor(Math.max(0, seconds) / 60)
  return `${String(total).padStart(2, '0')}:${String(Math.max(0, seconds) % 60).padStart(2, '0')}`
}

function shortTime(value: string | null) {
  return value ? value.slice(5, 16).replace('T', ' ') : '—'
}

const columns: ColumnDef<RecordingItem>[] = [
  {
    accessorKey: 'id',
    header: '记录',
    cell: ({ row }) => `#${row.original.id}`,
  },
  { accessorKey: 'userId', header: '用户' },
  {
    accessorKey: 'status',
    header: '状态',
    cell: ({ row }) => h(
      Badge,
      {
        variant: variantOf(TASK_STATUS_VARIANT, row.original.status),
        size: 'chip',
      },
      () => labelOf(TASK_STATUS_TEXT, row.original.status),
    ),
  },
  {
    accessorKey: 'durationSec',
    header: '时长',
    cell: ({ row }) => mmss(row.original.durationSec),
  },
  {
    accessorKey: 'createdAt',
    header: '创建时间',
    cell: ({ row }) => shortTime(row.original.createdAt),
  },
  {
    id: 'audio',
    header: '原件',
    cell: ({ row }) => (row.original.audioAvailable ? '可签发' : '已过期/已清理'),
  },
  {
    id: 'actions',
    header: '',
    cell: ({ row }) => h(
      Button,
      {
        variant: 'link',
        size: 'sm',
        onClick: () => openDetail(row.original.id),
      },
      () => '查看',
    ),
  },
]

const ocrDoneCount = computed(() => images.value.filter(item => item.ocrStatus === 'DONE').length)

load()
</script>

<template>
  <div class="flex flex-col gap-4">
    <PageHeader
      title="录音资产"
      description="按用户、状态、日期筛选；详情按听悟说话人编号展示，管理员可签发原件、导出与删除。"
    >
      <template #actions>
        <span class="text-sm text-muted-foreground">共 {{ total }} 条</span>
      </template>
    </PageHeader>

    <Panel>
      <form
        class="flex flex-wrap items-end gap-3"
        @submit.prevent="page = 1; load()"
      >
        <FieldGroup class="flex flex-row flex-wrap items-end gap-3">
          <Field class="w-32">
            <FieldLabel for="filter-user">
              用户 ID
            </FieldLabel>
            <Input id="filter-user" v-model="filters.userId" placeholder="全部" />
          </Field>
          <Field class="w-40">
            <FieldLabel for="filter-status">
              状态
            </FieldLabel>
            <Select v-model="statusFilter">
              <SelectTrigger id="filter-status">
                <SelectValue placeholder="全部状态" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">
                  全部状态
                </SelectItem>
                <SelectItem
                  v-for="option in statusOptions"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </SelectItem>
              </SelectContent>
            </Select>
          </Field>
          <Field class="w-40">
            <FieldLabel for="filter-from">
              起
            </FieldLabel>
            <Input id="filter-from" v-model="filters.from" placeholder="2026-09-01" />
          </Field>
          <Field class="w-40">
            <FieldLabel for="filter-to">
              止
            </FieldLabel>
            <Input id="filter-to" v-model="filters.to" placeholder="2026-09-30" />
          </Field>
          <Button type="submit" :disabled="loading">
            <Spinner v-if="loading" data-icon="inline-start" aria-label="查询中" />
            {{ loading ? '查询中…' : '查询' }}
          </Button>
        </FieldGroup>
      </form>
    </Panel>

    <Panel flush>
      <div class="px-5 py-4">
        <DataTable
          :columns="columns"
          :data="items"
          :loading="loading"
          empty-text="没有符合条件的记录"
        >
          <template #footer>
            <DataTablePagination
              v-model:page="page"
              :page-size="pageSize"
              :total="total"
              :disabled="loading"
              @update:page="load()"
            />
          </template>
        </DataTable>
      </div>
    </Panel>

    <Dialog v-model:open="detailOpen">
      <DialogContent class="sm:max-w-4xl">
        <DialogHeader v-if="detail">
          <DialogTitle>
            {{ detail.reportNo }} · 第 {{ detail.reportVersion ?? '—' }} 版
          </DialogTitle>
          <DialogDescription>
            状态 {{ detail.status ? labelOf(REPORT_STATUS_TEXT, detail.status) : '未生成' }}｜命中 {{ detail.flaggedCount }} 句｜违规 {{ detail.violationCount }} 项｜风险分 {{ detail.riskScore ?? '未发布' }}
          </DialogDescription>
          <div class="flex flex-wrap gap-2">
            <Badge v-if="detail.riskFlagged" variant="down" size="chip">
              超阈值
            </Badge>
            <Badge v-if="detail.redacted" variant="warn" size="chip">
              文本已脱敏
            </Badge>
          </div>
        </DialogHeader>

        <div v-if="detail" class="flex flex-col gap-3">
          <Hint v-if="detail.redacted">
            文本已脱敏，掩码等长，因此高亮位置不变。
          </Hint>
          <Hint v-for="note in detail.notes" :key="note">
            {{ note }}
          </Hint>

          <div class="flex flex-wrap items-center gap-2">
            <Button
              v-if="isAdmin"
              variant="outline"
              size="sm"
              :disabled="leasing"
              @click="leaseAudio()"
            >
              <MicIcon data-icon="inline-start" />
              {{ leasing ? '签发中…' : (audioUrl ? '重新签发原件' : '签发原件') }}
            </Button>
            <Button
              v-if="isAdmin"
              variant="outline"
              size="sm"
              :disabled="reanalyzing"
              @click="reanalyze"
            >
              <RefreshCwIcon data-icon="inline-start" />
              {{ reanalyzing ? '判读中…' : '重新判读' }}
            </Button>
            <Button
              variant="outline"
              size="sm"
              :disabled="exportBusy"
              @click="startExport(false)"
            >
              <FileDownIcon data-icon="inline-start" />
              {{ exporting === 'pdf' ? '正在提交…' : '导出报告' }}
            </Button>
            <Button
              v-if="isAdmin"
              variant="outline"
              size="sm"
              :disabled="exportBusy"
              @click="startExport(true)"
            >
              <PackageIcon data-icon="inline-start" />
              {{ exporting === 'zip' ? '正在提交…' : '导出含原件 ZIP' }}
            </Button>
            <Button
              v-if="isAdmin"
              variant="destructive"
              size="sm"
              class="ml-auto"
              @click="deleteOpen = true"
            >
              <BanIcon data-icon="inline-start" />
              删除
            </Button>
          </div>
        </div>

        <Tabs v-if="detail" v-model="activeTab" class="min-h-[45vh]">
          <TabsList>
            <TabsTrigger value="transcript">
              逐句转写（{{ detail.sentences.length }}）
            </TabsTrigger>
            <TabsTrigger value="evidence">
              命中证据（{{ detail.violations.length }}）
            </TabsTrigger>
            <TabsTrigger value="assets">
              图片（{{ images.length }}）
            </TabsTrigger>
            <TabsTrigger value="origin">
              原件与导出
            </TabsTrigger>
          </TabsList>

          <TabsContent value="transcript" class="max-h-[45vh] overflow-y-auto">
            <div
              v-for="sentence in detail.sentences"
              :key="sentence.id"
              class="border-b border-border py-2 pl-2"
              :class="sentence.highlights.length ? 'border-l-2 border-l-down' : 'border-l-2 border-l-transparent'"
            >
              <div class="text-xs text-muted-foreground">
                {{ toSeconds(sentence.startMs) }}–{{ toSeconds(sentence.endMs) }} 秒 · {{ speakerLabelOf(sentence.speakerId) }}
              </div>
              <div class="text-sm">
                <template
                  v-for="(part, index) in segments(sentence.text, sentence.highlights)"
                  :key="index"
                >
                  <span v-if="part.hit" class="font-medium text-down">{{ part.text }}</span>
                  <span v-else>{{ part.text }}</span>
                </template>
              </div>
              <div
                v-for="note in sentence.notes"
                :key="note"
                class="text-xs text-warn"
              >
                {{ displaySentenceNote(note) }}
              </div>
            </div>
            <EmptyState
              v-if="detail.sentences.length === 0"
              title="还没有转写句子"
              description="任务可能仍在转写或解析中。"
              :icon="MicIcon"
            />
          </TabsContent>

          <TabsContent value="evidence" class="max-h-[45vh] overflow-y-auto">
            <div
              v-for="violation in detail.violations"
              :key="violation.id"
              class="border-b border-border py-2 text-sm"
            >
              <div class="flex flex-wrap items-center gap-2">
                <Badge variant="down" size="chip">
                  {{ violation.matchType ? labelOf(MATCH_TYPE_TEXT, violation.matchType) : '命中' }}
                </Badge>
                <span>{{ violation.ruleName ?? '规则' }}</span>
                <span class="text-muted-foreground">扣 {{ violation.deduct ?? 0 }}</span>
              </div>
              <div class="mt-1 text-xs text-muted-foreground">
                {{ violation.note }}
              </div>
              <div
                v-if="violationSentence(violation)"
                class="mt-1 rounded bg-down/5 px-2 py-1 text-sm"
              >
                <template
                  v-for="(part, index) in segments(violationSentence(violation)!.text, violationSentence(violation)!.highlights)"
                  :key="index"
                >
                  <span v-if="part.hit" class="font-medium text-down">{{ part.text }}</span>
                  <span v-else>{{ part.text }}</span>
                </template>
              </div>
            </div>
            <EmptyState
              v-if="detail.violations.length === 0"
              title="没有命中证据"
              description="这条记录在当前规则集下没有违规命中。"
              :icon="FileDownIcon"
            />
          </TabsContent>

          <TabsContent value="assets" class="max-h-[45vh] overflow-y-auto">
            <p class="mb-2 text-xs text-muted-foreground">
              共 {{ images.length }} 张，已识别文字 {{ ocrDoneCount }} 张。地址由服务端签发，每次签发留痕。
            </p>
            <div class="flex flex-wrap gap-2">
              <div
                v-for="asset in images"
                :key="asset.assetId"
                class="w-40 rounded-md border border-border p-2"
              >
                <a
                  v-if="asset.url"
                  :href="asset.url"
                  target="_blank"
                  rel="noopener"
                  class="block truncate text-xs text-primary underline-offset-4 hover:underline"
                >
                  {{ asset.filename }}
                </a>
                <span v-else class="block truncate text-xs text-muted-foreground">
                  {{ asset.filename }}（已清理）
                </span>
                <div class="mt-1 text-[10px] text-muted-foreground">
                  OCR：{{ labelOf(OCR_STATUS_TEXT, asset.ocrStatus) }}
                </div>
              </div>
            </div>
            <EmptyState
              v-if="images.length === 0"
              title="没有图片资产"
              description="这条记录只有音频，或图片已过保留期。"
              :icon="ImageIcon"
            />
          </TabsContent>

          <TabsContent value="origin" class="max-h-[45vh] overflow-y-auto">
            <div class="flex flex-col gap-3 text-sm">
              <p class="text-muted-foreground">
                原件与导出都走服务端签发/生成，每次调取都会留审计日志。签发的原件可以直接在这里试听；加入下载任务后生成完会自动下载，也可在顶栏「导出任务」里手动重签。
              </p>
              <div v-if="audioUrl" class="flex flex-col gap-2 rounded-md border border-border p-3">
                <div class="text-xs text-muted-foreground">
                  原件播放 · 链接 {{ audioTtl ?? '—' }} 分钟内有效
                </div>
                <audio
                  class="w-full"
                  controls
                  preload="metadata"
                  :src="audioUrl"
                  @error="onAudioError"
                />
                <p v-if="audioError" class="text-xs text-muted-foreground">
                  {{ audioError }}
                </p>
                <div class="break-all text-[10px] text-muted-foreground">
                  <a
                    :href="audioUrl"
                    target="_blank"
                    rel="noopener"
                    class="text-primary underline-offset-4 hover:underline"
                  >
                    {{ audioUrl }}
                  </a>
                </div>
              </div>
              <p v-else-if="leasing" class="text-xs text-muted-foreground">
                正在签发播放链接…
              </p>
              <p v-else class="text-xs text-muted-foreground">
                {{ isAdmin
                  ? (detail.audioAvailable ? '点上方「签发原件」获取播放链接（仅管理员）。' : '原始录音已过保留期或已清理，无法试听。')
                  : '当前角色不能调取录音原件。' }}
              </p>
              <Separator />
              <div class="flex flex-wrap gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  :disabled="exportBusy"
                  @click="startExport(false)"
                >
                  <DownloadIcon data-icon="inline-start" />
                  {{ exporting === 'pdf' ? '正在提交…' : '导出 PDF' }}
                </Button>
                <Button
                  v-if="isAdmin"
                  variant="outline"
                  size="sm"
                  :disabled="exportBusy"
                  @click="startExport(true)"
                >
                  <PackageIcon data-icon="inline-start" />
                  {{ exporting === 'zip' ? '正在提交…' : '导出含原件 ZIP' }}
                </Button>
              </div>
            </div>
          </TabsContent>
        </Tabs>

        <DialogFooter v-if="detail">
          <Button variant="outline" @click="closeDetail">
            关闭
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <AlertDialog v-model:open="deleteOpen">
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>删除录音与记录</AlertDialogTitle>
          <AlertDialogDescription>
            删除后录音与记录将立即不可读，清理作业会在后台执行且无法恢复。确定继续吗？
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel :disabled="deleting">
            取消
          </AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            :disabled="deleting"
            @click="remove"
          >
            {{ deleting ? '删除中…' : '确认删除' }}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  </div>
</template>
