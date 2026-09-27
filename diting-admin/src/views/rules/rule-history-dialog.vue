<script setup lang="ts">
/**
 * 规则详情 + 版本历史。
 *
 * 版本表（audit_rule_versions）每次修改插一行、永不改写，所以「历史报告按哪一版判读」可复算。
 * 唯一回溯不了的是启用状态：enabled 在 audit_rules 上，表结构本就不对状态做版本化。
 */
import { HistoryIcon } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { toast } from 'vue-sonner'
import type { RuleItem } from '@/api/admin'
import { fetchRuleVersions } from '@/api/admin'
import { ApiError } from '@/api/request'
import EmptyState from '@/components/EmptyState.vue'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableEmpty,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { RULE_SCOPE_TEXT as ROLE_LABELS, MATCH_TYPE_TEXT as TYPE_LABELS } from '@/lib/enums'
import { ROLE_TARGETING_ENABLED } from '@/lib/features'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  rule: RuleItem | null
}>()

const open = defineModel<boolean>('open', { required: true })

const userStore = useUserStore()
const versions = ref<RuleItem[]>([])
const loading = ref(false)

const currentVersion = computed(() => props.rule?.version ?? null)

function shortTime(value: string | undefined | null) {
  return value ? value.slice(0, 16).replace('T', ' ') : '—'
}

async function load(ruleId: number) {
  const token = userStore.token
  if (!token) {
    return
  }
  loading.value = true
  try {
    versions.value = await fetchRuleVersions(token, ruleId)
  }
  catch (e) {
    versions.value = []
    toast.error(e instanceof ApiError ? e.message : '版本历史加载失败')
  }
  finally {
    loading.value = false
  }
}

watch(open, (visible) => {
  if (visible && props.rule?.ruleId) {
    void load(props.rule.ruleId)
  }
  else if (!visible) {
    versions.value = []
  }
})
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="sm:max-w-4xl">
      <DialogHeader>
        <DialogTitle>{{ rule?.name ?? '规则详情' }} · 版本历史</DialogTitle>
        <DialogDescription>
          编码 {{ rule?.code }}｜共 {{ versions.length }} 个版本。历史版本不可删除、不可改写；启用状态是当前值，不随版本回溯。
        </DialogDescription>
      </DialogHeader>

      <div class="max-h-[60vh] overflow-y-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>版本</TableHead>
              <TableHead>名称</TableHead>
              <TableHead>分组</TableHead>
              <TableHead>类型</TableHead>
              <TableHead v-if="ROLE_TARGETING_ENABLED">
                角色
              </TableHead>
              <TableHead>扣分</TableHead>
              <TableHead>关键词</TableHead>
              <TableHead>时间</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <template v-if="loading">
              <TableRow v-for="rowIndex in 3" :key="`skeleton-${rowIndex}`">
                <TableCell v-for="cellIndex in (ROLE_TARGETING_ENABLED ? 8 : 7)" :key="`cell-${cellIndex}`">
                  <Skeleton class="h-4 w-20" />
                </TableCell>
              </TableRow>
            </template>
            <TableEmpty v-else-if="versions.length === 0" :colspan="ROLE_TARGETING_ENABLED ? 8 : 7">
              <span class="text-muted-foreground">还没有版本记录</span>
            </TableEmpty>
            <template v-else>
              <TableRow
                v-for="item in versions"
                :key="item.version"
              >
                <TableCell>
                  <Badge :variant="item.version === currentVersion ? 'gold' : 'secondary'" size="chip">
                    v{{ item.version }}{{ item.version === currentVersion ? ' · 当前' : '' }}
                  </Badge>
                </TableCell>
                <TableCell class="max-w-[14rem] whitespace-normal">
                  {{ item.name }}
                </TableCell>
                <TableCell class="whitespace-normal">
                  {{ item.groupName ?? '—' }}
                </TableCell>
                <TableCell>{{ TYPE_LABELS[item.matchType] ?? item.matchType }}</TableCell>
                <TableCell v-if="ROLE_TARGETING_ENABLED">
                  {{ ROLE_LABELS[item.roleScope] ?? item.roleScope }}
                </TableCell>
                <TableCell>{{ item.deduct }}</TableCell>
                <!-- 关键词列允许换行：TableCell 默认 nowrap，长列表会把「时间」列挤出可视区 -->
                <TableCell class="max-w-[16rem] whitespace-normal">
                  {{ (item.keywords ?? []).join('、') || '—' }}
                </TableCell>
                <TableCell class="whitespace-nowrap text-muted-foreground">
                  {{ shortTime(item.createdAt) }}
                </TableCell>
              </TableRow>
            </template>
          </TableBody>
        </Table>
      </div>

      <EmptyState
        v-if="!loading && versions.length === 0"
        title="没有可展示的版本"
        description="规则创建时会写入第一版。"
        :icon="HistoryIcon"
      />

      <DialogFooter>
        <Button variant="outline" @click="open = false">
          关闭
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
