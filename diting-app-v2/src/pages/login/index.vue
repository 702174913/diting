<script setup lang="ts">
import { onLoad, onShareAppMessage } from '@dcloudio/uni-app'
import { ref } from 'vue'
import AccountLoginSheet from '@/components/AccountLoginSheet.vue'
import StatusBar from '@/components/StatusBar.vue'
import type { ReturnTab } from '@/utils/h5Auth'
import { useShareContent } from '@/utils/share'

const tabs: Record<ReturnTab, string> = {
  home: '/pages/home/index',
  upload: '/pages/upload/index',
  history: '/pages/history/index',
  about: '/pages/about/index',
}
const returnTab = ref<ReturnTab>('home')
const resumePath = ref('')

// #ifdef MP-WEIXIN
onShareAppMessage(useShareContent('/pages/home/index', '谛听 · 那段通话，有人替你再听一遍'))
// #endif

onLoad((query) => {
  const requested = query?.returnTab
  if (typeof requested === 'string' && Object.prototype.hasOwnProperty.call(tabs, requested)) {
    returnTab.value = requested as ReturnTab
  }
  const resume = query?.resume
  // 只能回到本应用明确列出的页面，不能让 URL 参数决定任意跳转。
  if (typeof resume === 'string' && (
    resume === '/pages/privacy/index'
    || /^\/pages\/(?:progress\/index|report\/detail)\?id=[1-9]\d*$/.test(resume)
  )) {
    resumePath.value = resume
  }
})

function onAccountLogin() {
  if (resumePath.value) {
    uni.redirectTo({ url: resumePath.value })
  }
  else {
    uni.switchTab({ url: tabs[returnTab.value] })
  }
}
</script>

<template>
  <view class="login-page">
    <StatusBar />
    <AccountLoginSheet
      :visible="true"
      :standalone="true"
      purpose="登录后，继续查看你的记录和上传文件。"
      @success="onAccountLogin"
    />
  </view>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  background: var(--bg);
}
</style>
