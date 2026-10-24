<script setup lang="ts">
/** 文件选择、预览与同意弹层只负责界面；幂等键/上传会话/受理状态始终留在父页。 */
import { computed } from 'vue'
import ConsentSheet from '@/components/ConsentSheet.vue'
import type { ChosenFile } from '@/utils/choose'
import { MAX_IMAGES, isImage } from '@/utils/choose'
import markOrb from '@/static/icons/mark-orb.png'

const props = defineProps<{
  busy: boolean
  uploadStage: string
  uploadPercent: number | null
  pendingFiles: ChosenFile[]
  sheetVisible: boolean
  pendingSummary: string
}>()
const emit = defineEmits<{
  chooseAudio: []
  chooseImages: []
  removeFile: [path: string]
  showConsent: []
  confirm: []
  cancel: []
}>()

/** 录音按钮文案：忙时只说阶段，不换文案免得按钮高度跳动 */
const audioLabel = computed(() => props.busy
  ? (props.uploadStage || '处理中…')
  : '选择录音')

const steps = [
  { no: '1', title: '完整转写', desc: '一句不落，方言和嘈杂环境也能处理' },
  { no: '2', title: '逐句比对', desc: '对照现行规范，把有问题的话标出来，写明依据' },
  { no: '3', title: '给你一份记录', desc: '看得懂、拿得出手，可以连同原始录音一起用' },
]
const promises = [
  '上传前会明确征求你同意，随时可以撤回',
  '传输和存储全程加密',
  '处理完一键彻底删除',
  '不会拿去训练，不会给任何人看',
]
</script>

<template>
  <view class="selection">
    <view class="up">
      <view class="orb">
        <image class="orb-img" :src="markOrb" mode="widthFix" />
      </view>
      <text class="up-h3">上传，然后交给我</text>
      <text class="up-p">录音和图片可以一起交，也可以只交一种。<text class="up-br" /><text>我会一句一句过一遍，把有问题的地方标出来。</text></text>

      <view class="pick">
        <button
          class="btn btn-main up-btn pick-btn"
          :disabled="busy"
          @click="emit('chooseAudio')"
        >
          {{ audioLabel }}
        </button>
        <button
          class="btn btn-soft up-btn pick-btn"
          :disabled="busy"
          @click="emit('chooseImages')"
        >
          选择图片
        </button>
      </view>
      <text v-if="busy && uploadPercent !== null" class="muted up-caption">已上传 {{ uploadPercent }}%</text>
      <text v-else class="muted up-caption">仅支持 MP3 音频、JPG/PNG 图片，单个文件不超过 10MB；录音最多一段，图片最多 {{ MAX_IMAGES }} 张</text>

      <view v-if="pendingFiles.length" class="picked">
        <view v-for="file in pendingFiles" :key="file.path" class="picked-row">
          <text class="picked-kind">{{ isImage(file) ? '图片' : '录音' }}</text>
          <text class="picked-name">{{ file.name }}</text>
          <text class="picked-del" @click="emit('removeFile', file.path)">移除</text>
        </view>
        <button class="btn btn-main btn-block picked-go" :disabled="busy" @click="emit('showConsent')">
          上传并交给我
        </button>
      </view>
    </view>

    <view class="steps">
      <view v-for="step in steps" :key="step.no" class="step">
        <text class="step-n">{{ step.no }}</text>
        <view class="step-body">
          <text class="step-t">{{ step.title }}</text>
          <text class="step-d">{{ step.desc }}</text>
        </view>
      </view>
    </view>

    <view class="list">
      <text class="list-t">关于你的录音</text>
      <view v-for="(item, index) in promises" :key="item" class="li" :class="{ 'li-last': index === promises.length - 1 }">
        <text class="li-k">✓</text>
        <text class="li-t">{{ item }}</text>
      </view>
    </view>

    <ConsentSheet
      :visible="sheetVisible"
      :filename="pendingSummary"
      @confirm="emit('confirm')"
      @cancel="emit('cancel')"
    />
  </view>
</template>

<style scoped>
.orb-img {
  width: 42px;
  height: 42px;
}

.up-br {
  display: block;
}

.up-btn {
  margin: 0 auto;
}

/* 两种来源可以先后选择，主次分明：录音为主，图片次之 */
.pick {
  display: flex;
  gap: 10px;
  margin: 0 0 16px;
}

.pick-btn {
  flex: 1;
}

.up-caption {
  display: block;
  margin-top: 15px;
  font-size: 12px;
}

.picked {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid var(--line, #e6ded4);
  border-radius: var(--r-md, 10px);
  background: var(--card, #fffdfa);
}

.picked-row {
  display: flex;
  align-items: center;
  padding: 4px 0;
}

.picked-kind {
  flex: none;
  margin-right: 8px;
  font-size: 12px;
  color: var(--ink-3);
}

.picked-name {
  flex: 1;
  font-size: 13px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.picked-del {
  flex: none;
  margin-left: 8px;
  font-size: 12px;
  color: var(--ink-3);
}

.picked-go {
  margin-top: 10px;
}

.step-body {
  flex: 1;
}
</style>
