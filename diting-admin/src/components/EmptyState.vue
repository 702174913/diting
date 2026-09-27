<script setup lang="ts">
/**
 * 空态：用于页面级「确实没有数据」的场景（区别于表格内的空行）。
 */
import type { Component } from 'vue'
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/ui/empty'

defineProps<{
  title: string
  description?: string
  /** 图标按组件对象传入（不是字符串 key） */
  icon?: Component
}>()
</script>

<template>
  <Empty>
    <EmptyHeader>
      <EmptyMedia v-if="icon" variant="icon">
        <component :is="icon" />
      </EmptyMedia>
      <EmptyTitle>{{ title }}</EmptyTitle>
      <EmptyDescription v-if="description">
        {{ description }}
      </EmptyDescription>
    </EmptyHeader>
    <EmptyContent v-if="$slots.default">
      <slot />
    </EmptyContent>
  </Empty>
</template>
