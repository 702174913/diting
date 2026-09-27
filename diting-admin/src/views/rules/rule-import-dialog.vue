<script setup lang="ts">
/**
 * 规则批量导入：模板下载 → 粘贴或选择 CSV → 预检报告 → 确认落库。
 *
 * 两段式不是仪式感：预检把「新增几行 / 改版几行 / 哪一行报错」先摆出来，
 * 确认后才落库；服务端在落库时会重新解析并重新校验，不信前端传来的预检结果。
 * 同 code 走乐观锁追加新版本（保留历史），单行失败不影响其他行。
 *
 * 落库后：全部成功 → toast + 自动关闭（父页刷新列表，关窗时 watch 会清掉上一次的预检与结果）；
 * 有失败行 → 留在弹窗里，因为「哪一行、为什么失败」只能在弹窗内逐行看，toast 盛不下。
 *
 * 布局：弹窗限高 85vh，正文区独立滚动，页脚固定在底部。
 * 31 行预检结果把弹窗撑得比视口还高时，标题与「确认导入」按钮都不会被顶出屏幕
 * （页脚是第 3 个网格行，不是滚动区的一部分）。
 */
import { DownloadIcon, FileUpIcon, ListChecksIcon } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { toast } from 'vue-sonner'
import type { RuleImportApply, RuleImportPrecheck, RuleImportRow } from '@/api/admin'
import { applyRuleImport, precheckRuleImport } from '@/api/admin'
import { ApiError } from '@/api/request'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field, FieldDescription, FieldLabel } from '@/components/ui/field'
import { Separator } from '@/components/ui/separator'
import { Spinner } from '@/components/ui/spinner'
import { Textarea } from '@/components/ui/textarea'
import { useUserStore } from '@/stores/user'

const emit = defineEmits<{ (event: 'imported'): void }>()
const open = defineModel<boolean>('open', { required: true })

const userStore = useUserStore()

/** 模板：列名与规则接口字段同构，关键词用顿号分隔（与页面内编辑器同一口径） */
const TEMPLATE_HEADER = 'code,name,groupName,matchType,roleScope,triggerRole,windowSize,keywords,expectedKeywords,deduct,description'
const TEMPLATE_ROWS = [
  'DJ-001,示例·威胁性表述,合规禁语,hit,ANY,,,"威胁、恐吓",,20,依据催收自律公约第八条',
  'DJ-002,示例·还款安排需应答,流程规范,require,ANY,ANY,3,"还款安排","已了解、已确认",15,依据内部质检口径',
]

const content = ref('')
const precheck = ref<RuleImportPrecheck | null>(null)
const result = ref<RuleImportApply | null>(null)
const checking = ref(false)
const applying = ref(false)

const canApply = computed(() => Boolean(precheck.value) && (precheck.value?.createCount ?? 0) + (precheck.value?.updateCount ?? 0) > 0)
const blockingErrors = computed(() => precheck.value?.errorCount ?? 0)

function reset() {
  content.value = ''
  precheck.value = null
  result.value = null
}

watch(open, (visible) => {
  // 开窗时清上一次的状态，而不是关窗时清：成功导入后会自动关窗，
  // 关窗动画期间还得把「导入结果」留在屏幕上，否则结果会先一步变空。
  if (visible) {
    reset()
  }
})

function downloadTemplate() {
  const csv = [TEMPLATE_HEADER, ...TEMPLATE_ROWS].join('\r\n')
  // 带 BOM，Excel 打开中文表头才不会乱码
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'diting-rules-template.csv'
  link.click()
  URL.revokeObjectURL(url)
}

async function pickFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  content.value = await file.text()
  precheck.value = null
  result.value = null
  input.value = ''
}

async function runPrecheck() {
  const token = userStore.token
  if (!token || !content.value.trim()) {
    toast.error('先粘贴内容或选择文件')
    return
  }
  checking.value = true
  result.value = null
  try {
    precheck.value = await precheckRuleImport(token, content.value)
    const report = precheck.value
    if (report.errorCount > 0) {
      toast.warning(`预检完成：新增 ${report.createCount}、改版 ${report.updateCount}、报错 ${report.errorCount}`)
    }
    else {
      toast.success(`预检通过：新增 ${report.createCount}、改版 ${report.updateCount}`)
    }
  }
  catch (e) {
    precheck.value = null
    toast.error(e instanceof ApiError ? e.message : '预检失败')
  }
  finally {
    checking.value = false
  }
}

async function runApply() {
  const token = userStore.token
  if (!token || !canApply.value) {
    return
  }
  applying.value = true
  try {
    const applied = await applyRuleImport(token, content.value)
    result.value = applied
    emit('imported')
    if (applied.failed > 0) {
      toast.warning(`已导入：新增 ${applied.created}、改版 ${applied.updated}、失败 ${applied.failed}`)
      return
    }
    toast.success(`导入完成：新增 ${applied.created}、改版 ${applied.updated}`)
    open.value = false
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '导入失败')
  }
  finally {
    applying.value = false
  }
}

function actionVariant(action: RuleImportRow['action']) {
  if (action === 'CREATE') {
    return 'up' as const
  }
  if (action === 'UPDATE') {
    return 'warn' as const
  }
  return 'down' as const
}

