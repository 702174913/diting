<script setup lang="ts">
/**
 * 后台外壳：顶栏（视图模式 / 时间范围 / 当前账号）+ 侧栏（经营 · 业务 · 治理）。
 *
 * 投资人视图下，含个体数据的页面直接锁定 —— 这条限制是设计承诺的一部分，
 * 不是权限 bug，所以点击时明确给出反馈而不是静默无响应
 * （因此锁定项仍然可点，只是点了会亮红边框提示，不做 disabled）。
 *
 * 图标从手绘 SVG path 数组换成 lucide 组件；金色谛听徽标是品牌资产，保留手绘。
 */
import type { Component } from 'vue'
import {
  BookTextIcon,
  FileDownIcon,
  FileLockIcon,
  FileTextIcon,
  LayoutDashboardIcon,
  LogOutIcon,
  MicIcon,
  ScaleIcon,
  ShieldCheckIcon,
  SlidersHorizontalIcon,
  UsersIcon,
} from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ExportTaskDialog from '@/components/ExportTaskDialog.vue'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { cn } from '@/lib/utils'
import { useExportTaskStore } from '@/stores/exportTask'
import { useUserStore } from '@/stores/user'
import { type Range, type ViewMode, useViewStore } from '@/stores/view'

interface MenuItem {
  key: string
  title: string
  tag?: string
  icon: Component
}

const userStore = useUserStore()
const view = useViewStore()
const exportStore = useExportTaskStore()
const route = useRoute()
const router = useRouter()
const showExportDialog = ref(false)

const groups: { title: string, items: MenuItem[] }[] = [
  {
    title: '总览',
    items: [
      { key: 'overview', title: '总览看板', icon: LayoutDashboardIcon },
    ],
  },
  {
    title: '业务（含个体数据）',
    items: [
      { key: 'recordings', title: '录音资产', tag: '明细', icon: MicIcon },
    ],
  },
  {
    title: '治理',
    items: [
      { key: 'rules', title: '规则库', tag: '可写', icon: BookTextIcon },
      { key: 'legal-clauses', title: '条款库', tag: '可写', icon: ScaleIcon },
      { key: 'settings', title: '策略设置', icon: SlidersHorizontalIcon },
      { key: 'agreements', title: '协议配置', tag: '可写', icon: FileTextIcon },
      { key: 'audit', title: '权限与审计', tag: '敏感', icon: FileLockIcon },
      { key: 'accounts', title: '账号管理', tag: '可写', icon: UsersIcon },
    ],
  },
]

const ranges: { value: Range, label: string }[] = [
  { value: 'TODAY', label: '今日' },
  { value: '30D', label: '近 30 天' },
  { value: 'QUARTER', label: '本季度' },
]

const blocked = ref(false)
/**
 * 侧栏当前路由，用于高亮。
 * 注：曾有一个 LEGACY_PAGES 名单把旧样式表的裸元素规则按路由挂到 .legacy 作用域下，
 * 现在所有页面都已迁完，名单与 legacy.css 一起退场。
 */
const currentKey = computed(() => String(route.name ?? ''))

const roleText = computed(() => (
  view.isInvestor
    ? '投资人 · 聚合只读（不可导出）'
    : `${userStore.role === 'ADMIN' ? '技术管理员' : '运营负责人'} · 全量权限`
))

const avatarText = computed(() => (userStore.nickname || '陈').slice(0, 1))

/** 分段控件的选中值直接读写 store，选中态与锁定逻辑保持一致 */
const mode = computed({
  get: () => view.mode,
  set: (value: string) => {
    if (value === 'ops' || value === 'ir') {
      setMode(value)
    }
  },
})

const range = computed({
  get: () => view.range,
  set: (value: string) => {
    if (value === 'TODAY' || value === '30D' || value === 'QUARTER') {
      view.setRange(value)
    }
  },
})

function setMode(value: ViewMode) {
  view.setMode(value)
  // 切到投资人视图时，如果正停在锁定页，退回总览
  if (view.isLocked(currentKey.value)) {
    router.replace('/overview')
  }
}

function open(key: string) {
  if (view.isLocked(key)) {
    blocked.value = true
    setTimeout(() => {
      blocked.value = false
    }, 700)
    return
  }
  router.push(`/${key}`)
}

function logout() {
  exportStore.clear()
  userStore.clear()
  router.replace('/login')
}

onMounted(() => {
  // 刷新页面后把未完成作业的轮询接回来：否则「完成后自动下载」要等用户点开「导出任务」才开始
  void exportStore.refresh()
})
</script>

