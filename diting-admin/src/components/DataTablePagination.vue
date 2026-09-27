<script setup lang="ts">
/**
 * 分页：服务端分页（接口只暴露 page / pageSize）。
 * 沿用定稿的紧凑形态「上一页 · 第 X / Y 页 · 下一页」，不用页码列表。
 */
import { computed } from 'vue'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  page: number
  pageSize: number
  total: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  (event: 'update:page', value: number): void
}>()

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
</script>

<template>
  <nav class="flex items-center gap-3" aria-label="分页">
    <Button
      variant="outline"
      size="sm"
      :disabled="disabled || page <= 1"
      @click="emit('update:page', page - 1)"
    >
      上一页
    </Button>
    <span class="text-sm text-muted-foreground">
      第 <strong class="text-foreground">{{ page }}</strong> / {{ pageCount }} 页
    </span>
    <Button
      variant="outline"
      size="sm"
      :disabled="disabled || page >= pageCount"
      @click="emit('update:page', page + 1)"
    >
      下一页
    </Button>
  </nav>
</template>
