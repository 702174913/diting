<script setup lang="ts">
/**
 * 条款编辑器：新增与修改共用。
 *
 * 字段与服务端 LegalClauseSaveRequest 对齐（法规名称 ≤200、条款号 ≤64、原文非空、备注 ≤500），
 * (法规名称, 条款号) 组合唯一 —— 重复时服务端返回 409，message 直接展示，前端不猜。
 * 「停用」不在这个弹窗里做：它是有级联影响的操作，走列表页带确认的独立动作。
 */
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, watch } from 'vue'
import { toast } from 'vue-sonner'
import { z } from 'zod'
import { createLegalClause, updateLegalClause } from '@/api/admin'
import { ApiError } from '@/api/request'
import FormField from '@/components/FormField.vue'
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
import { Input } from '@/components/ui/input'
import { Spinner } from '@/components/ui/spinner'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { useUserStore } from '@/stores/user'
import type { LegalClauseItem } from '@/api/admin'

const props = defineProps<{
  clause: LegalClauseItem | null
}>()

const emit = defineEmits<{ (event: 'saved'): void }>()
const open = defineModel<boolean>('open', { required: true })

const userStore = useUserStore()

const isEdit = computed(() => Boolean(props.clause?.id))

/** 纯空白等于空：服务端是 @NotBlank，别让「   」走到 400 才被拒 */
function filled(value: string) {
  return value.trim().length > 0
}

const schema = toTypedSchema(z.object({
  lawName: z.string().max(200, '法规名称最长 200 位')
    .refine(filled, { message: '法规名称不能为空' }),
  articleNo: z.string().max(64, '条款号最长 64 位')
    .refine(filled, { message: '条款号不能为空' }),
  content: z.string().refine(filled, { message: '条款原文不能为空' }),
  remark: z.string().max(500, '备注最长 500 位'),
  enabled: z.boolean(),
}))

const { handleSubmit, errors, defineField, resetForm, isSubmitting } = useForm({
  validationSchema: schema,
  initialValues: { lawName: '', articleNo: '', content: '', remark: '', enabled: true },
})

const [lawName] = defineField('lawName')
const [articleNo] = defineField('articleNo')
const [content] = defineField('content')
const [remark] = defineField('remark')
const [enabled] = defineField('enabled')

watch(open, (visible) => {
  if (!visible) {
    return
  }
  resetForm({
    values: props.clause
      ? {
          lawName: props.clause.lawName,
          articleNo: props.clause.articleNo,
          content: props.clause.content,
          remark: props.clause.remark ?? '',
          enabled: props.clause.enabled,
        }
      : { lawName: '', articleNo: '', content: '', remark: '', enabled: true },
  })
})

const onSubmit = handleSubmit(async (form) => {
  const token = userStore.token
  if (!token) {
    return
  }
  const payload = {
    lawName: form.lawName.trim(),
    articleNo: form.articleNo.trim(),
    content: form.content.trim(),
    remark: form.remark.trim() || null,
    enabled: form.enabled,
  }
  try {
    if (props.clause?.id) {
      await updateLegalClause(token, props.clause.id, payload)
      toast.success('条款已更新（引用它的报告展示同步变化）')
    }
    else {
      await createLegalClause(token, payload)
      toast.success('条款已新增')
    }
    open.value = false
    emit('saved')
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '保存失败')
  }
})
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="sm:max-w-2xl">
      <DialogHeader>
        <DialogTitle>{{ isEdit ? '修改法律条款' : '新增法律条款' }}</DialogTitle>
        <DialogDescription>
          {{ isEdit
            ? '原文与法规名称会立即影响所有引用该条款的报告（报告不存依据快照）。'
            : '新增后要在「规则库 → 绑定条款」里选上，才会随报告下发。' }}
        </DialogDescription>
      </DialogHeader>

      <form class="flex flex-col gap-4" novalidate @submit="onSubmit">
        <div class="grid gap-4 sm:grid-cols-2">
          <FormField name="lawName" label="法规名称" :error="errors.lawName" required>
            <Input
              v-model="lawName"
              maxlength="200"
              placeholder="如：个人信息保护法"
              :disabled="isSubmitting"
              :aria-invalid="Boolean(errors.lawName)"
            />
          </FormField>

          <FormField name="articleNo" label="条款号" :error="errors.articleNo" required>
            <Input
              v-model="articleNo"
              maxlength="64"
              placeholder="如：第十三条"
              :disabled="isSubmitting"
              :aria-invalid="Boolean(errors.articleNo)"
            />
          </FormField>
        </div>

        <FormField
          name="content"
          label="条款原文"
          :error="errors.content"
          :description="`报告与 PDF 里原样展示，共 ${content?.length ?? 0} 字`"
          required
        >
          <Textarea
            v-model="content"
            rows="6"
            placeholder="粘贴条款原文"
            :disabled="isSubmitting"
            :aria-invalid="Boolean(errors.content)"
          />
        </FormField>

        <FormField
          name="remark"
          label="备注"
          :error="errors.remark"
          description="内部说明，不随报告下发"
        >
          <Input
            v-model="remark"
            maxlength="500"
            :disabled="isSubmitting"
            :aria-invalid="Boolean(errors.remark)"
          />
        </FormField>

        <Field orientation="horizontal">
          <FieldLabel for="clause-enabled">
            启用该条款
          </FieldLabel>
          <Switch
            id="clause-enabled"
            v-model="enabled"
            :disabled="isSubmitting"
          />
          <FieldDescription>
            停用只是不能再被新绑定：已有绑定照旧展示，报告里的依据不受影响。
          </FieldDescription>
        </Field>

        <DialogFooter>
          <Button
            variant="outline"
            type="button"
            :disabled="isSubmitting"
            @click="open = false"
          >
            取消
          </Button>
          <Button type="submit" :disabled="isSubmitting">
            <Spinner v-if="isSubmitting" data-icon="inline-start" aria-label="保存中" />
            {{ isSubmitting ? '保存中…' : '保存' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
