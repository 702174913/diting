<script setup lang="ts">
import type { AlertDialogActionProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { AlertDialogAction } from 'reka-ui'
import type { ButtonVariants } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { buttonVariants } from '@/components/ui/button'

const props = withDefaults(defineProps<AlertDialogActionProps & {
  class?: HTMLAttributes['class']
  /** 上游只透传 class，导致「破坏性确认」只能靠 class 覆盖配色；这里补上 variant/size 透传 */
  variant?: ButtonVariants['variant']
  size?: ButtonVariants['size']
}>(), {
  variant: 'default',
  size: 'default',
})

const delegatedProps = reactiveOmit(props, 'class', 'variant', 'size')
</script>

<template>
  <AlertDialogAction
    v-bind="delegatedProps"
    :class="cn(buttonVariants({ variant, size }), props.class)"
  >
    <slot />
  </AlertDialogAction>
</template>
