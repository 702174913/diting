<script setup lang="ts">
/** 已发布报告概要只负责展示；发布门禁由父页决定。 */
import { computed } from 'vue'
import type { ReportDetail } from '@/types/api'
import { formatDuration } from '@/utils/format'
import waveWarm from '@/static/icons/wave-warm.png'

const props = defineProps<{
  report: ReportDetail
  headline: string
  versionLabel: string
}>()
const flaggedCount = computed(() => props.report.flaggedCount)
const sentenceCount = computed(() => props.report.sentenceCount ?? props.report.sentences.length)
</script>

<template>
  <view class="rep">
    <view class="rep-top">
      <view class="rep-badge">
        <image class="rep-badge-img" :src="waveWarm" mode="widthFix" />
      </view>
      <view class="rep-head">
        <text class="rep-num">{{ headline }}</text>
        <text class="rep-sub">编号 {{ report.reportNo }} · {{ formatDuration(report.durationSec) }}</text>
      </view>
    </view>
    <view class="rep-stats">
      <view class="rs">
        <text class="rs-b">{{ flaggedCount }}</text>
        <text class="rs-s">要留意</text>
      </view>
      <view class="rs">
        <text class="rs-b">{{ sentenceCount }}</text>
        <text class="rs-s">听完的句子</text>
      </view>
      <view v-if="report.riskScore !== null" class="rs">
        <text class="rs-b">{{ report.riskScore }}</text>
        <text class="rs-s">风险分（阈值 {{ report.threshold }}）</text>
      </view>
      <view v-if="report.violationCount > flaggedCount" class="rs">
        <text class="rs-b">{{ report.violationCount }}</text>
        <text class="rs-s">含缺失项</text>
      </view>
    </view>
    <text v-if="report.riskFlagged" class="rep-flag">本通电话的判读结果已达到风险阈值</text>
    <text v-if="report.reportVersion" class="rep-sub">
      第 {{ report.reportVersion }} 版 · {{ versionLabel }}
    </text>
  </view>
</template>

<style scoped>
.rep-badge-img {
  width: 24px;
  height: 24px;
}

.rep-head {
  flex: 1;
  min-width: 0;
}

.rep-flag {
  display: block;
  margin-top: 10px;
  font-size: 12px;
  color: var(--warn, #C8A45C);
}
</style>
