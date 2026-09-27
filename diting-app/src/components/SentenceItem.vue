<script setup lang="ts">
/**
 * 质检记录里的单条判读结果：时间戳 + 说话人 + 原文（按服务端给的高亮区间切分）+ 命中规则名。
 * 高亮用原文 UTF-16 左闭右开区间，前端只做切片渲染，**不渲染任何 HTML**（服务端也不生成 HTML）。
 * 命中只报规则名：依据是原文里的高亮片段，规则库的匹配词不外显（见 utils/reportNotes）。
 */
import { computed } from 'vue'
import type { Sentence } from '@/types/api'
import { formatClock } from '@/utils/format'
import { displaySentenceNote } from '@/utils/reportNotes'

const props = defineProps<{ sentence: Sentence, sourceLabel?: string }>()

const speakerLabel = computed(() => {
  const { speakerId, sourceAssetId } = props.sentence
  if (sourceAssetId != null) {
    return props.sourceLabel ?? '图片原件'
  }
  return speakerId === null || speakerId === undefined ? '说话人未知' : `说话人 ${speakerId}`
})

/** 把命中区间切成「普通 / 高亮」片段；区间非法时退化为整段普通文本，绝不瞎切 */
const parts = computed(() => {
  const { text, highlights } = props.sentence
  const valid = (highlights ?? [])
    .filter(range => range.start >= 0 && range.end <= text.length && range.start < range.end)
    .sort((a, b) => a.start - b.start)
  if (valid.length === 0) {
    return [{ text, hit: false }]
  }
  const segments: { text: string, hit: boolean }[] = []
  let cursor = 0
  for (const range of valid) {
    if (range.start < cursor) {
      continue
    }
    if (range.start > cursor) {
      segments.push({ text: text.slice(cursor, range.start), hit: false })
    }
    segments.push({ text: text.slice(range.start, range.end), hit: true })
    cursor = range.end
  }
  if (cursor < text.length) {
    segments.push({ text: text.slice(cursor), hit: false })
  }
  return segments
})
</script>

<template>
  <view class="find">
    <text class="f-ts">{{ sentence.sourceAssetId != null ? speakerLabel : `${formatClock(sentence.startMs)} · ${speakerLabel}` }}</text>
    <text class="f-q">
      <text v-for="(part, index) in parts" :key="index" :class="{ 'f-hl': part.hit }">{{ part.text }}</text>
    </text>
    <text v-for="(note, index) in sentence.notes" :key="`n-${index}`" class="f-note">{{ displaySentenceNote(note) }}</text>
  </view>
</template>
