<script setup lang="ts">
/**
 * 权限与审计：谁在什么时候看了什么 —— 全部留痕，不可篡改。
 *
 * 关键设计：原件默认不可见（OPERATOR 只看脱敏文本）；ADMIN 需要时走临时授权 + 全量留痕。
 * 本页只读：账号与角色的变更入口在「账号管理」与「策略设置」，这里不提供写操作。
 */
import type { ColumnDef } from '@tanstack/vue-table'
import { ShieldCheckIcon } from '@lucide/vue'
import { h, onMounted, ref } from 'vue'
import { toast } from 'vue-sonner'
import { fetchAuditLogs } from '@/api/admin'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import DataTablePagination from '@/components/DataTablePagination.vue'
import Hint from '@/components/Hint.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import {
  AUDIT_ACTION_TEXT,
  AUDIT_RESULT_TEXT,
  AUDIT_RESULT_VARIANT,
  AUDIT_TARGET_TEXT,
  labelOf,
  optionsOf,
  variantOf,
} from '@/lib/enums'
import { useUserStore } from '@/stores/user'
import type { AuditItem } from '@/api/admin'

const userStore = useUserStore()

/** 真实审计日志：动作、目标、结果与元数据；不含对话原文与签名链接 */
const logs = ref<AuditItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
/** 下拉里用中文，提交的仍是动作码；ALL 是「全部」的哨兵值（reka-ui 不接受空字符串选项） */
const actionFilter = ref('ALL')
const actionOptions = optionsOf(AUDIT_ACTION_TEXT)
const loading = ref(false)

const canReadAudit = userStore.role === 'ADMIN'

async function load() {
  const token = userStore.token
  if (!token || !canReadAudit) {
    return
  }
  loading.value = true
  try {
    const result = await fetchAuditLogs(token, {
      page: page.value,
      pageSize: pageSize.value,
      action: actionFilter.value === 'ALL' ? '' : actionFilter.value,
    })
    logs.value = result.items
    total.value = result.total
  }
  catch (e) {
    logs.value = []
    total.value = 0
    toast.error(e instanceof ApiError ? e.message : '审计日志加载失败')
  }
  finally {
    loading.value = false
  }
}

interface RoleRow {
  role: string
  aggregate: string
  ops: string
  audio: string
  identity: string
  exportable: string
  investor: string
}

/** 与 AdminRecordingController.AUDIO_TTL_MINUTES 对齐；具体链接以接口 expiresAt 为准。 */
const AUDIO_LINK_TTL_MINUTES = 15
const rows: RoleRow[] = [
  { role: '投资人（INVESTOR）', aggregate: '✓', ops: '✕', audio: '✕', identity: '✕', exportable: '✕', investor: '✓' },
  { role: '运营（OPERATOR）', aggregate: '✓', ops: '✓（脱敏）', audio: '✕', identity: '掩码', exportable: '✕', investor: '✓' },
  { role: '管理员（ADMIN）', aggregate: '✓', ops: '✓', audio: '✓（签发留痕）', identity: '原文', exportable: '✓', investor: '✓' },
]

const columns: { key: keyof Omit<RoleRow, 'role'>, label: string }[] = [
  { key: 'aggregate', label: '聚合指标' },
  { key: 'ops', label: '运营明细' },
  { key: 'audio', label: '录音原件' },
  { key: 'identity', label: '用户标识' },
  { key: 'exportable', label: '导出' },
  { key: 'investor', label: '投资人视图' },
]

function shortTime(value: string | null) {
  return value ? value.slice(5, 16).replace('T', ' ') : '—'
}

const logColumns: ColumnDef<AuditItem>[] = [
  {
    id: 'createdAt',
    header: '时间',
    cell: ({ row }) => shortTime(row.original.createdAt),
  },
  {
    id: 'actor',
    header: '操作人',
    cell: ({ row }) => `${row.original.actorDomain === 'ADMIN' ? '后台' : '用户'} #${row.original.actorId ?? '—'}`,
  },
  {
    accessorKey: 'action',
    header: '动作',
    cell: ({ row }) => labelOf(AUDIT_ACTION_TEXT, row.original.action),
  },
  {
    id: 'target',
    header: '对象',
    cell: ({ row }) => `${labelOf(AUDIT_TARGET_TEXT, row.original.targetType)} #${row.original.targetId ?? '—'}`,
  },
  {
    id: 'result',
    header: '结果',
    cell: ({ row }) => h(
      Badge,
      {
        variant: variantOf(AUDIT_RESULT_VARIANT, row.original.result),
        size: 'chip',
      },
      () => labelOf(AUDIT_RESULT_TEXT, row.original.result),
    ),
  },
]

