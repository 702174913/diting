<script setup lang="ts">
/**
 * 规则编辑器：新增与修改共用（修改会生成新版本，带 expectedVersion 乐观锁）。
 *
 * 表单自己拥有 useForm 与提交逻辑，父级只负责「谁被打开」和「保存后重载列表」。
 * 字段布局用 FormField（FieldGroup + Field），校验走 vee-validate + zod，与登录页同一套。
 */
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, watch } from 'vue'
import { toast } from 'vue-sonner'
import { z } from 'zod'
import type { RuleItem } from '@/api/admin'
import { createRule, updateRule } from '@/api/admin'
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
import { Field, FieldDescription } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Spinner } from '@/components/ui/spinner'
import { Textarea } from '@/components/ui/textarea'
import { RULE_SCOPE_TEXT as ROLE_LABELS, MATCH_TYPE_TEXT as TYPE_LABELS } from '@/lib/enums'
import { ROLE_TARGETING_ENABLED } from '@/lib/features'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  rule: RuleItem | null
}>()

const emit = defineEmits<{ (event: 'saved'): void }>()
const open = defineModel<boolean>('open', { required: true })
const userStore = useUserStore()

/** 关键词用顿号分隔的自由文本，落库前再拆成数组 —— 与批量导入模板同一口径 */
function splitKeywords(value: string | undefined): string[] {
  return (value ?? '').split(/[、,，;；|\s]+/).map(part => part.trim()).filter(Boolean)
}

const schema = toTypedSchema(z.object({
  code: z.string().min(1, '规则编码不能为空').max(64, '规则编码最长 64 位'),
  name: z.string().min(1, '规则名称不能为空').max(200, '规则名称最长 200 位'),
  groupName: z.string().max(100, '分组名最长 100 位'),
  matchType: z.string().min(1, '规则类型不能为空'),
  roleScope: z.string().min(1, '目标角色不能为空'),
  triggerRole: z.string(),
  windowSize: z.number({ invalid_type_error: '窗口需为 1-20 的整数' }).int('窗口需为整数'),
  deduct: z.number({ invalid_type_error: '扣分需为 0-100 的整数' }).int('扣分需为整数').min(0, '扣分需为 0-100 的整数').max(100, '扣分需为 0-100 的整数'),
  keywords: z.string().min(1, '至少需要一个关键词'),
  expectedKeywords: z.string(),
  description: z.string().min(1, '规则说明不能为空（要写清平台规则依据）'),
}))

const { handleSubmit, errors, defineField, resetForm, values, isSubmitting } = useForm({
  validationSchema: schema,
  initialValues: {
    code: '',
    name: '',
    groupName: '默认分组',
    matchType: 'hit',
    roleScope: ROLE_TARGETING_ENABLED ? 'AGENT' : 'ANY',
    triggerRole: ROLE_TARGETING_ENABLED ? 'AGENT' : 'ANY',
    windowSize: 3,
    deduct: 20,
    keywords: '',
    expectedKeywords: '',
    description: '',
  },
})

const [code] = defineField('code')
const [name] = defineField('name')
const [groupName] = defineField('groupName')
const [matchType] = defineField('matchType')
const [roleScope] = defineField('roleScope')
const [triggerRole] = defineField('triggerRole')
const [windowSize] = defineField('windowSize')
const [deduct] = defineField('deduct')
const [keywords] = defineField('keywords')
const [expectedKeywords] = defineField('expectedKeywords')
const [description] = defineField('description')

const isEdit = computed(() => Boolean(props.rule?.ruleId))
const needsTrigger = computed(() => values.matchType === 'require')

const keywordLabel = computed(() => (
  values.matchType === 'must-have' ? '必需词' : values.matchType === 'require' ? '触发词' : '命中词'
))

const typeGuide = computed(() => {
  switch (values.matchType) {
    case 'require':
      return ROLE_TARGETING_ENABLED
        ? '触发角色说出任一触发词后，随后指定条数的目标角色发言中须出现任一应答词；未出现才扣分。窗口只计算目标角色的发言。'
        : '任一句话出现任一触发词后，随后指定条数的发言中须出现任一应答词；未出现才扣分。'
    case 'must-have':
      return ROLE_TARGETING_ENABLED
        ? '整通话中，指定角色至少说出一个必需词；该角色有发言却始终没说到，才记录通话级缺失。'
        : '整通话中至少出现一个必需词；通篇没说到才记录通话级缺失。'
    default:
      return ROLE_TARGETING_ENABLED
        ? '指定角色的任一句话出现任一关键词，即记录一次命中证据。适合识别禁语或敏感表述。'
        : '任一句话出现任一关键词，即记录一次命中证据。适合识别禁语或敏感表述。'
  }
})

