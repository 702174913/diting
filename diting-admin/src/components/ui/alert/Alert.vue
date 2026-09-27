<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import type { AlertVariants } from '.'
import { alertVariants } from '.'
import { cn } from '@/lib/utils'

const props = withDefaults(defineProps<{
  class?: HTMLAttributes['class']
  variant?: AlertVariants['variant']
  /** 静态说明性 callout 传 "note"：它不是 live region，不该被读屏当成告警播报 */
  role?: string
}>(), {
  role: 'alert',
})
</script>

<template>
  <div
    data-slot="alert"
    :class="cn(alertVariants({ variant }), props.class)"
    :role="role"
  >
    <slot />
  </div>
</template>
