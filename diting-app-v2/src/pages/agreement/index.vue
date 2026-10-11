<script setup lang="ts">
/**
 * 用户协议正文（S19）：注册强制勾选的 4 份文件，公开访问，无需登录。
 * 正文是 admin 后台富文本维护的 HTML，原样渲染。
 */
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { fetchAgreementDetail } from '@/api/agreement'
import type { AgreementDetail } from '@/api/agreement'
import StatusBar from '@/components/StatusBar.vue'
import { messageOf } from '@/utils/errors'

const detail = ref<AgreementDetail | null>(null)
const errorText = ref('')

onLoad(async (query) => {
  const rawKey = typeof query?.key === 'string' ? query.key : ''
  let key = rawKey
  // 协议标识可能是中文协议名，路由里被编码过：解一次码；未编码时解码是空操作
  try {
    key = decodeURIComponent(rawKey)
  }
  catch {
    key = rawKey
  }
  if (!key) {
    errorText.value = '缺少协议标识'
    return
  }
  try {
    detail.value = await fetchAgreementDetail(key)
  }
  catch (error) {
    errorText.value = messageOf(error)
  }
})
</script>

<template>
  <view class="agreement-page">
    <StatusBar />
    <view v-if="detail" class="agreement-body">
      <text class="agreement-kicker">谛听 · 用户协议</text>
      <text class="agreement-title">{{ detail.title }}</text>
      <text class="agreement-version">版本 V{{ detail.version }}</text>
      <rich-text class="agreement-content" :nodes="detail.contentHtml" />
    </view>
    <view v-else class="agreement-state">
      <text>{{ errorText || '正在载入…' }}</text>
    </view>
  </view>
</template>

<style scoped>
.agreement-page {
  min-height: 100vh;
  background: var(--bg);
}
.agreement-body {
  padding: 12px 20px 48px;
}
.agreement-kicker {
  display: block;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 2px;
  color: var(--sage);
}
.agreement-title {
  display: block;
  margin-top: 8px;
  font-family: var(--serif);
  font-size: 22px;
  font-weight: 600;
  color: var(--ink);
}
.agreement-version {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
}
.agreement-content {
  display: block;
  margin-top: 16px;
  font-size: 14px;
  line-height: 1.9;
  color: var(--ink);
}
.agreement-state {
  padding: 80px 20px;
  text-align: center;
  font-size: 14px;
  color: var(--ink-2);
}
</style>
