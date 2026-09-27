<script setup lang="ts">
/**
 * 策略设置（开发文档 §4.2、§7.3）。
 *
 * 页面只读展示三项配置与当前版本；修改走弹窗（policy-dialog）。
 * **读取失败时禁止修改**：拿不到真实配置时若允许保存，默认值会把线上配置覆盖掉 ——
 * 这条不是 UX 偏好，是数据安全问题，所以读失败时按钮直接禁用；失败原因走 toast 提示，不在页面展示。
 */
import { PencilIcon, SlidersHorizontalIcon } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import { toast } from 'vue-sonner'
import PolicyDialog from './policy-dialog.vue'
import { fetchConfig } from '@/api/admin'
import { ApiError } from '@/api/request'
import EmptyState from '@/components/EmptyState.vue'
import Hint from '@/components/Hint.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { useUserStore } from '@/stores/user'
import type { PolicyConfig } from '@/api/admin'

const userStore = useUserStore()

const config = ref<PolicyConfig | null>(null)
const loaded = ref(false)
/** 只做“是否失败”门控（禁用保存/切换空态），失败文案走 toast，不在页面展示 */
const loadError = ref(false)
const dialogOpen = ref(false)

const isAdmin = computed(() => userStore.role === 'ADMIN')
const canEdit = computed(() => loaded.value && !loadError.value && isAdmin.value)

const rows = computed(() => [
  {
    label: '每日免费份数',
    value: config.value ? String(config.value.dailyFreeQuota) : '—',
    note: '0 表示没有免费额度，不是无限。',
  },
  {
    label: '风险阈值',
    value: config.value ? String(config.value.riskThreshold) : '—',
    note: '风险分达到或超过该值即标记为风险（0—100）。',
  },
  {
    label: '最长录音时长',
    value: config.value ? `${config.value.maxRecordingMinutes} 分钟` : '—',
    note: '超过该时长的录音在提交阶段就会被拒。',
  },
])

async function load() {
  const token = userStore.token
  if (!token) {
    return
  }
  loadError.value = false
  try {
    config.value = await fetchConfig(token)
    loaded.value = true
  }
  catch (e) {
    loaded.value = false
    config.value = null
    loadError.value = true
    toast.error(e instanceof ApiError ? e.message : '配置读取失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="flex flex-col gap-4">
    <PageHeader
      title="策略设置"
      description="每日免费份数、风险阈值、最长录音时长。三项作为同一版本保存，保存后立即对新提交生效。"
    >
      <template #actions>
        <Badge variant="secondary" size="chip">
          当前版本 v{{ config?.version ?? '—' }}
        </Badge>
        <Button
          :disabled="!canEdit"
          @click="dialogOpen = true"
        >
          <PencilIcon data-icon="inline-start" />
          修改策略
        </Button>
      </template>
    </PageHeader>

    <Panel title="当前策略" note="三项同一版本快照">
      <div v-if="!loaded && !loadError" class="flex flex-col gap-3">
        <Skeleton class="h-5 w-2/3" />
        <Skeleton class="h-5 w-1/2" />
        <Skeleton class="h-5 w-1/2" />
      </div>

      <div v-else class="flex flex-col">
        <template v-for="(row, index) in rows" :key="row.label">
          <Separator v-if="index > 0" class="my-3" />
          <div class="flex flex-wrap items-baseline gap-x-4 gap-y-1">
            <span class="w-32 shrink-0 text-sm text-muted-foreground">{{ row.label }}</span>
            <span class="font-heading text-lg">{{ row.value }}</span>
            <span class="text-xs text-muted-foreground">{{ row.note }}</span>
          </div>
        </template>
      </div>

      <EmptyState
        v-if="!loaded && loadError"
        title="没有可展示的配置"
        description="重新读取成功后才能看到当前生效值。"
        :icon="SlidersHorizontalIcon"
      >
        <Button variant="outline" size="sm" @click="load()">
          重新读取
        </Button>
      </EmptyState>
    </Panel>

    <Hint>
      <b>为什么读取失败时不允许保存：</b>{{ ' ' }}
      三项配置是同一版本快照，保存请求带 expectedVersion。如果读失败还允许保存，
      界面上的「默认值」就会带着一个假版本号写回去，把真实配置顶掉 ——
      这类事故看起来只是几个数字变了，实际会改变所有新提交的额度与风险判定。
      只读角色可以查看但不能保存（服务端同样会 403）。
    </Hint>

    <PolicyDialog
      v-model:open="dialogOpen"
      :config="config"
      @saved="load()"
    />
  </div>
</template>
