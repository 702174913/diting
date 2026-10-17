<script setup lang="ts">
/** 文件选择、预览与同意弹层只负责界面；幂等键/上传会话/受理状态始终留在父页。 */
import { computed } from 'vue'
import ConsentSheet from '@/components/ConsentSheet.vue'
import type { ChosenFile, UploadMode } from '@/utils/choose'
import { MAX_IMAGES, isImage } from '@/utils/choose'
import markOrb from '@/static/icons/mark-orb.png'

const props = defineProps<{
  busy: boolean
  uploadStage: string
  uploadPercent: number | null
  /** 当前要交的东西：录音，或图片。写成字面量联合，编译期能落到运行时 type: String */
  mode: 'audio' | 'image'
  pendingFiles: ChosenFile[]
  sheetVisible: boolean
  pendingSummary: string
}>()
const emit = defineEmits<{
  changeMode: [mode: UploadMode]
  chooseAudio: []
  chooseImages: []
  removeFile: [path: string]
  showConsent: []
  confirm: []
  cancel: []
}>()

/** 主按钮文案：忙时只说阶段，不换文案免得按钮高度跳动 */
const ctaLabel = computed(() => props.busy
  ? (props.uploadStage || '处理中…')
  : (props.mode === 'audio' ? '选择录音文件' : '选择图片'))

const modes: { key: UploadMode, title: string, desc: string }[] = [
  { key: 'audio', title: '录音', desc: '一段通话' },
  { key: 'image', title: '图片', desc: `最多 ${MAX_IMAGES} 张` },
]

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
      <text class="up-p">一次交一种，录音或图片都行。<text class="up-br" /><text>我会一句一句过一遍，把有问题的地方标出来。</text></text>

      <view class="mode">
        <view
          v-for="item in modes"
          :key="item.key"
          class="mode-tab"
          :class="{ 'mode-on': item.key === mode }"
          @click="emit('changeMode', item.key)"
        >
          <text class="mode-t">{{ item.title }}</text>
          <text class="mode-d">{{ item.desc }}</text>
        </view>
      </view>

      <button
        v-if="mode === 'audio'"
        class="btn btn-main up-btn"
        :disabled="busy"
        @click="emit('chooseAudio')"
      >
        {{ ctaLabel }}
      </button>
      <button
        v-else
        class="btn btn-main up-btn"
        :disabled="busy"
        @click="emit('chooseImages')"
      >
        {{ ctaLabel }}
      </button>
      <text v-if="busy && uploadPercent !== null" class="muted up-caption">已上传 {{ uploadPercent }}%</text>
      <text v-else class="muted up-caption">仅支持 MP3 音频、JPG/PNG 图片，单个文件不超过 10MB；两种不能混合提交</text>

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

/* 二选一的 tab：一眼看出这次交的是哪一种 */
.mode {
  display: flex;
  gap: 6px;
  margin: 0 0 16px;
  padding: 4px;
  background: #f4eee7;
  border-radius: 15px;
}

.mode-tab {
  flex: 1;
  padding: 7px 8px;
  border-radius: 12px;
}

.mode-on {
  background: var(--card, #fffdfa);
  box-shadow: var(--sh, 0 2px 8px -4px rgba(90, 70, 50, 0.2));
}

.mode-t {
  display: block;
  font-size: 14px;
  color: var(--ink-2, #5c554d);
}

.mode-on .mode-t {
  font-weight: 500;
  color: var(--ink, #2e2a26);
}

.mode-d {
  display: block;
  margin-top: 1px;
  font-size: 11px;
  color: var(--ink-3, #948b80);
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
