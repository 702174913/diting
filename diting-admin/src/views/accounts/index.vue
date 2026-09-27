<script setup lang="ts">
/**
 * 账号管理（开发文档 §7.3、§9）。
 *
 * 只对 ADMIN 开放：后端 @RequireRole 是 ADMIN only，前端菜单也按此隐藏。
 * 删除是「注销」（软删除）：账号与审计日志强绑定，删行会让审计失去引用，
 * 所以标记 deleted_at + 停用 + 用户名脱敏，行保留。默认不显示已注销账号，
 * 需要核对历史操作时用「显示已注销」开关带出来。
 */
import type { ColumnDef } from '@tanstack/vue-table'
import { EyeIcon, PencilIcon, PlusIcon, UserRoundXIcon, UsersIcon } from '@lucide/vue'
import { h, onMounted, ref } from 'vue'
import { toast } from 'vue-sonner'
import AccountEditorDialog from './account-editor-dialog.vue'
import { deleteAccount, fetchAccounts } from '@/api/admin'
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
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { ADMIN_ROLE_TEXT as ROLE_LABELS } from '@/lib/enums'
import { useUserStore } from '@/stores/user'
import type { AdminAccountItem } from '@/api/admin'

const userStore = useUserStore()

const items = ref<AdminAccountItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const roleFilter = ref('ALL')
const includeDeleted = ref(false)
const loading = ref(false)

const editorOpen = ref(false)
const editingAccount = ref<AdminAccountItem | null>(null)
const deleteOpen = ref(false)
const deletingAccount = ref<AdminAccountItem | null>(null)
const deleting = ref(false)

const isAdmin = userStore.role === 'ADMIN'

function roleLabel(value: string) {
  return ROLE_LABELS[value] ?? value
}

function shortTime(value: string | null) {
  return value ? value.slice(0, 16).replace('T', ' ') : '—'
}

