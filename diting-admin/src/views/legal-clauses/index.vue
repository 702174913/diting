<script setup lang="ts">
/**
 * 条款库（V13）：规则命中时随报告下发的「相关依据」。
 *
 * 与规则库的关键差别：绑定按「规则」存储、实时生效 —— 不生成规则新版本、不走
 * expectedVersion 乐观锁，报告也不存依据快照。所以改原文、改绑定会立刻反映到
 * 所有引用它的报告（含历史版本重读），写操作要当成线上文案改动。
 *
 * 停用（DELETE）不物理删除：已有绑定与报告引用照旧展示，只是不能再被新绑定
 * —— 绑定接口对停用条款返回 422 并整单拒绝，所以绑定弹窗里已停用的绑定只能解除。
 *
 * 查看与写入全部走弹窗：新增/修改（legal-clause-editor-dialog）、详情（本页内联只读）、
 * 停用确认（AlertDialog）。
 */
import type { ColumnDef } from '@tanstack/vue-table'
import { BanIcon, EyeIcon, PencilIcon, PlusIcon, ScaleIcon } from '@lucide/vue'
import { h, onMounted, ref } from 'vue'
import { toast } from 'vue-sonner'
import LegalClauseEditorDialog from './legal-clause-editor-dialog.vue'
import { disableLegalClause, fetchLegalClauses } from '@/api/admin'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import DataTablePagination from '@/components/DataTablePagination.vue'
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
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useUserStore } from '@/stores/user'
import type { LegalClauseItem } from '@/api/admin'

const userStore = useUserStore()

const items = ref<LegalClauseItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const enabledFilter = ref('ALL')
const loading = ref(false)

const editorOpen = ref(false)
const editingClause = ref<LegalClauseItem | null>(null)

const detailOpen = ref(false)
const detailClause = ref<LegalClauseItem | null>(null)

const disableOpen = ref(false)
const disablingClause = ref<LegalClauseItem | null>(null)
const disabling = ref(false)

const isAdmin = userStore.role === 'ADMIN'

function shortTime(value: string | null) {
  return value ? value.slice(0, 16).replace('T', ' ') : '—'
}

/** ALL 表示不带筛选；ENABLED/DISABLED 转成布尔，false 会被 buildQuery 保留 */
function enabledParam(): boolean | undefined {
  if (enabledFilter.value === 'ENABLED') {
    return true
  }
  return enabledFilter.value === 'DISABLED' ? false : undefined
}

