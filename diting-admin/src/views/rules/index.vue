<script setup lang="ts">
/**
 * 规则库（开发文档 §4.2、§8）。
 *
 * 修改一定生成新版本（带 expectedVersion 乐观锁）；停用只影响未来的规则集合，
 * 历史版本与历史报告不动。这里不做独立的「模型管理」页：模型组就是规则的分组字段。
 *
 * 查看与写入全部走弹窗：新增/修改（rule-editor-dialog）、详情与版本历史
 * （rule-history-dialog）、批量导入（rule-import-dialog）、绑定法律条款
 * （rule-clause-bind-dialog）、停用确认（AlertDialog）、当前规则集快照（本页内联，只读）。
 *
 * 「法律依据」列是绑定条款的入口：绑定按规则存储、实时生效，不生成新版本。
 */
import type { ColumnDef } from '@tanstack/vue-table'
import {
  BanIcon,
  BookTextIcon,
  EyeIcon,
  PackageIcon,
  PencilIcon,
  PlusIcon,
  ScaleIcon,
  UploadIcon,
} from '@lucide/vue'
import { h, onMounted, ref } from 'vue'
import { toast } from 'vue-sonner'
import RuleClauseBindDialog from './rule-clause-bind-dialog.vue'
import RuleEditorDialog from './rule-editor-dialog.vue'
import RuleHistoryDialog from './rule-history-dialog.vue'
import RuleImportDialog from './rule-import-dialog.vue'
import { disableRule, fetchRuleSnapshot, fetchRules } from '@/api/admin'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import DataTablePagination from '@/components/DataTablePagination.vue'
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
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableEmpty,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { RULE_SCOPE_TEXT as ROLE_LABELS, MATCH_TYPE_TEXT as TYPE_LABELS } from '@/lib/enums'
import { ROLE_TARGETING_ENABLED } from '@/lib/features'
import { useUserStore } from '@/stores/user'
import type { RuleItem, RuleSetSnapshot } from '@/api/admin'

const userStore = useUserStore()

const items = ref<RuleItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loading = ref(false)

const editorOpen = ref(false)
const editingRule = ref<RuleItem | null>(null)
const historyOpen = ref(false)
const historyRule = ref<RuleItem | null>(null)
const importOpen = ref(false)

const bindOpen = ref(false)
const bindingRule = ref<RuleItem | null>(null)

const disableOpen = ref(false)
const disablingRule = ref<RuleItem | null>(null)
const disabling = ref(false)

const snapshotOpen = ref(false)
const snapshot = ref<RuleSetSnapshot | null>(null)
const snapshotLoading = ref(false)

const isAdmin = computedAdmin()

function computedAdmin() {
  return Boolean(userStore.role === 'ADMIN')
}

function typeLabel(value: string) {
  return TYPE_LABELS[value] ?? value
}

function roleLabel(value: string) {
  return ROLE_LABELS[value] ?? value
}

async function load() {
  const token = userStore.token
  if (!token) {
    return
  }
  loading.value = true
  try {
    const result = await fetchRules(token, { page: page.value, pageSize: pageSize.value, keyword: keyword.value })
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
  editingRule.value = null
  editorOpen.value = true
}

function startEdit(rule: RuleItem) {
  editingRule.value = rule
  editorOpen.value = true
}

function openHistory(rule: RuleItem) {
  historyRule.value = rule
  historyOpen.value = true
}

function openBind(rule: RuleItem) {
  bindingRule.value = rule
  bindOpen.value = true
}

/** 绑定条数：服务端在 get/list/create/update 都返回，历史版本接口恒为空 */
function clauseCount(rule: RuleItem) {
  return rule.legalClauses?.length ?? 0
}

function askDisable(rule: RuleItem) {
  disablingRule.value = rule
  disableOpen.value = true
}

async function confirmDisable() {
  const token = userStore.token
  const rule = disablingRule.value
  if (!token || !rule || disabling.value) {
    return
  }
  disabling.value = true
  try {
    await disableRule(token, rule.ruleId)
    toast.success('已停用（仅影响未来的规则集合）')
    disableOpen.value = false
    disablingRule.value = null
    await load()
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '停用失败')
  }
  finally {
    disabling.value = false
  }
}

async function openSnapshot() {
  snapshotOpen.value = true
  snapshotLoading.value = true
  const token = userStore.token
  if (!token) {
    return
  }
  try {
    snapshot.value = await fetchRuleSnapshot(token)
  }
  catch (e) {
    snapshot.value = null
    toast.error(e instanceof ApiError ? e.message : '快照加载失败')
  }
  finally {
    snapshotLoading.value = false
  }
}

