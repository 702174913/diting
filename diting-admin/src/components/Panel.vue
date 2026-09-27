<script setup lang="ts">
/**
 * 面板：旧定稿 .panel + .panel-h 的等价物。
 * 只在布局与内边距上做覆盖（圆角、内外边距），颜色一律走语义令牌。
 */
import type { HTMLAttributes } from 'vue'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'

const props = defineProps<{
  title?: string
  /** 标题右侧的小字说明（旧 .panel-h .note） */
  note?: string
  /** 表格类内容自带贴边布局，避免双重内边距 */
  flush?: boolean
  class?: HTMLAttributes['class']
}>()
</script>

<template>
  <Card :class="cn('gap-0 rounded-lg py-0 shadow-none', props.class)">
    <CardHeader
      v-if="title || note || $slots.actions"
      class="grid-cols-[1fr_auto] items-center gap-2.5 px-5 pt-4 pb-0"
    >
      <CardTitle v-if="title" class="font-heading text-base font-semibold tracking-[1px]">
        {{ title }}
      </CardTitle>
      <div class="col-start-2 flex items-center gap-3">
        <slot name="actions" />
        <span v-if="note" class="text-xs text-muted-foreground">{{ note }}</span>
      </div>
    </CardHeader>
    <CardContent :class="flush ? 'p-0' : 'px-5 pt-4 pb-5'">
      <slot />
    </CardContent>
  </Card>
</template>
