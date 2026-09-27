<script setup lang="ts">
/**
 * 协议配置（S19）。
 *
 * 4 份用户协议（注册强制勾选）的标题与正文，富文本维护，每次保存版本号 +1。
 * 读失败时禁用保存（与策略设置同一条数据安全规则：拿不到真实内容时不允许覆盖）。
 * 写仅 ADMIN（服务端强制）；OPERATOR 只读。
 */
import { computed, onMounted, ref } from 'vue'
import { FileTextIcon } from '@lucide/vue'
import { toast } from 'vue-sonner'
import RichTextEditor from '@/components/RichTextEditor.vue'
import { fetchAgreements, saveAgreement } from '@/api/admin'
import type { AgreementItem } from '@/api/admin'
import { ApiError } from '@/api/request'
import EmptyState from '@/components/EmptyState.vue'
import Hint from '@/components/Hint.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const items = ref<AgreementItem[]>([])
const loaded = ref(false)
const loadError = ref(false)
const activeKey = ref('')
const saving = ref(false)

/** 编辑态与服务端快照分离：切 tab 不丢未保存的修改 */
const drafts = ref<Record<string, { title: string, contentHtml: string }>>({})

const isAdmin = computed(() => userStore.role === 'ADMIN')
const canEdit = computed(() => loaded.value && !loadError.value && isAdmin.value)
const active = computed(() => items.value.find(item => item.key === activeKey.value) ?? null)
const draft = computed(() => (activeKey.value ? drafts.value[activeKey.value] : undefined))
const dirty = computed(() => {
  if (!active.value || !draft.value) {
    return false
  }
  return draft.value.title !== active.value.title || draft.value.contentHtml !== active.value.contentHtml
})

async function load() {
  const token = userStore.token
  if (!token) {
    return
  }
  loadError.value = false
  try {
    items.value = await fetchAgreements(token)
    drafts.value = Object.fromEntries(items.value.map(item => [item.key, {
      title: item.title,
      contentHtml: item.contentHtml,
    }]))
    if (!items.value.some(item => item.key === activeKey.value)) {
      activeKey.value = items.value[0]?.key ?? ''
    }
    loaded.value = true
  }
  catch (e) {
    loaded.value = false
    items.value = []
    loadError.value = true
    toast.error(e instanceof ApiError ? e.message : '协议读取失败')
  }
}

async function save() {
  if (!canEdit.value || !active.value || !draft.value || saving.value) {
    return
  }
  if (!draft.value.title.trim() || !draft.value.contentHtml.trim()) {
    toast.error('标题与正文不能为空')
    return
  }
  const key = active.value.key
  const submitted = { ...draft.value }
  saving.value = true
  try {
    const saved = await saveAgreement(userStore.token, key, submitted.title.trim(), submitted.contentHtml)
    const target = items.value.find(item => item.key === key)
    if (target) {
      Object.assign(target, saved)
    }
    // 等待响应时切换协议或继续编辑，不能把响应覆盖到别的协议/新的未保存草稿。
    if (drafts.value[key]?.title === submitted.title && drafts.value[key]?.contentHtml === submitted.contentHtml) {
      drafts.value[key] = { title: saved.title, contentHtml: saved.contentHtml }
    }
    toast.success(`已保存，版本 v${saved.version}`)
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '保存失败')
  }
  finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="flex flex-col gap-4">
    <PageHeader
      title="协议配置"
      description="用户注册前必须勾选的 4 份协议。富文本编辑，保存后版本号 +1；注册时快照版本号供审计。"
    >
      <template #actions>
        <Badge v-if="active" variant="secondary" size="chip">
          当前版本 v{{ active.version }}
        </Badge>
        <Button :disabled="!canEdit || !dirty || saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </Button>
      </template>
    </PageHeader>

    <Hint>
      保存即对新注册用户生效；在途注册页看到的是旧版，提交时会提示重新确认，不会静默同意。
    </Hint>

    <div v-if="!loaded && !loadError" class="flex flex-col gap-2">
      <Skeleton class="h-10 w-full" />
      <Skeleton class="h-64 w-full" />
    </div>
    <EmptyState
      v-else-if="loadError"
      title="协议读取失败"
      description="拿不到真实内容时禁止编辑，请稍后重试。"
      :icon="FileTextIcon"
    >
      <Button variant="outline" size="sm" @click="load()">
        重新读取
      </Button>
    </EmptyState>
    <template v-else-if="active && draft">
      <Tabs v-model="activeKey">
        <TabsList>
          <TabsTrigger v-for="item in items" :key="item.key" :value="item.key">
            {{ item.title }}（v{{ item.version }}）
          </TabsTrigger>
        </TabsList>
        <TabsContent :value="active.key" class="mt-4 flex flex-col gap-3">
          <Panel title="标题">
            <Input v-model="draft.title" :disabled="!canEdit" maxlength="128" />
          </Panel>
          <Panel title="正文">
            <RichTextEditor v-model="draft.contentHtml" :disabled="!canEdit" />
          </Panel>
        </TabsContent>
      </Tabs>
    </template>
  </div>
</template>
