<script setup lang="ts">
/**
 * 口径说明块（旧定稿的 .hint）。
 * 内容是可读性优先的长句 + <b>/<code> 内联标记，所以直接用 Alert 的 note 变体装自由内容，
 * 不再套 AlertTitle / AlertDescription —— 那两个会把字号与颜色再覆盖一层。
 */
import { Alert } from '@/components/ui/alert'
</script>

<template>
  <Alert variant="note" role="note">
    <slot />
  </Alert>
</template>
