<script setup lang="ts">
/** 纯展示导出入口：只发送打包选项，作业复用、轮询和签名仍由父页管理。 */
import downloadWhite from '@/static/icons/download-white.png'

defineProps<{
  waitingReport: boolean
  exporting: boolean
  originalsAvailable: boolean
}>()
const emit = defineEmits<{
  save: [withAudio: boolean]
}>()
</script>

<template>
  <view class="actions">
    <button
      class="btn btn-main btn-block"
      :disabled="exporting || waitingReport"
      @click="emit('save', false)"
    >
      <image class="btn-ic" :src="downloadWhite" mode="widthFix" />
      <text>导出 PDF</text>
    </button>
    <button
      class="btn btn-soft btn-block btn-gap"
      :disabled="exporting || waitingReport || !originalsAvailable"
      @click="emit('save', true)"
    >
      <text>连同原件一起打包（录音 / 图片）</text>
    </button>
    <text v-if="!originalsAvailable" class="hint">原件已过保留期或暂不可用，无法连同打包。</text>
  </view>
</template>

<style scoped>
.actions {
  padding: 6px 20px 0;
}

.btn-ic {
  width: 18px;
  height: 18px;
}

.btn-gap {
  margin-top: 10px;
}

.hint {
  display: block;
  margin-top: 10px;
  font-size: 12px;
  color: var(--ink-3);
}
</style>
