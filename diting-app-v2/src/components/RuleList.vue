<script setup lang="ts">
/** 命中规则与对应法条条款：句子卡片与问题清单共用同一渲染。 */
import type { HitRule } from '@/types/api'

defineProps<{ rules: HitRule[] }>()
</script>

<template>
  <view v-if="rules.length" class="f-rules">
    <view v-for="rule in rules" :key="rule.modelId" class="f-rule">
      <text class="f-rule-name">
        {{ rule.violationName || rule.serialNumber || '命中规则' }}
        <text v-if="rule.violationType" class="f-rule-type">· {{ rule.violationType }}</text>
      </text>
      <view v-for="provision in rule.provisions" :key="provision.id" class="f-law">
        <text class="f-law-name">《{{ provision.name }}》{{ provision.which }}</text>
        <text v-if="provision.content" class="f-law-text">{{ provision.content }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.f-rules {
  margin-top: 10px;
}

.f-rule {
  padding: 8px 10px;
  margin-top: 6px;
  background: var(--warm-soft, #f7efe6);
  border: 1px solid var(--warm-line, #eeddcb);
  border-radius: 8px;
}

.f-rule-name {
  display: block;
  font-size: 12.5px;
  font-weight: 600;
  color: #b05526;
}

.f-rule-type {
  font-weight: 400;
  color: var(--ink-3, #948b80);
}

.f-law {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--warm-line, #eeddcb);
}

.f-law-name {
  display: block;
  font-size: 12px;
  color: var(--ink-2, #5c554d);
}

.f-law-text {
  display: block;
  margin-top: 2px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-3, #948b80);
}
</style>