function actionLabel(action: RuleImportRow['action']) {
  return action === 'CREATE' ? '新增' : action === 'UPDATE' ? '改版' : '报错'
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="max-h-[85vh] grid-rows-[auto_minmax(0,1fr)_auto] overflow-hidden sm:max-w-3xl">
      <DialogHeader>
        <DialogTitle>批量导入规则</DialogTitle>
        <DialogDescription>
          模板列：code、name、groupName、matchType（hit / require / must-have）、roleScope、triggerRole、windowSize、keywords、expectedKeywords、deduct、description。关键词用顿号分隔；同 code 视为改版并追加新版本。
          <br>
          目标角色功能暂已隐藏：roleScope／triggerRole 两列仍可解析（roleScope 可填 ANY 或留空，triggerRole 已允许 ANY），判读时不区分说话人。
        </DialogDescription>
      </DialogHeader>

      <div class="flex min-h-0 flex-col gap-4 overflow-y-auto">
        <div class="flex flex-wrap items-center gap-2">
          <Button variant="outline" size="sm" @click="downloadTemplate">
            <DownloadIcon data-icon="inline-start" />
            下载模板
          </Button>
          <Button variant="outline" size="sm" as-child>
            <label for="rule-import-file" class="cursor-pointer">
              <FileUpIcon data-icon="inline-start" />
              选择 CSV 文件
            </label>
          </Button>
          <input
            id="rule-import-file"
            class="sr-only"
            type="file"
            accept=".csv,.txt,text/csv"
            @change="pickFile"
          >
        </div>

        <Field>
          <FieldLabel for="rule-import-content">
            导入内容（可直接粘贴）
          </FieldLabel>
          <Textarea
            id="rule-import-content"
            v-model="content"
            rows="8"
            spellcheck="false"
            placeholder="第一行必须是列名，之后每行一条规则"
          />
          <FieldDescription>
            预检只看不写；确认导入时服务端会重新解析并逐行落库，失败行会连同原因列出。
          </FieldDescription>
        </Field>

        <div class="flex flex-wrap items-center gap-2">
          <Button :disabled="checking || !content.trim()" @click="runPrecheck">
            <ListChecksIcon data-icon="inline-start" />
            {{ checking ? '预检中…' : '预检' }}
          </Button>
        </div>

        <template v-if="precheck">
          <Separator />
          <div class="flex flex-wrap items-center gap-2 text-sm">
            <span>共 {{ precheck.total }} 行</span>
            <Badge variant="up" size="chip">
              新增 {{ precheck.createCount }}
            </Badge>
            <Badge variant="warn" size="chip">
              改版 {{ precheck.updateCount }}
            </Badge>
            <Badge variant="down" size="chip">
              报错 {{ precheck.errorCount }}
            </Badge>
          </div>
          <div class="rounded-md border border-border">
            <div
              v-for="row in precheck.rows"
              :key="`${row.line}-${row.code}`"
              class="flex items-start gap-3 border-b border-border px-3 py-2 text-sm last:border-b-0"
            >
              <span class="w-12 shrink-0 text-xs text-muted-foreground">第 {{ row.line }} 行</span>
              <Badge :variant="actionVariant(row.action)" size="chip">
                {{ actionLabel(row.action) }}
              </Badge>
              <div class="min-w-0 flex-1">
                <div class="truncate">
                  {{ row.code || '（缺编码）' }}<span v-if="row.name"> · {{ row.name }}</span>
                  <span v-if="row.currentVersion" class="text-xs text-muted-foreground"> · 当前 v{{ row.currentVersion }}</span>
                </div>
                <div
                  v-for="message in row.messages"
                  :key="message"
                  class="text-xs text-destructive"
                >
                  {{ message }}
                </div>
              </div>
            </div>
          </div>
        </template>

        <template v-if="result">
          <Separator />
          <div class="flex flex-wrap items-center gap-2 text-sm">
            <span>导入结果：</span>
            <Badge variant="up" size="chip">
              新增 {{ result.created }}
            </Badge>
            <Badge variant="warn" size="chip">
              改版 {{ result.updated }}
            </Badge>
            <Badge :variant="result.failed > 0 ? 'down' : 'secondary'" size="chip">
              失败 {{ result.failed }}
            </Badge>
          </div>
          <div
            v-for="failure in result.failures"
            :key="`${failure.line}-${failure.code}`"
            class="text-xs text-destructive"
          >
            第 {{ failure.line }} 行（{{ failure.code || '缺编码' }}）：{{ failure.messages.join('；') }}
          </div>
        </template>
      </div>

      <DialogFooter class="sm:items-center">
        <span
          v-if="precheck && blockingErrors > 0"
          class="text-xs text-warn sm:mr-auto"
        >
          有 {{ blockingErrors }} 行报错，导入时会跳过这些行
        </span>
        <Button variant="outline" @click="open = false">
          关闭
        </Button>
        <Button
          v-if="precheck"
          :disabled="applying || !canApply"
          @click="runApply"
        >
          <Spinner v-if="applying" data-icon="inline-start" aria-label="导入中" />
          {{ applying ? '导入中…' : '确认导入' }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
