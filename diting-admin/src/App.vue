<script setup lang="ts">
/**
 * 应用根：路由出口 + 全局 toast 容器。
 * 反馈一律走 vue-sonner（页面内只保留口径说明与只读提示），所以 Toaster 只挂这一次。
 */
import { Toaster } from '@/components/ui/sonner'
</script>

<template>
  <router-view />
  <Toaster position="bottom-right" />
</template>
