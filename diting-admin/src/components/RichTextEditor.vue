<script setup lang="ts">
/**
 * 轻量富文本编辑器（协议配置专用，不引入编辑器依赖）。
 * contenteditable + execCommand：加粗/斜体/下划线/标题/引用/列表/链接/清格式。
 * 服务端白名单负责落库清洗；本组件在 innerHTML 渲染、输入及粘贴前再做独立清洗。
 */
import { nextTick, onMounted, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { sanitizeAgreementHtml } from '@/utils/sanitizeAgreement'

const props = defineProps<{
  modelValue: string
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const editor = ref<HTMLElement | null>(null)
const syncing = ref(false)
const linkOpen = ref(false)
const linkUrl = ref('https://')
const linkError = ref('')
let savedRange: Range | null = null

function syncFromModel() {
  if (!editor.value || syncing.value) {
    return
  }
  const safe = sanitizeAgreementHtml(props.modelValue || '')
  if (editor.value.innerHTML !== safe) {
    editor.value.innerHTML = safe
  }
}

function onInput() {
  if (!editor.value) {
    return
  }
  const safe = sanitizeAgreementHtml(editor.value.innerHTML)
  if (editor.value.innerHTML !== safe) {
    editor.value.innerHTML = safe
  }
  syncing.value = true
  emit('update:modelValue', safe)
  nextTick(() => {
    syncing.value = false
  })
}

function onPaste(event: ClipboardEvent) {
  if (props.disabled || !editor.value) {
    event.preventDefault()
    return
  }
  event.preventDefault()
  const html = event.clipboardData?.getData('text/html')
  if (html) {
    document.execCommand('insertHTML', false, sanitizeAgreementHtml(html))
  }
  else {
    document.execCommand('insertText', false, event.clipboardData?.getData('text/plain') ?? '')
  }
  onInput()
}

function exec(command: string, value?: string) {
  if (props.disabled || !editor.value) {
    return
  }
  editor.value.focus()
  document.execCommand(command, false, value)
  onInput()
}

function insertLink() {
  const selection = window.getSelection()
  const range = selection?.rangeCount ? selection.getRangeAt(0) : null
  if (!range || range.collapsed || !editor.value?.contains(range.commonAncestorContainer)) {
    linkError.value = '请先选中正文中的文字'
    return
  }
  savedRange = range.cloneRange()
  linkUrl.value = 'https://'
  linkError.value = ''
  linkOpen.value = true
}

async function applyLink() {
  let url: URL
  try {
    url = new URL(linkUrl.value.trim())
    if (!['http:', 'https:'].includes(url.protocol)) {
      throw new Error('不支持的链接协议')
    }
  }
  catch {
    linkError.value = '只允许完整的 http(s) 链接'
    return
  }
  linkOpen.value = false
  await nextTick()
  editor.value?.focus()
  if (savedRange) {
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(savedRange)
  }
  savedRange = null
  exec('createLink', url.href)
}

function formatBlock(tag: string) {
  exec('formatBlock', tag)
}

onMounted(syncFromModel)
watch(() => props.modelValue, syncFromModel)
</script>

<template>
  <div class="overflow-hidden rounded-xl border border-line bg-card">
    <div class="flex flex-wrap items-center gap-1 border-b border-line bg-muted/40 px-2 py-1.5">
      <Button variant="ghost" size="sm" aria-label="加粗" :disabled="disabled" @click="exec('bold')">
        <span class="font-bold">B</span>
      </Button>
      <Button variant="ghost" size="sm" aria-label="斜体" :disabled="disabled" @click="exec('italic')">
        <span class="italic">I</span>
      </Button>
      <Button variant="ghost" size="sm" aria-label="下划线" :disabled="disabled" @click="exec('underline')">
        <span class="underline">U</span>
      </Button>
      <Separator orientation="vertical" class="mx-1 h-5" />
      <Button variant="ghost" size="sm" :disabled="disabled" @click="formatBlock('h2')">
        标题
      </Button>
      <Button variant="ghost" size="sm" :disabled="disabled" @click="formatBlock('h3')">
        小标题
      </Button>
      <Button variant="ghost" size="sm" :disabled="disabled" @click="formatBlock('blockquote')">
        引用
      </Button>
      <Button variant="ghost" size="sm" :disabled="disabled" @click="formatBlock('p')">
        正文
      </Button>
      <Separator orientation="vertical" class="mx-1 h-5" />
      <Button variant="ghost" size="sm" :disabled="disabled" @click="exec('insertUnorderedList')">
        列表
      </Button>
      <Button variant="ghost" size="sm" :disabled="disabled" @click="exec('insertOrderedList')">
        编号
      </Button>
      <Button variant="ghost" size="sm" :disabled="disabled" @click="insertLink">
        链接
      </Button>
      <Button variant="ghost" size="sm" :disabled="disabled" @click="exec('removeFormat')">
        清格式
      </Button>
    </div>
    <div v-if="linkError && !linkOpen" class="px-5 py-2 text-sm text-destructive" role="alert">{{ linkError }}</div>
    <div
      ref="editor"
      class="agreement-editor min-h-[320px] max-h-[560px] overflow-y-auto px-5 py-4 text-[14px] leading-[1.9] focus:outline-none"
      :contenteditable="!disabled"
      :data-disabled="disabled"
      @input="onInput"
      @paste="onPaste"
      @drop.prevent
    />
    <Dialog v-model:open="linkOpen">
      <DialogContent>
        <DialogHeader>
          <DialogTitle>插入协议链接</DialogTitle>
          <DialogDescription>选择正文文字后填写完整的 http(s) 地址。</DialogDescription>
        </DialogHeader>
        <form class="flex flex-col gap-4" @submit.prevent="applyLink">
          <FieldGroup>
            <Field :data-invalid="Boolean(linkError)">
              <FieldLabel for="agreement-link">链接地址</FieldLabel>
              <Input id="agreement-link" v-model="linkUrl" type="url" :aria-invalid="Boolean(linkError)" />
              <FieldDescription v-if="linkError" role="alert">{{ linkError }}</FieldDescription>
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" @click="linkOpen = false">取消</Button>
            <Button type="submit">插入链接</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </div>
</template>

<style scoped>
.agreement-editor[data-disabled='true'] {
  pointer-events: none;
  opacity: 0.7;
}
.agreement-editor h2 {
  margin: 18px 0 8px;
  font-size: 17px;
  font-weight: 700;
}
.agreement-editor h3 {
  margin: 14px 0 6px;
  font-size: 15px;
  font-weight: 700;
}
.agreement-editor blockquote {
  margin: 10px 0;
  padding: 8px 12px;
  border-left: 3px solid var(--line-strong, #d8cfc2);
  background: var(--muted, #f6f1ea);
}
.agreement-editor ul,
.agreement-editor ol {
  margin: 8px 0;
  padding-left: 22px;
}
.agreement-editor a {
  color: #a84f26;
  text-decoration: underline;
}
</style>
