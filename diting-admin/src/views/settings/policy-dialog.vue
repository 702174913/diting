<script setup lang="ts">
/**
 * 策略设置编辑器（弹窗）。
 *
 * 三项配置作为同一版本快照保存，带 expectedVersion 乐观锁。
 * 注意「0 表示没有免费额度，不是无限」——这是口径，不是校验细节。
 */
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { watch } from 'vue'
import { toast } from 'vue-sonner'
import { z } from 'zod'
import type { PolicyConfig } from '@/api/admin'
import { saveConfig } from '@/api/admin'
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
import { Input } from '@/components/ui/input'
import { Spinner } from '@/components/ui/spinner'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  /** 当前已加载的配置；为空表示还没读到，此时不允许打开保存 */
  config: PolicyConfig | null
}>()

const emit = defineEmits<{ (event: 'saved'): void }>()
const open = defineModel<boolean>('open', { required: true })

const userStore = useUserStore()

const schema = toTypedSchema(z.object({
  dailyFreeQuota: z.number({ invalid_type_error: '需为 0 或正整数' })
    .int('需为整数').min(0, '份数不小于 0'),
  riskThreshold: z.number({ invalid_type_error: '需为 0—100 的整数' })
    .int('需为整数').min(0, '风险阈值 0—100').max(100, '风险阈值 0—100'),
  maxRecordingMinutes: z.number({ invalid_type_error: '需为不小于 1 的整数' })
    .int('需为整数').min(1, '录音时长不小于 1 分钟'),
}))

const { handleSubmit, errors, defineField, resetForm, isSubmitting } = useForm({
  validationSchema: schema,
  initialValues: { dailyFreeQuota: 0, riskThreshold: 60, maxRecordingMinutes: 10 },
})

const [dailyFreeQuota] = defineField('dailyFreeQuota')
const [riskThreshold] = defineField('riskThreshold')
const [maxRecordingMinutes] = defineField('maxRecordingMinutes')

watch(open, (visible) => {
  if (visible && props.config) {
    resetForm({
      values: {
        dailyFreeQuota: props.config.dailyFreeQuota,
        riskThreshold: props.config.riskThreshold,
        maxRecordingMinutes: props.config.maxRecordingMinutes,
      },
    })
  }
})

const onSubmit = handleSubmit(async (form) => {
  const token = userStore.token
  const current = props.config
  if (!token || !current) {
    return
  }
  try {
    const saved = await saveConfig(token, current.version, {
      dailyFreeQuota: form.dailyFreeQuota,
      riskThreshold: form.riskThreshold,
      maxRecordingMinutes: form.maxRecordingMinutes,
    })
    toast.success(`已保存为新版本 v${saved.version}，立即对新提交生效`)
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
    <DialogContent class="sm:max-w-lg">
      <DialogHeader>
        <DialogTitle>修改策略设置</DialogTitle>
        <DialogDescription>
          三项作为同一版本快照保存，带乐观锁；保存后立即对新提交生效。
          当前版本 v{{ config?.version ?? '—' }}。
        </DialogDescription>
      </DialogHeader>

      <form class="flex flex-col gap-4" novalidate @submit="onSubmit">
        <FormField
          name="dailyFreeQuota"
          label="每日免费份数"
          :error="errors.dailyFreeQuota"
          description="0 表示没有免费额度，不是无限。"
          required
        >
          <Input
            v-model.number="dailyFreeQuota"
            type="number"
            min="0"
            step="1"
            :aria-invalid="Boolean(errors.dailyFreeQuota)"
            :disabled="isSubmitting"
          />
        </FormField>

        <FormField
          name="riskThreshold"
          label="风险阈值"
          :error="errors.riskThreshold"
          description="风险分达到或超过该值即标记为风险（0—100）。"
          required
        >
          <Input
            v-model.number="riskThreshold"
            type="number"
            min="0"
            max="100"
            step="1"
            :aria-invalid="Boolean(errors.riskThreshold)"
            :disabled="isSubmitting"
          />
        </FormField>

        <FormField
          name="maxRecordingMinutes"
          label="最长录音时长（分钟）"
          :error="errors.maxRecordingMinutes"
          required
        >
          <Input
            v-model.number="maxRecordingMinutes"
            type="number"
            min="1"
            step="1"
            :aria-invalid="Boolean(errors.maxRecordingMinutes)"
            :disabled="isSubmitting"
          />
        </FormField>

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
            {{ isSubmitting ? '保存中…' : '保存为新版本' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
