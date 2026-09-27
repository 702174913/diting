<script setup lang="ts">
/**
 * 页面头：标题 + 口径说明 + 右侧操作区。
 * 对应旧定稿的 .page-head：标题用衬线字、字号与字距都按定稿取值。
 */
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="mb-5 flex flex-wrap items-end justify-between gap-3">
    <div>
      <h1 class="font-heading text-2xl font-semibold tracking-[2px]">
        {{ title }}
      </h1>
      <p v-if="description" class="mt-1 text-xs text-muted-foreground">
        {{ description }}
      </p>
    </div>
    <div v-if="$slots.actions" class="flex flex-wrap items-center gap-3">
      <slot name="actions" />
    </div>
  </div>
</template>