onMounted(load)
</script>

<template>
  <div class="flex flex-col gap-4">
    <PageHeader
      title="权限与审计"
      description="谁在什么时候看了什么 —— 全部留痕，不可篡改"
    >
      <template #actions>
        <span class="text-sm text-muted-foreground">共 {{ total }} 条记录</span>
      </template>
    </PageHeader>

    <Panel title="角色与权限矩阵" note="静态口径：与服务端 @RequireRole 一一对应，不是实时数据">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border text-left text-xs text-muted-foreground">
            <th class="py-2 pr-2 font-medium">
              角色
            </th>
            <th
              v-for="column in columns"
              :key="column.key"
              class="py-2 pr-2 font-medium"
            >
              {{ column.label }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.role"
            class="border-b border-border last:border-b-0"
          >
            <td class="py-2 pr-2 font-medium">
              {{ row.role }}
            </td>
            <td
              v-for="column in columns"
              :key="column.key"
              class="py-2 pr-2 text-muted-foreground"
            >
              {{ row[column.key] }}
            </td>
          </tr>
        </tbody>
      </table>

      <Hint>
        <b>设计原则：</b>原件默认不可见：运营（OPERATOR）只看脱敏文本，管理员（ADMIN）需要时走「临时授权 + 全量留痕」（链接默认 {{ AUDIO_LINK_TTL_MINUTES }} 分钟过期，以签发接口返回时间为准）。图片资产允许 ADMIN/OPERATOR 查看；投资人无个体资产权限。
        这条限制会牺牲一些排障效率，但它换来的是「每一次调取都可被审计」这个可以被检查的承诺。
      </Hint>
    </Panel>

    <Panel flush>
      <template #default>
        <!--
          筛选行：标题在左、筛选在右。两个不能用直觉写的坑：
          1. form 必须自己拿到确定宽度（flex-1）：它的孩子 FieldGroup 自带 w-full，
             而 auto 宽度的 flex item + 百分比子元素会塌成 0px（筛查栏被挤成竖排并溢出卡片）；
          2. Field 的宽度要用包裹层定，不能写 Field class="w-56" —— Field 自带的 w-full
             在构建产物里排在 .w-56 之后，会把固定宽度盖掉。
        -->
        <div class="flex flex-wrap items-end justify-between gap-3 px-5 pt-4">
          <div class="min-w-0">
            <h3 class="font-heading text-base font-semibold tracking-[1px]">
              近期审计日志
            </h3>
            <p class="mt-1 text-xs text-muted-foreground">
              仅记录，不可编辑。账号与角色的变更入口在「账号管理」页，本页只读。
            </p>
          </div>
          <form
            class="flex flex-1 flex-wrap items-end justify-end gap-3"
            @submit.prevent="page = 1; load()"
          >
            <FieldGroup class="flex flex-row flex-wrap items-end justify-end gap-3">
              <div class="w-56 shrink-0">
                <Field>
                  <FieldLabel for="audit-action">
                    动作
                  </FieldLabel>
                  <Select v-model="actionFilter">
                    <SelectTrigger id="audit-action" class="w-full">
                      <SelectValue placeholder="全部动作" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ALL">
                        全部动作
                      </SelectItem>
                      <SelectItem
                        v-for="option in actionOptions"
                        :key="option.value"
                        :value="option.value"
                      >
                        {{ option.label }}
                      </SelectItem>
                    </SelectContent>
                  </Select>
                </Field>
              </div>
              <Button type="submit" :disabled="loading || !canReadAudit">
                {{ loading ? '查询中…' : '查询' }}
              </Button>
            </FieldGroup>
          </form>
        </div>

        <div class="px-5 py-4">
          <DataTable
            :columns="logColumns"
            :data="logs"
            :loading="loading"
            :empty-text="canReadAudit ? '还没有审计记录' : '当前角色无权查看审计明细'"
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
      </template>
    </Panel>

    <p class="flex items-center gap-2 text-xs text-muted-foreground">
      <ShieldCheckIcon class="size-3.5" />
      审计日志不含对话原文与签名链接；原件调取只记录「链接已签发」这一事实。
    </p>
  </div>
</template>