<template>
  <header class="sticky top-0 z-40 flex h-15 items-center gap-4 border-b border-border bg-card px-5">
    <div class="flex shrink-0 items-center gap-2.5">
      <div class="grid size-[30px] place-items-center rounded-full border border-gold-deep bg-gold/10 text-gold">
        <svg aria-hidden="true" fill="none" height="16" viewBox="0 0 40 40" width="16">
          <path d="M12 25c1.5-3.5 4.5-5.5 8-5.5s6.5 2 8 5.5c-2 1-4 1.5-8 1.5s-6-.5-8-1.5z" fill="currentColor" />
          <path d="M13.5 19.5c0-3.5 2-6 6.5-6s6.5 2.5 6.5 6" stroke="currentColor" stroke-linecap="round" stroke-width="1.2" />
        </svg>
      </div>
      <div>
        <div class="font-heading text-[17px] tracking-[3px]">
          谛听
        </div>
        <div class="-mt-0.5 text-[8px] tracking-[2.5px] text-gold">
          DÌ TĪNG
        </div>
      </div>
    </div>

    <span class="rounded-md border border-line-strong px-2.5 py-0.5 text-[11px] tracking-[1px] text-muted-foreground">
      管理后台
    </span>

    <div class="flex-1" />

    <ToggleGroup
      v-model="mode"
      type="single"
      variant="gold"
      size="compact"
      class="rounded-lg border border-border bg-surface-2 p-0.5"
      aria-label="视图模式"
    >
      <ToggleGroupItem value="ops">
        运营视图
      </ToggleGroupItem>
      <ToggleGroupItem value="ir">
        投资人视图
      </ToggleGroupItem>
    </ToggleGroup>

    <ToggleGroup
      v-model="range"
      type="single"
      variant="gold-outline"
      size="compact"
      spacing="2"
      aria-label="时间范围"
    >
      <ToggleGroupItem
        v-for="item in ranges"
        :key="item.value"
        :value="item.value"
      >
        {{ item.label }}
      </ToggleGroupItem>
    </ToggleGroup>

    <Button variant="outline" size="sm" @click="showExportDialog = true">
      <FileDownIcon data-icon="inline-start" />
      导出任务
      <Badge
        v-if="exportStore.pendingCount"
        variant="gold"
        size="chip"
        class="ml-1"
      >
        {{ exportStore.pendingCount }}
      </Badge>
    </Button>

    <div class="flex items-center gap-2.5 border-l border-border pl-3.5">
      <Avatar class="size-[30px] border border-line-strong bg-surface-3">
        <AvatarFallback class="text-xs text-gold">
          {{ avatarText }}
        </AvatarFallback>
      </Avatar>
      <div>
        <div class="text-[12.5px]">
          {{ userStore.nickname || '陈叙' }}
        </div>
        <div class="text-[10px] text-muted-foreground">
          {{ roleText }}
        </div>
      </div>
      <Button variant="outline" size="sm" @click="logout">
        <LogOutIcon data-icon="inline-start" />
        退出
      </Button>
    </div>
  </header>

  <div class="flex min-h-[calc(100vh-60px)]">
    <nav
      class="sticky top-15 h-[calc(100vh-60px)] w-53 shrink-0 overflow-y-auto border-r border-border bg-card px-2.5 py-4"
      aria-label="主导航"
    >
      <template v-for="group in groups" :key="group.title">
        <div class="px-3 pt-3.5 pb-2 text-[10px] tracking-[2px] text-muted-foreground">
          {{ group.title }}
        </div>
        <button
          v-for="item in group.items"
          :key="item.key"
          type="button"
          :aria-current="currentKey === item.key ? 'page' : undefined"
          :class="cn(
            'mb-0.5 flex min-h-9.5 w-full items-center gap-2.5 rounded-md px-3 py-[9px] text-left text-[13.5px] transition-colors',
            currentKey === item.key
              ? 'bg-surface-3 text-foreground'
              : 'text-foreground-soft hover:bg-surface-2 hover:text-foreground',
            view.isLocked(item.key) && 'cursor-not-allowed opacity-[0.42]',
          )"
          @click="open(item.key)"
        >
          <component :is="item.icon" class="size-4 shrink-0 opacity-85" />
          <span class="truncate">{{ item.title }}</span>
          <Badge
            v-if="item.tag"
            variant="secondary"
            size="chip"
            class="ml-auto"
          >
            {{ item.tag }}
          </Badge>
        </button>
      </template>
    </nav>

    <main class="min-w-0 flex-1 px-6.5 pt-5.5 pb-15">
      <div
        v-if="view.isInvestor"
        :class="cn(
          'mb-4.5 flex items-center gap-3 rounded-lg border border-gold-deep bg-linear-to-r from-gold/10 to-transparent px-4 py-3 transition-colors',
          blocked && 'border-destructive',
        )"
      >
        <ShieldCheckIcon class="size-5 shrink-0 text-gold" />
        <div>
          <div class="text-[13px]">
            <b>投资人视图 · 聚合脱敏</b>
          </div>
          <div class="text-xs text-muted-foreground">
            本视图仅呈现聚合指标与单位经济模型，不包含任何单用户、单条录音或可识别信息；录音资产明细、权限审计、账号管理与协议配置已锁定。数据口径与运营视图一致，仅呈现层不同。
          </div>
        </div>
      </div>

      <router-view />

      <div class="mt-8.5 border-t border-border pt-4 text-[11px] leading-[1.9] text-muted-foreground">
        谛听 · 后台管理端原型 ｜ 数据为演示样本<br>
        设计原则：运营视图追求颗粒度，投资人视图追求可信度，两者共用同一数据口径但呈现层隔离；个体数据在任何视图中均不可导出。
      </div>
    </main>
  </div>

  <ExportTaskDialog v-model:open="showExportDialog" />
</template>
