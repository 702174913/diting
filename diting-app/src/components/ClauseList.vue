<script setup lang="ts">
/**
 * 适用条款：命中规则绑定的法律条款（V13）。
 * 折叠展示 —— 默认只列「《法规名》条款号」，点开才看条文原文：
 * 法规原文动辄几百字，直铺会把命中原文与证据说明淹掉。
 * 服务端按规则当前绑定实时读取，因此绑定改了，历史记录重读时看到的是新条款。
 */
import { computed, ref } from 'vue'
import type { ReportLegalClause } from '@/types/api'

const props = defineProps<{ clauses?: ReportLegalClause[] }>()

const list = computed(() => props.clauses ?? [])
/** 展开的条款 id：默认全收起 */
const openIds = ref<number[]>([])

/** 条款标题：《法规名》+ 条款号；法规名自带书名号时不重复加（与后台详情、PDF 导出件同一口径） */
function clauseTitle(clause: ReportLegalClause) {
  const law = clause.lawName ?? ''
  const named = law && !law.startsWith('《') ? `《${law}》` : law
  return `${named}${clause.articleNo ?? ''}`
}

function toggle(id: number) {
  openIds.value = openIds.value.includes(id)
    ? openIds.value.filter(item => item !== id)
    : [...openIds.value, id]
}
</script>

<template>
  <view v-if="list.length" class="clause">
    <text class="clause-t">适用条款</text>
    <view v-for="clause in list" :key="`clause-${clause.id}`" class="clause-row">
      <view class="clause-head" @click="toggle(clause.id)">
        <text class="clause-no">{{ clauseTitle(clause) }}</text>
        <text class="clause-toggle">{{ openIds.includes(clause.id) ? '收起原文' : '查看原文' }}</text>
      </view>
      <text v-if="openIds.includes(clause.id)" class="clause-body">{{ clause.content }}</text>
    </view>
  </view>
</template>