watch(open, (visible) => {
  if (!visible) {
    return
  }
  const rule = props.rule
  resetForm({
    values: rule
      ? {
          code: rule.code,
          name: rule.name,
          groupName: rule.groupName ?? '默认分组',
          matchType: rule.matchType,
          roleScope: ROLE_TARGETING_ENABLED ? rule.roleScope : 'ANY',
          triggerRole: ROLE_TARGETING_ENABLED ? (rule.triggerRole ?? 'AGENT') : 'ANY',
          windowSize: rule.windowSize ?? 3,
          deduct: rule.deduct,
          keywords: (rule.keywords ?? []).join('、'),
          expectedKeywords: (rule.expectedKeywords ?? []).join('、'),
          description: rule.description,
        }
      : {
          code: '',
          name: '',
          groupName: '默认分组',
          matchType: 'hit',
          roleScope: ROLE_TARGETING_ENABLED ? 'AGENT' : 'ANY',
          triggerRole: ROLE_TARGETING_ENABLED ? 'AGENT' : 'ANY',
          windowSize: 3,
          deduct: 20,
          keywords: '',
          expectedKeywords: '',
          description: '',
        },
  })
})

const onSubmit = handleSubmit(async (form) => {
  const token = userStore.token
  if (!token) {
    return
  }
  const payload = {
    code: form.code.trim(),
    name: form.name.trim(),
    groupName: form.groupName?.trim() || '默认分组',
    description: form.description.trim(),
    matchType: form.matchType,
    // 目标角色功能隐藏期间固定「任意角色」，引擎也不再区分说话人（见 lib/features.ts）
    roleScope: ROLE_TARGETING_ENABLED ? form.roleScope : 'ANY',
    triggerRole: form.matchType === 'require'
      ? (ROLE_TARGETING_ENABLED ? form.triggerRole : 'ANY')
      : null,
    keywords: splitKeywords(form.keywords),
    expectedKeywords: form.matchType === 'require' ? splitKeywords(form.expectedKeywords) : [],
    windowSize: form.matchType === 'require' ? form.windowSize : null,
    deduct: form.deduct,
  }
  try {
    if (props.rule?.ruleId) {
      const updated = await updateRule(token, props.rule.ruleId, props.rule.version, payload)
      toast.success(`已保存为第 ${updated.version} 版（历史版本保留）`)
    }
    else {
      const created = await createRule(token, payload)
      toast.success(`已创建（第 ${created.version} 版）`)
    }
    open.value = false
    emit('saved')
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '保存失败，请稍后重试')
  }
})
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="sm:max-w-3xl">
      <DialogHeader>
        <DialogTitle>{{ isEdit ? '修改规则（生成新版本）' : '新增规则' }}</DialogTitle>
        <DialogDescription>
          停用只影响未来的规则集合，历史版本与历史报告保持不变；保存会追加新版本而不是覆盖。
        </DialogDescription>
      </DialogHeader>

      <form class="flex flex-col gap-4" novalidate @submit="onSubmit">
        <div class="grid gap-4 sm:grid-cols-2">
          <FormField name="code" label="规则编码" :error="errors.code" required>
            <Input
              v-model="code"
              maxlength="64"
              :aria-invalid="Boolean(errors.code)"
              :disabled="isSubmitting"
            />
          </FormField>
          <FormField name="name" label="规则名称" :error="errors.name" required>
            <Input
              v-model="name"
              maxlength="200"
              :aria-invalid="Boolean(errors.name)"
              :disabled="isSubmitting"
            />
          </FormField>
          <FormField name="groupName" label="分组" :error="errors.groupName">
            <Input
              v-model="groupName"
              maxlength="100"
              placeholder="默认分组"
              :aria-invalid="Boolean(errors.groupName)"
              :disabled="isSubmitting"
            />
          </FormField>
          <FormField name="matchType" label="规则类型" :error="errors.matchType" required>
            <Select v-model="matchType" :disabled="isSubmitting">
              <SelectTrigger class="w-full">
                <SelectValue placeholder="选择规则类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  <SelectItem
                    v-for="(label, value) in TYPE_LABELS"
                    :key="value"
                    :value="value"
                  >
                    {{ label }}
                  </SelectItem>
                </SelectGroup>
              </SelectContent>
            </Select>
          </FormField>
          <FormField v-if="ROLE_TARGETING_ENABLED" name="roleScope" label="目标角色" :error="errors.roleScope" required>
            <Select v-model="roleScope" :disabled="isSubmitting">
              <SelectTrigger class="w-full">
                <SelectValue placeholder="选择目标角色" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  <SelectItem
                    v-for="(label, value) in ROLE_LABELS"
                    :key="value"
                    :value="value"
                  >
                    {{ label }}
                  </SelectItem>
                </SelectGroup>
              </SelectContent>
            </Select>
          </FormField>
          <FormField name="deduct" label="扣分（0—100）" :error="errors.deduct" required>
            <Input
              v-model.number="deduct"
              type="number"
              min="0"
              max="100"
              step="1"
              :aria-invalid="Boolean(errors.deduct)"
              :disabled="isSubmitting"
            />
          </FormField>
          <FormField
            v-if="needsTrigger && ROLE_TARGETING_ENABLED"
            name="triggerRole"
            label="触发角色"
            :error="errors.triggerRole"
            required
          >
            <Select v-model="triggerRole" :disabled="isSubmitting">
              <SelectTrigger class="w-full">
                <SelectValue placeholder="选择触发角色" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  <SelectItem value="AGENT">
                    催收方
                  </SelectItem>
                  <SelectItem value="CUSTOMER">
                    客户
                  </SelectItem>
                </SelectGroup>
              </SelectContent>
            </Select>
          </FormField>
          <FormField
            v-if="needsTrigger"
            name="windowSize"
            :label="ROLE_TARGETING_ENABLED ? '目标角色发言窗口（1—20 条）' : '应答窗口（1—20 条）'"
            :error="errors.windowSize"
            required
          >
            <Input
              v-model.number="windowSize"
              type="number"
              min="1"
              max="20"
              step="1"
              :aria-invalid="Boolean(errors.windowSize)"
              :disabled="isSubmitting"
            />
          </FormField>
          <FormField
            name="keywords"
            :label="`${keywordLabel}（顿号或逗号分隔，任一命中即可）`"
            :error="errors.keywords"
            :class="needsTrigger ? undefined : 'sm:col-span-2'"
            required
          >
            <Input
              v-model="keywords"
              placeholder="例如：催收、逾期"
              :aria-invalid="Boolean(errors.keywords)"
              :disabled="isSubmitting"
            />
          </FormField>
          <FormField
            v-if="needsTrigger"
            name="expectedKeywords"
            label="应答词（顿号或逗号分隔，任一出现即通过）"
            :error="errors.expectedKeywords"
            required
          >
            <Input
              v-model="expectedKeywords"
              placeholder="例如：已了解、已确认"
              :aria-invalid="Boolean(errors.expectedKeywords)"
              :disabled="isSubmitting"
            />
          </FormField>
        </div>

        <Field>
          <FieldDescription>
            <b>{{ TYPE_LABELS[values.matchType] ?? '规则类型' }}</b> —— {{ typeGuide }}
          </FieldDescription>
          <FieldDescription>
            {{ ROLE_TARGETING_ENABLED
              ? '目标角色：命中／必需表述检查谁的发言；“触发后需应答”检查谁的回应。“任意角色”不区分说话人；指定角色需先确认说话人身份。“未知角色”仅匹配标记为未知的说话人。'
              : '目标角色功能暂已隐藏：所有规则按「任意角色」判读，不区分说话人（列表与版本历史也不再展示角色）。' }}
          </FieldDescription>
        </Field>

        <Separator />

        <FormField name="description" label="规则说明（写清平台规则依据）" :error="errors.description" required>
          <Textarea
            v-model="description"
            rows="2"
            :aria-invalid="Boolean(errors.description)"
            :disabled="isSubmitting"
          />
        </FormField>

        <p class="text-xs text-muted-foreground">
          同一规则在一通电话里最多扣分一次，重复命中仍保留全部证据。
        </p>

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
            {{ isSubmitting ? '保存中…' : (isEdit ? '保存为新版本' : '创建规则') }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
