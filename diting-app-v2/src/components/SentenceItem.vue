<script setup lang="ts">
/** 纯文本展示识别结果及原始 model_ids / hit_texts，不推断规则名称。 */
import { computed } from 'vue'
import type { Sentence } from '@/types/api'
import { formatClock } from '@/utils/format'
import { displaySentenceNote } from '@/utils/reportNotes'

const props = defineProps<{ sentence: Sentence, sourceLabel?: string }>()

const speakerLabel = computed(() => {
  const { speakerId, sourceAssetId, sourceKind, speaker } = props.sentence
  if (sourceKind === 'IMAGE') {
    return '图片识别文字'
  }
  if (speaker) {
    return `说话人 ${speaker}`
  }
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
    <text class="f-ts">{{ sentence.sourceAssetId != null || sentence.hasTiming === false ? speakerLabel : `${formatClock(sentence.startMs)} · ${speakerLabel}` }}</text>
    <text class="f-q">
      <text v-for="(part, index) in parts" :key="index" :class="{ 'f-hl': part.hit }">{{ part.text }}</text>
    </text>
    <text v-if="sentence.modelIds?.length" class="f-note">model_ids：{{ sentence.modelIds.join(', ') }}</text>
    <text v-if="sentence.hitTexts?.length" class="f-note">hit_texts：{{ sentence.hitTexts.join('、') }}</text>
    <text v-for="(note, index) in sentence.notes" :key="`n-${index}`" class="f-note">{{ displaySentenceNote(note) }}</text>
  </view>
</template>
