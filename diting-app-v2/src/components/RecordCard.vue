<script setup lang="ts">
/**
 * 「听过的话」列表项。
 * 三种视觉状态对应定稿：要留意（暖橙）+ 判读中（灰）+ 没发现（柔青）。
 */
import { computed } from 'vue'
import { isRunning as taskRunning } from '@/utils/taskStatus'
import { taskSummary } from '@/utils/taskSummary'
import type { TaskItem } from '@/types/api'
import { formatDateTime, formatDuration } from '@/utils/format'
import waveWarm from '@/static/icons/wave-warm.png'
import clockInk from '@/static/icons/clock-ink.png'
import checkSage from '@/static/icons/check-sage.png'

const props = defineProps<{ record: TaskItem }>()
// longpress 必须显式声明并 emit：否则父页面的 @longpress 收不到事件，单条删除入口形同虚设
const emit = defineEmits<{ open: [id: number], longpress: [id: number] }>()

const isRunning = computed(() => taskRunning(props.record.status))

const summary = computed(() => taskSummary(props.record))
const variant = computed(() => summary.value.variant)

const icon = computed(() => {
  if (variant.value === 'pass') {
    return checkSage
  }
  return isRunning.value ? clockInk : waveWarm
})

const pillText = computed(() => summary.value.text)

function open() {
  emit('open', props.record.id)
}

function pressLong() {
  emit('longpress', props.record.id)
}
</script>

<template>
  <view class="rec" @click="open" @longpress="pressLong">
    <view class="rec-ic" :class="variant">
      <image class="rec-ic-img" :src="icon" mode="widthFix" />
    </view>
    <view class="rec-mid">
      <text class="rec-ti">{{ formatDateTime(record.createdAt) }}</text>
      <text class="rec-mt">{{ formatDuration(record.durationSec) }}</text>
      <text
        class="pill"
        :class="{ 'pill-pass': variant === 'pass', 'pill-run': variant === 'run' }"
      >
        {{ pillText }}
      </text>
    </view>
    <text class="dots3">···</text>
  </view>
</template>

<style scoped>
.rec-ic-img {
  width: 21px;
  height: 21px;
}
</style>
