<script setup lang="ts">
/**
 * 规则 ↔ 法律条款绑定（V13）。
 *
 * 绑定按「规则」存储、实时生效：不生成规则新版本、不参与 expectedVersion 乐观锁，
 * 报告也不存依据快照 —— 这里改完，引用该规则的报告（含历史版本重读）立刻跟着变。
 * 提交是替换式的（PUT 全量 clauseIds）：取消勾选即解绑，全不选等于解绑全部。
 *
 * 两个必须讲清楚的约束：
 * 1) 绑定接口只接受「存在且启用」的条款，混进停用条款会被 422 整单拒绝 ——
 *    所以已停用的绑定不可能被重新提交，保存即视为解除，必须在界面上先说明。
 * 2) 提交顺序就是 bindings.sort_order，也就是报告里「相关依据」的展示顺序：
 *    勾选顺序 = 展示顺序，取消再勾会排到末位。
 */
import { CheckIcon, ScaleIcon, SearchIcon } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { toast } from 'vue-sonner'
import { bindRuleLegalClauses, fetchLegalClauses } from '@/api/admin'
import { ApiError } from '@/api/request'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Spinner } from '@/components/ui/spinner'
import { useUserStore } from '@/stores/user'
import type { LegalClauseItem, RuleItem } from '@/api/admin'

const props = defineProps<{
  rule: RuleItem | null
}>()

const emit = defineEmits<{ (event: 'saved'): void }>()
const open = defineModel<boolean>('open', { required: true })

const userStore = useUserStore()

/** 该规则当前绑定的条款（含已停用的：它们仍然照常下发，只是不能重新绑定） */
const pinned = computed<LegalClauseItem[]>(() => props.rule?.legalClauses ?? [])
const pinnedEnabled = computed(() => pinned.value.filter(clause => clause.enabled))
const pinnedDisabled = computed(() => pinned.value.filter(clause => !clause.enabled))
const pinnedIds = computed(() => new Set(pinned.value.map(clause => clause.id)))

const selected = ref<number[]>([])
/** 打开时的初始选中项：用于判断「有改动」，没改动就不必再写一条审计 */
const initialSelection = ref<number[]>([])

const keyword = ref('')
const candidates = ref<LegalClauseItem[]>([])
const candidateTotal = ref(0)
const loadingCandidates = ref(false)
const saving = ref(false)

/** 已绑定的不再列进「可选」里，避免同一个条款出现两次 */
const available = computed(() => candidates.value.filter(clause => !pinnedIds.value.has(clause.id)))

/** 搜索命中数超过一页时的提示（服务端分页，一次只取前 100 条） */
const truncated = computed(() => candidateTotal.value > candidates.value.length)

const dirty = computed(() => (
  selected.value.length !== initialSelection.value.length
  || selected.value.some((id, index) => id !== initialSelection.value[index])
))
/** 已停用的绑定保存后一定会被解除，所以那种情况下「没动勾选」也是一次真实的改动 */
const changed = computed(() => dirty.value || pinnedDisabled.value.length > 0)

async function loadCandidates() {
  const token = userStore.token
  if (!token) {
    return
  }
  loadingCandidates.value = true
  try {
    const result = await fetchLegalClauses(token, {
      page: 1,
      pageSize: 100,
      keyword: keyword.value,
      enabled: true,
    })
    candidates.value = result.items
    candidateTotal.value = result.total
  }
  catch (e) {
    candidates.value = []
    candidateTotal.value = 0
    toast.error(e instanceof ApiError ? e.message : '条款库加载失败')
  }
  finally {
    loadingCandidates.value = false
  }
}

watch(open, (visible) => {
  if (!visible) {
    return
  }
  initialSelection.value = pinnedEnabled.value.map(clause => clause.id)
  selected.value = [...initialSelection.value]
  keyword.value = ''
  void loadCandidates()
})

function toggle(id: number) {
  const index = selected.value.indexOf(id)
  if (index >= 0) {
    selected.value.splice(index, 1)
  }
  else {
    selected.value.push(id)
  }
}

function clearAll() {
  selected.value = []
}

