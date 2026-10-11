<script setup lang="ts">
/**
 * 判读中 / 判读完成卡片。
 * 定稿里它是「有人在替你听」这件事的可视化：百分比、进度条、以及逐句浮现的转写。
 * 逐句内容只在服务端给出部分结果时渲染，不预置假文本。
 */
import { computed } from 'vue'
import type { TaskStatus } from '@/types/api'
import { formatDuration } from '@/utils/format'

export interface ProgressLine {
  ts: string
  before: string
  hit?: string
  after?: string
}

const props = withDefaults(defineProps<{
  status: TaskStatus
  /** 服务端给不出百分比时为 null：宁可不显示，也不写死 0% 冒充进度 */
  progress?: number | null
  durationSec?: number
  lines?: ProgressLine[]
}>(), {
  progress: null,
  durationSec: 0,
  lines: () => [],
})

const percent = computed(() => (
  typeof props.progress === 'number' ? Math.min(100, Math.max(0, Math.round(props.progress))) : null
))

const caption = computed(() => {
  if (props.status === 'COMPLETED') {
    return '已经听完了'
  }
  if (props.status === 'FAILED' || props.status === 'INVALID') {
    return '这段没听成'
  }
  if (props.status === 'QUEUED' || props.status === 'UPLOADED') {
    return '排队中，马上开始'
  }
  return '正在一句一句听'
})

const subtitle = computed(() => {
  const size = formatDuration(props.durationSec)
  if (props.status === 'COMPLETED') {
    return `${size} · 已听完`
  }
  if (props.status === 'FAILED' || props.status === 'INVALID') {
    return `${size} · 可以重新上传一次`
  }
  return size
})
</script>

<template>
  <view class="proc">
    <view class="proc-top">
      <view>
        <text class="proc-cap">{{ caption }}</text>
        <text class="proc-sub">{{ subtitle }}</text>
      </view>
      <text v-if="percent !== null" class="proc-pct">{{ percent }}%</text>
    </view>
    <view v-if="percent !== null" class="track">
      <view class="track-i" :style="{ width: `${percent}%` }" />
    </view>
    <view v-if="lines.length" class="lines">
      <view v-for="line in lines" :key="line.ts" class="lines-ln">
        <text class="lines-ts">{{ line.ts }}</text>
        <text>{{ line.before }}</text><text v-if="line.hit" class="lines-hl">{{ line.hit }}</text><text>{{ line.after }}</text>
      </view>
    </view>
  </view>
</template>