async function load() {
  const token = userStore.token
  if (!token) {
    return
  }
  loading.value = true
  try {
    const result = await fetchAccounts(token, {
      page: page.value,
      pageSize: pageSize.value,
      role: roleFilter.value === 'ALL' ? undefined : roleFilter.value,
      includeDeleted: includeDeleted.value || undefined,
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
  editingAccount.value = null
  editorOpen.value = true
}

function startEdit(account: AdminAccountItem) {
  editingAccount.value = account
  editorOpen.value = true
}

function askDelete(account: AdminAccountItem) {
  deletingAccount.value = account
  deleteOpen.value = true
}

async function confirmDelete() {
  const token = userStore.token
  const account = deletingAccount.value
  if (!token || !account || deleting.value) {
    return
  }
  deleting.value = true
  try {
    await deleteAccount(token, account.id)
    toast.success('已注销：账号被停用、用户名已脱敏，审计记录保留')
    deleteOpen.value = false
    deletingAccount.value = null
    await load()
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '注销失败')
  }
  finally {
    deleting.value = false
  }
}

const columns: ColumnDef<AdminAccountItem>[] = [
  { accessorKey: 'username', header: '用户名' },
  { accessorKey: 'nickname', header: '昵称' },
  {
    id: 'role',
    header: '角色',
    cell: ({ row }) => roleLabel(row.original.role),
  },
  {
    id: 'state',
    header: '状态',
    cell: ({ row }) => {
      if (row.original.deletedAt) {
        return h(Badge, { variant: 'secondary', size: 'chip' }, () => '已注销')
      }
      return h(
        Badge,
        { variant: row.original.enabled ? 'up' : 'warn', size: 'chip' },
        () => (row.original.enabled ? '启用' : '已禁用'),
      )
    },
  },
  {
    id: 'createdAt',
    header: '创建时间',
    cell: ({ row }) => shortTime(row.original.createdAt),
  },
  {
    id: 'deletedAt',
    header: '注销时间',
    cell: ({ row }) => shortTime(row.original.deletedAt),
  },
  {
    id: 'actions',
    header: '',
    cell: ({ row }) => h(
      'div',
      { class: 'flex items-center justify-end gap-1' },
      row.original.deletedAt
        ? [
            h(Badge, { variant: 'secondary', size: 'chip' }, () => '仅供审计追溯'),
          ]
        : [
            h(Button, { variant: 'ghost', size: 'sm', onClick: () => startEdit(row.original) }, () => [
              h(PencilIcon, { 'data-icon': 'inline-start' }),
              '修改',
            ]),
            h(Button, { variant: 'ghost', size: 'sm', onClick: () => askDelete(row.original) }, () => [
              h(UserRoundXIcon, { 'data-icon': 'inline-start' }),
              '注销',
            ]),
          ],
    ),
  },
]

onMounted(load)
</script>

<template>
  <div class="flex flex-col gap-4">
    <PageHeader
      title="账号管理"
      description="后台账号的增改与注销。注销是软删除：账号与审计强绑定，行保留可追溯。"
    >
      <template #actions>
        <span class="text-sm text-muted-foreground">共 {{ total }} 个</span>
        <Button v-if="isAdmin" @click="startCreate">
          <PlusIcon data-icon="inline-start" />
          新增账号
        </Button>
      </template>
    </PageHeader>

    <EmptyState
      v-if="!isAdmin"
      title="当前角色无权管理账号"
      description="账号管理仅对 ADMIN 开放（服务端同样会返回 403）。"
      :icon="UsersIcon"
    />

    <template v-else>
      <Panel flush>
        <form
          class="flex flex-wrap items-end gap-3 px-5 pt-4"
          @submit.prevent="page = 1; load()"
        >
          <FieldGroup class="flex flex-row flex-wrap items-end gap-4">
            <Field class="w-48">
              <FieldLabel for="account-role">
                角色
              </FieldLabel>
              <Select v-model="roleFilter" @update:model-value="page = 1; load()">
                <SelectTrigger id="account-role" class="w-full">
                  <SelectValue placeholder="全部角色" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="ALL">
                      全部角色
                    </SelectItem>
                    <SelectItem
                      v-for="(label, value) in ROLE_LABELS"
                      :key="value"
                      :value="value"
                    >
                      {{ label }}
                    </SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <Field orientation="horizontal" class="w-auto">
              <FieldLabel for="account-include-deleted">
                显示已注销
              </FieldLabel>
              <Switch
                id="account-include-deleted"
                v-model="includeDeleted"
                @update:model-value="page = 1; load()"
              />
            </Field>
            <Button type="submit" :disabled="loading">
              {{ loading ? '查询中…' : '查询' }}
            </Button>
          </FieldGroup>
        </form>

        <div class="px-5 py-4">
          <DataTable
            :columns="columns"
            :data="items"
            :loading="loading"
            empty-text="没有符合条件的账号"
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
        <b>为什么注销而不是删除：</b>审计日志的 actor_id 指向 admin_users，删行会让「每一次调取都可被审计」失去引用。
        注销会标记 deleted_at、停用账号、把用户名改为 <code>deleted#&lt;id&gt;</code> 并随机化密码 ——
        即使有人在库里手工把 deleted_at 改回 NULL，原密码也不能登录，必须走重置。
        同时系统始终要求至少保留一个启用的 ADMIN。
      </Hint>

      <p class="flex items-center gap-2 text-xs text-muted-foreground">
        <EyeIcon class="size-3.5" />
        已注销账号默认隐藏；打开「显示已注销」可核对注销时间与脱敏后的用户名。
      </p>
    </template>

    <AccountEditorDialog
      v-model:open="editorOpen"
      :account="editingAccount"
      @saved="load()"
    />

    <AlertDialog v-model:open="deleteOpen">
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>注销账号</AlertDialogTitle>
          <AlertDialogDescription>
            注销《{{ deletingAccount?.nickname || deletingAccount?.username }}》？
            账号会立即被停用、用户名脱敏、密码随机化，且不能再登录；审计记录保留可查。
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel :disabled="deleting">
            取消
          </AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            :disabled="deleting"
            @click="confirmDelete"
          >
            {{ deleting ? '注销中…' : '确认注销' }}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  </div>
</template>
