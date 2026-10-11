<script setup lang="ts">
/**
 * 上传前的同意弹层。
 * 定稿要求：上传前明确征求同意、随时可撤回，四条承诺逐条列明，不做含糊表述。
 */
interface Props {
  visible: boolean
  filename?: string
}

withDefaults(defineProps<Props>(), { filename: '' })

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

const promises = [
  '上传前会明确征求你同意，随时可以撤回',
  '传输和存储全程加密',
  '处理完可以一键彻底删除，不留副本',
  '不会拿去训练，不会给任何人看',
]
</script>

<template>
  <view v-if="visible" class="mask" @click="emit('cancel')">
    <view class="sheet" @click.stop>
      <text class="sheet-title">
        关于你的录音
      </text>
      <text v-if="filename" class="sheet-file">
        {{ filename }}
      </text>
      <view v-for="(item, index) in promises" :key="item" class="li" :class="{ 'li-last': index === promises.length - 1 }">
        <text class="li-k">✓</text>
        <text class="li-t">{{ item }}</text>
      </view>
      <button class="btn btn-main btn-block sheet-confirm" @click="emit('confirm')">
        同意并上传
      </button>
      <button class="btn btn-soft btn-block sheet-cancel" @click="emit('cancel')">
        再想想
      </button>
    </view>
  </view>
</template>

<style scoped>
.mask {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 100;
  display: flex;
  align-items: flex-end;
  background: rgba(46, 42, 38, 0.45);
}

.sheet {
  box-sizing: border-box;
  width: 100%;
  max-height: 90vh;
  /* 兜底：不支持 CSS 变量的环境保持改动前的 32px */
  padding: 24px 22px 32px;
  /*
    uni-app H5 把 tabBar 渲染成 DOM（z-index: 998），会盖住 z-index: 100 的弹层底部。
    --window-bottom 由 uni-h5 注入到 document.documentElement，无需自己算 tabBar 高度：
      H5 端 = tabBar 高度 + env(safe-area-inset-bottom)（已含安全区，不要再加一次 env()）
      小程序端未定义 -> 走 0px 兜底，原生 tabBar 本就在 window 之外
  */
  padding-bottom: calc(32px + var(--window-bottom, 0px));
  overflow-y: auto;
  background: var(--card);
  border-radius: var(--r-lg) var(--r-lg) 0 0;
}

.sheet-title {
  display: block;
  margin-bottom: 12px;
  font-family: var(--serif);
  font-size: 17px;
  font-weight: 600;
}

.sheet-file {
  display: block;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--ink-3);
}

.sheet-confirm {
  margin-top: 16px;
}

.sheet-cancel {
  margin-top: 10px;
}
</style>
