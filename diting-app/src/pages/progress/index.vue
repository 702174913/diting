<script setup lang="ts">
/**
 * 兼容重定向页 —— 不是页面的第二个实现。
 *
 * 进度与记录已合并到 `pages/report/detail`：两页本来就是同一个 taskId
 * （`/api/task/{id}/status` 与 `/api/report/{id}`），拆开只是让同一套
 * 「轮询 + 终态 + 失败重传」逻辑存在两份。
 *
 * 保留这个路由只为不让旧链接、旧分享和历史登录回跳地址 404：
 * 它不发任何请求，也不持有任何状态。
 */
import { onLoad } from '@dcloudio/uni-app'

onLoad((query) => {
  const id = Number(query?.id ?? 0)
  uni.redirectTo({ url: id > 0 ? `/pages/report/detail?id=${id}` : '/pages/report/detail' })
})
</script>

<template>
  <view class="page">
    <view class="empty">
      <text class="empty-text">正在打开记录…</text>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 8px 0 40px;
  background: var(--bg);
}
</style>
