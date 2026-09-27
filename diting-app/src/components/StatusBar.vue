<script setup lang="ts">
/**
 * 自定义导航栏下的状态栏占位。
 * 页面统一使用 navigationStyle: custom，由系统绘制状态栏，这里只留出高度。
 */
const info = uni.getSystemInfoSync()
const height = Number(info.statusBarHeight ?? 0)
</script>

<template>
  <view class="status-bar" :style="{ height: `${height}px` }" />
</template>

<style scoped>
.status-bar {
  flex-shrink: 0;
  width: 100%;
}
</style>
