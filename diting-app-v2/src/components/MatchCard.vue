<script setup lang="ts">
/** 问题清单条目：直接展示 match_result / picture_match_results 的命中原文、规则与法条。 */
import { computed } from 'vue'
import RuleList from '@/components/RuleList.vue'
import type { MatchItem } from '@/types/api'

const props = defineProps<{ match: MatchItem }>()

const speakerLabel = computed(() => props.match.source === 'IMAGE'
  ? '图片识别文字'
  : `说话人 ${props.match.speaker || '未知'}`)

const parts = computed(() => {
  const { content, hitTexts } = props.match
  const ranges: { start: number, end: number }[] = []
  for (const hit of hitTexts) {
    if (!hit) {
      continue
    }
    let start = content.indexOf(hit)
    while (start >= 0) {
      ranges.push({ start, end: start + hit.length })
      start = content.indexOf(hit, start + hit.length)
    }
  }
  const merged: { start: number, end: number }[] = []
  for (const range of ranges.sort((a, b) => a.start - b.start)) {
    const previous = merged[merged.length - 1]
    if (previous && range.start <= previous.end) {
      previous.end = Math.max(previous.end, range.end)
    }
    else {
      merged.push({ ...range })
    }
  }
  if (merged.length === 0) {
    return [{ text: content, hit: false }]
  }
  const segments: { text: string, hit: boolean }[] = []
  let cursor = 0
  for (const range of merged) {
    if (range.start > cursor) {
      segments.push({ text: content.slice(cursor, range.start), hit: false })
    }
    segments.push({ text: content.slice(range.start, range.end), hit: true })
    cursor = range.end
  }
  if (cursor < content.length) {
    segments.push({ text: content.slice(cursor), hit: false })
  }
  return segments
})
</script>

<template>
  <view class="find">
    <text class="f-ts">{{ speakerLabel }}</text>
    <text class="f-q">
      <text v-for="(part, index) in parts" :key="index" :class="{ 'f-hl': part.hit }">{{ part.text }}</text>
    </text>
    <RuleList :rules="match.rules" />
  </view>
</template>
