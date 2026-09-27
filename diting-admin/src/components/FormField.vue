<script setup lang="ts">
/**
 * 表单字段：把校验错误接到上游的 Field 体系上（FieldGroup + Field）。
 *
 * 上游把这件事拆成了两套：field 是布局原语，form 是 vee-validate 绑定。
 * 规则要求表单一律用 FieldGroup + Field，所以这里只做一次桥接：
 * 布局与错误展示集中在组件里，各表单只需要 useForm + defineField 绑值和传 error。
 *
 * 有意不用 useField 读错误：父级已经用 defineField 注册了同名字段，
 * 子级再注册一次会出现同名重复注册。
 */
import type { HTMLAttributes } from 'vue'
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field'
import { cn } from '@/lib/utils'

const props = defineProps<{
  /** 字段名，与父级 useForm 的 name 一致 */
  name: string
  label: string
  /** 校验错误文案，来自 useForm 的 errors[name] */
  error?: string
  description?: string
  required?: boolean
  /** 定稿的登录页是「只有 placeholder、没有可见标签」的形态，这里保留可访问性但不显示 */
  hideLabel?: boolean
  /** 旧表单里有「标签在左、控件在右」的设置项，用 horizontal */
  orientation?: 'vertical' | 'horizontal' | 'responsive'
  class?: HTMLAttributes['class']
}>()
</script>

<template>
  <Field
    :orientation="orientation ?? 'vertical'"
    :data-invalid="props.error ? true : undefined"
    :class="cn(props.class)"
  >
    <FieldLabel :for="name" :class="hideLabel ? 'sr-only' : undefined">
      {{ label }}
      <span v-if="required" class="text-destructive" aria-hidden="true">*</span>
    </FieldLabel>
    <!--
      注意：这里不能写 <slot :name="..." /> —— name 是 <slot> 的保留属性（用于声明具名插槽），
      写了之后编译器会去找名为 props.name 的插槽，默认插槽的子节点会被静默丢弃。
      父级自己拿 useForm 的 errors 绑 aria-invalid，这里不需要回传任何 prop。
    -->
    <slot />
    <FieldDescription v-if="description">
      {{ description }}
    </FieldDescription>
    <FieldError v-if="props.error">
      {{ props.error }}
    </FieldError>
  </Field>
</template>