const columns: ColumnDef<RuleItem>[] = [
  { accessorKey: 'code', header: '编码' },
  { accessorKey: 'name', header: '名称' },
  {
    id: 'matchType',
    header: '类型',
    cell: ({ row }) => typeLabel(row.original.matchType),
  },
  {
    id: 'roleScope',
    header: '角色',
    cell: ({ row }) => roleLabel(row.original.roleScope),
  },
  { accessorKey: 'deduct', header: '扣分' },
  {
    id: 'version',
    header: '版本',
    cell: ({ row }) => `v${row.original.version}`,
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
    id: 'legalClauses',
    header: '法律依据',
    // 绑定入口就放在数据里：ADMIN 点条数直接进绑定弹窗，其他人只读
    cell: ({ row }) => h(
      isAdmin
        ? Button
        : 'span',
      isAdmin
        ? { variant: 'ghost', size: 'sm', class: '-ml-2', onClick: () => openBind(row.original) }
        : { class: 'text-muted-foreground' },
      () => [
        isAdmin ? h(ScaleIcon, { 'data-icon': 'inline-start' }) : null,
        clauseCount(row.original) > 0 ? `${clauseCount(row.original)} 条` : (isAdmin ? '未绑定' : '—'),
      ],
    ),
  },
  {
    id: 'actions',
    header: '',
    cell: ({ row }) => h(
      'div',
      { class: 'flex items-center justify-end gap-1' },
      [
        h(Button, { variant: 'ghost', size: 'sm', onClick: () => openHistory(row.original) }, () => [
          h(EyeIcon, { 'data-icon': 'inline-start' }),
          '详情',
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
  // 目标角色功能隐藏时不展示角色列（见 lib/features.ts）
].filter(column => ROLE_TARGETING_ENABLED || column.id !== 'roleScope') as ColumnDef<RuleItem>[]

onMounted(load)
</script>

<template>
  <div class="flex flex-col gap-4">
    <PageHeader
      title="规则库"
      description="修改生成新版本；历史报告保持不变，停用仅影响未来。"
    >
      <template #actions>
        <span class="text-sm text-muted-foreground">共 {{ total }} 条</span>
        <Button variant="outline" @click="openSnapshot">
          <PackageIcon data-icon="inline-start" />
          当前规则集快照
        </Button>
        <Button
          v-if="isAdmin"
          variant="outline"
          @click="importOpen = true"
        >
          <UploadIcon data-icon="inline-start" />
          批量导入
        </Button>
        <Button v-if="isAdmin" @click="startCreate">
          <PlusIcon data-icon="inline-start" />
          新增规则
        </Button>
      </template>
    </PageHeader>

    <Panel flush>
      <form
        class="flex flex-wrap items-end gap-3 px-5 pt-4"
        @submit.prevent="page = 1; load()"
      >
        <FieldGroup class="flex flex-row flex-wrap items-end gap-3">
          <Field class="w-64">
            <FieldLabel for="rule-keyword">
              规则编码
            </FieldLabel>
            <Input id="rule-keyword" v-model="keyword" placeholder="按编码筛选" />
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
          empty-text="还没有规则"
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

    <RuleEditorDialog
      v-model:open="editorOpen"
      :rule="editingRule"
      @saved="load()"
    />

    <RuleHistoryDialog
      v-model:open="historyOpen"
      :rule="historyRule"
    />

    <RuleImportDialog
      v-model:open="importOpen"
      @imported="load()"
    />

    <RuleClauseBindDialog
      v-model:open="bindOpen"
      :rule="bindingRule"
      @saved="load()"
    />

    <AlertDialog v-model:open="disableOpen">
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>停用规则</AlertDialogTitle>
          <AlertDialogDescription>
            停用《{{ disablingRule?.name ?? '' }}》？历史版本与历史报告不受影响，只是不再进入未来的规则集合。
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

    <Dialog v-model:open="snapshotOpen">
      <DialogContent class="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>当前规则集快照</DialogTitle>
          <DialogDescription>
            分析开始时冻结启用规则的集合，后续规则改动不会改变这次判读的结果。
            {{ snapshot ? `快照版本 ruleSetVersion=${snapshot.ruleSetVersion}，共 ${snapshot.rules.length} 条启用规则。` : '' }}
          </DialogDescription>
        </DialogHeader>

        <div v-if="snapshotLoading" class="flex flex-col gap-2">
          <Skeleton class="h-5 w-full" />
          <Skeleton class="h-5 w-full" />
          <Skeleton class="h-5 w-2/3" />
        </div>
        <div v-else class="max-h-[55vh] overflow-y-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>规则</TableHead>
                <TableHead>类型</TableHead>
                <TableHead>扣分</TableHead>
                <TableHead>版本行 id</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="!snapshot || snapshot.rules.length === 0" :colspan="4">
                <span class="text-muted-foreground">当前没有启用中的规则</span>
              </TableEmpty>
              <TableRow
                v-for="item in snapshot?.rules ?? []"
                :key="item.ruleVersionId"
              >
                <TableCell class="whitespace-normal">
                  {{ item.name }}
                </TableCell>
                <TableCell class="whitespace-normal">
                  {{ typeLabel(item.matchType) }}
                </TableCell>
                <TableCell class="whitespace-normal">
                  {{ item.deduct }}
                </TableCell>
                <TableCell class="whitespace-normal text-muted-foreground">
                  {{ item.ruleVersionId }}
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>

        <DialogFooter>
          <Button variant="outline" @click="snapshotOpen = false">
            关闭
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <p class="flex items-center gap-2 text-xs text-muted-foreground">
      <BookTextIcon class="size-3.5" />
      规则变更只追加版本行；「详情」里可以核对该规则每一版的判定参数。
    </p>
  </div>
</template>