async function load() {
  const token = userStore.token
  if (!token) {
    return
  }
  loading.value = true
  try {
    const result = await fetchLegalClauses(token, {
      page: page.value,
      pageSize: pageSize.value,
      keyword: keyword.value,
      enabled: enabledParam(),
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

function startCreate() {
  editingClause.value = null
  editorOpen.value = true
}

function startEdit(clause: LegalClauseItem) {
  editingClause.value = clause
  editorOpen.value = true
}

function openDetail(clause: LegalClauseItem) {
  detailClause.value = clause
  detailOpen.value = true
}

function askDisable(clause: LegalClauseItem) {
  disablingClause.value = clause
  disableOpen.value = true
}

async function confirmDisable() {
  const token = userStore.token
  const clause = disablingClause.value
  if (!token || !clause || disabling.value) {
    return
  }
  disabling.value = true
  try {
    await disableLegalClause(token, clause.id)
    toast.success('已停用（已有绑定与报告引用不变，只是不能再被新绑定）')
    disableOpen.value = false
    disablingClause.value = null
    await load()
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '停用失败')
  }
  finally {
    disabling.value = false
  }
}

const columns: ColumnDef<LegalClauseItem>[] = [
  { accessorKey: 'lawName', header: '法规名称' },
  {
    accessorKey: 'articleNo',
    header: '条款号',
    cell: ({ row }) => h('span', { class: 'whitespace-normal text-muted-foreground' }, row.original.articleNo),
  },
  {
    id: 'content',
    header: '条款原文',
    cell: ({ row }) => h('div', {
      class: 'line-clamp-2 max-w-[34rem] text-muted-foreground',
      title: row.original.content,
    }, row.original.content),
  },
  {
    id: 'remark',
    header: '备注',
    cell: ({ row }) => h('span', { class: 'whitespace-normal text-muted-foreground' }, row.original.remark ?? '—'),
  },
  {
    id: 'enabled',
    header: '状态',
    cell: ({ row }) => h(
      Badge,
      { variant: row.original.enabled ? 'up' : 'secondary', size: 'chip' },
      () => (row.original.enabled ? '启用' : '已停用'),
    ),
  },
  {
    id: 'updatedAt',
    header: '更新时间',
    cell: ({ row }) => shortTime(row.original.updatedAt),
  },
  {
    id: 'actions',
    header: '',
    cell: ({ row }) => h(
      'div',
      { class: 'flex items-center justify-end gap-1' },
      [
        h(Button, { variant: 'ghost', size: 'sm', onClick: () => openDetail(row.original) }, () => [
          h(EyeIcon, { 'data-icon': 'inline-start' }),
          '查看',
        ]),
        isAdmin
          ? h(Button, { variant: 'ghost', size: 'sm', onClick: () => startEdit(row.original) }, () => [
              h(PencilIcon, { 'data-icon': 'inline-start' }),
              '修改',
            ])
          : null,
        isAdmin && row.original.enabled
          ? h(Button, { variant: 'ghost', size: 'sm', onClick: () => askDisable(row.original) }, () => [
              h(BanIcon, { 'data-icon': 'inline-start' }),
              '停用',
            ])
          : null,
      ],
    ),
  },
]

onMounted(load)
</script>

<template>
  <div class="flex flex-col gap-4">
    <PageHeader
      title="条款库"
      description="规则命中时随报告下发的「相关依据」；改原文即刻生效，停用不删除。"
    >
      <template #actions>
        <span class="text-sm text-muted-foreground">共 {{ total }} 条</span>
        <Button v-if="isAdmin" @click="startCreate">
          <PlusIcon data-icon="inline-start" />
          新增条款
        </Button>
      </template>
    </PageHeader>

    <Panel flush>
      <form
        class="flex flex-wrap items-end gap-3 px-5 pt-4"
        @submit.prevent="page = 1; load()"
      >
        <FieldGroup class="flex flex-row flex-wrap items-end gap-4">
          <Field class="w-64">
            <FieldLabel for="clause-keyword">
              关键词
            </FieldLabel>
            <Input
              id="clause-keyword"
              v-model="keyword"
              placeholder="法规名称 / 条款号 / 原文"
            />
          </Field>
          <Field class="w-40">
            <FieldLabel for="clause-enabled-filter">
              状态
            </FieldLabel>
            <Select v-model="enabledFilter" @update:model-value="page = 1; load()">
              <SelectTrigger id="clause-enabled-filter" class="w-full">
                <SelectValue placeholder="全部状态" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  <SelectItem value="ALL">
                    全部状态
                  </SelectItem>
                  <SelectItem value="ENABLED">
                    启用
                  </SelectItem>
                  <SelectItem value="DISABLED">
                    已停用
                  </SelectItem>
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>
          <Button type="submit" :disabled="loading">
            {{ loading ? '查询中…' : '查询' }}
          </Button>
          <span v-if="!isAdmin" class="pb-2 text-sm text-muted-foreground">
            只读角色：可查看，不能修改
          </span>
        </FieldGroup>
      </form>

      <div class="px-5 py-4">
        <DataTable
          :columns="columns"
          :data="items"
          :loading="loading"
          empty-text="条款库还是空的"
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

    <Hint>
      <b>口径：</b>条款绑定在<b>规则</b>上、不随规则版本冻结，报告也不存依据快照 ——
      改原文或改绑定，引用它的报告（含历史版本）重读时展示的就是最新的。
      停用只是「不再能被新绑定」：已有绑定照旧下发，解绑要在规则的「绑定条款」里取消勾选。
      新增、修改、停用都会写「权限与审计」。
    </Hint>

    <LegalClauseEditorDialog
      v-model:open="editorOpen"
      :clause="editingClause"
      @saved="load()"
    />

    <Dialog v-model:open="detailOpen">
      <DialogContent class="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>条款详情</DialogTitle>
          <DialogDescription>
            {{ detailClause?.lawName }} {{ detailClause?.articleNo }}
          </DialogDescription>
        </DialogHeader>

        <div class="flex max-h-[55vh] flex-col gap-3 overflow-y-auto text-sm">
          <div class="flex items-center gap-2">
            <Badge :variant="detailClause?.enabled ? 'up' : 'secondary'" size="chip">
              {{ detailClause?.enabled ? '启用' : '已停用' }}
            </Badge>
            <span class="text-xs text-muted-foreground">
              更新于 {{ shortTime(detailClause?.updatedAt ?? null) }}
            </span>
          </div>
          <p class="whitespace-pre-wrap rounded-md border border-border bg-muted/40 p-3 leading-relaxed">
            {{ detailClause?.content }}
          </p>
          <p v-if="detailClause?.remark" class="text-xs text-muted-foreground">
            备注：{{ detailClause.remark }}
          </p>
        </div>

        <DialogFooter>
          <Button variant="outline" @click="detailOpen = false">
            关闭
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <AlertDialog v-model:open="disableOpen">
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>停用条款</AlertDialogTitle>
          <AlertDialogDescription>
            停用《{{ disablingClause?.lawName }} {{ disablingClause?.articleNo }}》？
            已有绑定与报告里的依据不受影响，只是不能再被新绑定；需要从报告里去掉，请到「规则库 → 绑定条款」里取消勾选。
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel :disabled="disabling">
            取消
          </AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            :disabled="disabling"
            @click="confirmDisable"
          >
            {{ disabling ? '处理中…' : '确认停用' }}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>

    <p class="flex items-center gap-2 text-xs text-muted-foreground">
      <ScaleIcon class="size-3.5" />
      条款原文以现行有效版本为准；这里存的是「规则命中时引用的依据」，不构成法律意见。
    </p>
  </div>
</template>
