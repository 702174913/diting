<script setup lang="ts">
/**
 * 首屏 Hero 卡：标题 + 说明 + 主行动按钮 + 一句话承诺 + 隐私提示条。
 * 文案口径来自 UI 定稿「晨光 · 陪伴」：不评判、只把那几句话找出来。
 */
import shieldSage from '@/static/icons/shield-sage.png'
import uploadWhite from '@/static/icons/upload-white.png'

interface Props {
  title: string
  description: string
  buttonText: string
  caption?: string
  tipText?: string
}

withDefaults(defineProps<Props>(), {
  caption: '',
  tipText: '',
})

const emit = defineEmits<{ action: [] }>()
</script>

<template>
  <view class="hero">
    <text class="hero-h1">
      {{ title }}
    </text>
    <text class="hero-p">
      {{ description }}
    </text>
  </view>

  <view class="cta">
    <button class="btn btn-main btn-block" @click="emit('action')">
      <image class="btn-ic" :src="uploadWhite" mode="widthFix" />
      <text class="btn-label">
        {{ buttonText }}
      </text>
    </button>
    <text v-if="caption" class="muted cta-caption">
      {{ caption }}
    </text>
  </view>

  <view v-if="tipText" class="tip">
    <image class="tip-ic" :src="shieldSage" mode="widthFix" />
    <text class="tip-p">
      {{ tipText }}
    </text>
  </view>
</template>

<style scoped>
.cta {
  padding: 0 20px 4px;
}

.btn-ic {
  width: 19px;
  height: 19px;
}

.btn-label {
  font-size: 16px;
}

.cta-caption {
  display: block;
  margin-top: 11px;
  text-align: center;
}
</style>
