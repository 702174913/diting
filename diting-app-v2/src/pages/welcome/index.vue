<script setup lang="ts">
/**
 * 首次使用引导：三步讲清「交出录音 → 逐句判读 → 拿到记录」。
 *
 * 只做教学：不发请求、不写登录态、不收集任何信息。
 * 跳过与看完等价（都只是记「介绍过了」），不替用户同意任何事——
 * 上传前的 ConsentSheet 与协议页仍然是必经环节，这里不越位。
 */
import { onShareAppMessage } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import StatusBar from '@/components/StatusBar.vue'
import GuideScreen from '@/components/GuideScreen.vue'
import { markOnboarded } from '@/utils/onboard'
import { useShareContent } from '@/utils/share'

// #ifdef MP-WEIXIN
onShareAppMessage(useShareContent('/pages/home/index', '谛听 · 那段通话，有人替你再听一遍'))
// #endif

const steps = [
  { no: '第一步', kind: 'upload' as const, title: '把那段对话交给我' },
  { no: '第二步', kind: 'read' as const, title: '我一句一句听完' },
  { no: '第三步', kind: 'export' as const, title: '带走一份能用的记录' },
]

const current = ref(0)
const isLast = computed(() => current.value === steps.length - 1)
const primaryLabel = computed(() => isLast.value ? '开始使用' : '下一步')

/**
 * swiper 在小程序里必须给定高度（flex:1 不生效）。
 * 这里不按窗口高度算，而是钉住内容高度 510px：截图 411 + 指引 28 + 步骤名/标题 54。
 * 算窗口高度会在小屏上把截图裁掉，不如让小屏的整页自己滚。
 */
const slideHeight = '510px'

function onChange(event: { detail: { current: number } }) {
  current.value = event.detail.current
}

function next() {
  if (isLast.value) {
    finish()
    return
  }
  current.value += 1
}

/** 记住「已介绍」，然后回到来处；直接冷启动进本页时回首页兜底 */
function finish() {
  markOnboarded()
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
  }
  else {
    uni.switchTab({ url: '/pages/home/index' })
  }
}
</script>

<template>
  <view class="guide">
    <StatusBar />

    <view class="guide-top">
      <text class="guide-skip" role="button" tabindex="0" @click="finish">跳过</text>
    </view>

    <swiper
      class="slides"
      :current="current"
      :duration="240"
      :style="{ height: slideHeight }"
      @change="onChange"
    >
      <swiper-item v-for="step in steps" :key="step.no">
        <view class="slide">
          <GuideScreen :kind="step.kind" />
          <text class="slide-no">{{ step.no }}</text>
          <text class="slide-title">{{ step.title }}</text>
        </view>
      </swiper-item>
    </swiper>

    <view class="dots">
      <view
        v-for="(step, index) in steps"
        :key="step.no"
        class="dot"
        :class="{ 'dot-on': index === current }"
      />
    </view>

    <button class="btn btn-main btn-block guide-go" @click="next">
      {{ primaryLabel }}
    </button>

    <text class="note">
      <text class="note-line">第一次免费。涉及你的录音，上传前会单独征求同意，</text>
      <text class="note-line">任何时候都能撤回，也能一键删除。</text>
    </text>
  </view>
</template>

<style scoped>
.guide {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  padding-bottom: 24px;
  background: var(--bg);
}

.guide-top {
  display: flex;
  justify-content: flex-end;
  padding: 8px 22px 4px;
}

.guide-skip {
  padding: 6px 4px;
  font-size: 13px;
  color: var(--ink-3);
}

.slides {
  flex: none;
  width: 100%;
}

.slide {
  display: flex;
  flex-direction: column;
  align-items: center;
  box-sizing: border-box;
  height: 100%;
  padding: 8px 28px 8px;
  text-align: center;
}

/* 截图 + 圈注在上，文字在下；不加动画，引导时不该抢注意力 */
.slide-no {
  display: block;
  margin-top: 10px;
  font-size: 12.5px;
  letter-spacing: 2px;
  color: #b05526;
}

.slide-title {
  display: block;
  margin-top: 2px;
  font-family: var(--serif);
  font-size: 20px;
  font-weight: 600;
  line-height: 1.6;
  letter-spacing: 0.5px;
}

.dots {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 8px 0 18px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--line);
  transition: width 0.2s, background 0.2s;
}

.dot-on {
  width: 18px;
  border-radius: 4px;
  background: var(--warm);
}

.guide-go {
  margin: 0 22px;
  width: auto;
}

.note {
  margin-top: 14px;
  margin-bottom: 0;
}
</style>