async function save() {
  const token = userStore.token
  const rule = props.rule
  if (!token || !rule || saving.value) {
    return
  }
  saving.value = true
  try {
    const bound = await bindRuleLegalClauses(token, rule.ruleId, selected.value)
    toast.success(bound.length > 0 ? `已绑定 ${bound.length} 条条款` : '已解绑全部条款')
    open.value = false
    emit('saved')
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '绑定失败')
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="sm:max-w-3xl">
      <DialogHeader>
        <DialogTitle>绑定法律条款</DialogTitle>
        <DialogDescription>
          命中《{{ rule?.name ?? '' }}》的报告会在「相关依据」里给出勾选的条款。
          绑定实时生效、不生成规则新版本，报告也不存快照 —— 勾选顺序就是报告里的展示顺序。
        </DialogDescription>
      </DialogHeader>

      <div class="flex flex-col gap-4">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <span class="text-sm">
            已选 <b>{{ selected.length }}</b> 条
            <span v-if="pinnedDisabled.length > 0" class="text-muted-foreground">
              （另有 {{ pinnedDisabled.length }} 条已停用的绑定会被解除）
            </span>
          </span>
          <Button
            variant="ghost"
            size="sm"
            :disabled="saving || selected.length === 0"
            @click="clearAll"
          >
            全部取消
          </Button>
        </div>

        <section class="flex flex-col gap-2">
          <h3 class="text-xs tracking-wide text-muted-foreground">
            该规则已绑定
          </h3>
          <p v-if="pinned.length === 0" class="rounded-md border border-dashed border-border px-3 py-4 text-sm text-muted-foreground">
            还没有绑定任何条款：命中该规则时报告不会给出法律依据。
          </p>
          <label
            v-for="clause in pinnedEnabled"
            :key="clause.id"
            class="flex cursor-pointer items-start gap-3 rounded-md border border-border p-2.5 hover:bg-muted/40"
          >
            <Checkbox
              class="mt-0.5"
              :model-value="selected.includes(clause.id)"
              :disabled="saving"
              @update:model-value="() => toggle(clause.id)"
            />
            <span class="flex flex-col gap-1">
              <span class="text-sm">
                {{ clause.lawName }}
                <span class="text-muted-foreground">{{ clause.articleNo }}</span>
              </span>
              <span class="line-clamp-2 text-xs text-muted-foreground">{{ clause.content }}</span>
            </span>
          </label>

          <p
            v-for="clause in pinnedDisabled"
            :key="clause.id"
            class="flex items-start gap-3 rounded-md border border-dashed border-destructive/40 bg-destructive/5 p-2.5 text-sm"
          >
            <Badge variant="down" size="chip" class="mt-0.5">
              已停用
            </Badge>
            <span class="flex flex-col gap-1">
              <span>{{ clause.lawName }} <span class="text-muted-foreground">{{ clause.articleNo }}</span></span>
              <span class="text-xs text-muted-foreground">
                报告里仍在下发；停用条款不能重新绑定，保存本次绑定后这条绑定会被解除（要保留请先到「条款库」把它改回启用）。
              </span>
            </span>
          </p>
        </section>

        <section class="flex flex-col gap-2">
          <h3 class="text-xs tracking-wide text-muted-foreground">
            从条款库添加
          </h3>
          <form class="flex items-center gap-2" @submit.prevent="loadCandidates">
            <Input
              v-model="keyword"
              class="flex-1"
              placeholder="按法规名称 / 条款号 / 原文搜索"
            />
            <Button type="submit" variant="outline" :disabled="loadingCandidates">
              <SearchIcon data-icon="inline-start" />
              搜索
            </Button>
          </form>

          <div v-if="loadingCandidates" class="flex flex-col gap-2">
            <Skeleton class="h-10 w-full" />
            <Skeleton class="h-10 w-full" />
          </div>
          <p v-else-if="available.length === 0" class="rounded-md border border-dashed border-border px-3 py-4 text-sm text-muted-foreground">
            {{ keyword
              ? '没有匹配的启用条款。'
              : '条款库里没有可绑定的启用条款（新增入口在侧栏「治理 → 条款库」）。' }}
          </p>
          <div v-else class="flex max-h-56 flex-col gap-1 overflow-y-auto pr-1">
            <label
              v-for="clause in available"
              :key="clause.id"
              class="flex cursor-pointer items-start gap-3 rounded-md p-2.5 hover:bg-muted/40"
            >
              <Checkbox
                class="mt-0.5"
                :model-value="selected.includes(clause.id)"
                :disabled="saving"
                @update:model-value="() => toggle(clause.id)"
              />
              <span class="flex flex-col gap-1">
                <span class="text-sm">
                  {{ clause.lawName }}
                  <span class="text-muted-foreground">{{ clause.articleNo }}</span>
                </span>
                <span class="line-clamp-2 text-xs text-muted-foreground">{{ clause.content }}</span>
              </span>
            </label>
          </div>

          <p v-if="truncated" class="text-xs text-muted-foreground">
            命中 {{ candidateTotal }} 条，只列出前 {{ candidates.length }} 条，请用关键词缩小范围。
          </p>
        </section>

        <p v-if="!changed" class="flex items-center gap-2 text-xs text-muted-foreground">
          <CheckIcon class="size-3.5" />
          绑定没有改动（每次保存都会写一条审计，所以没改就不必保存）。
        </p>
      </div>

      <DialogFooter>
        <Button variant="outline" :disabled="saving" @click="open = false">
          取消
        </Button>
        <Button :disabled="saving || !changed" @click="save">
          <Spinner v-if="saving" data-icon="inline-start" aria-label="保存中" />
          {{ saving ? '保存中…' : '保存绑定' }}
        </Button>
      </DialogFooter>

      <p class="flex items-center gap-2 text-xs text-muted-foreground">
        <ScaleIcon class="size-3.5" />
        只在「规则库」里改绑定不影响历史报告已发布的判读结果，只影响依据的展示。
      </p>
    </DialogContent>
  </Dialog>
</template>
