<script setup lang="ts">
/**
 * 账号编辑器：新增与修改共用。
 *
 * 密码强度由服务端 PasswordPolicy 校验（≥10 位、含字母与数字、不含用户名、弱密码黑名单），
 * 这里只做前端的基本提示与必填判断 —— 真正的判定在服务端，且它的 message 可以直接展示。
 */
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, watch } from 'vue'
import { toast } from 'vue-sonner'
import { z } from 'zod'
import { createAccount, updateAccount } from '@/api/admin'
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
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Spinner } from '@/components/ui/spinner'
import { Switch } from '@/components/ui/switch'
import { ADMIN_ROLE_TEXT as ROLE_LABELS } from '@/lib/enums'
import { useUserStore } from '@/stores/user'
import type { AdminAccountItem } from '@/api/admin'

const props = defineProps<{
  account: AdminAccountItem | null
}>()

const emit = defineEmits<{ (event: 'saved'): void }>()
const open = defineModel<boolean>('open', { required: true })

const userStore = useUserStore()

const isEdit = computed(() => Boolean(props.account?.id))

const schema = toTypedSchema(z.object({
  username: z.string().min(1, '用户名不能为空').max(64, '用户名最长 64 位'),
  password: z.string().max(72, '密码最长 72 位'),
  nickname: z.string().max(64, '昵称最长 64 位'),
  role: z.string().min(1, '角色不能为空'),
  enabled: z.boolean(),
}).refine(
  form => isEdit.value || form.password.length > 0,
  { path: ['password'], message: '新建账号必须设置密码' },
).refine(
  form => form.password.length === 0 || form.password.length >= 10,
  { path: ['password'], message: '密码至少 10 位' },
))

const { handleSubmit, errors, defineField, resetForm, isSubmitting } = useForm({
  validationSchema: schema,
  initialValues: { username: '', password: '', nickname: '', role: 'OPERATOR', enabled: true },
})

const [username] = defineField('username')
const [password] = defineField('password')
const [nickname] = defineField('nickname')
const [role] = defineField('role')
const [enabled] = defineField('enabled')

watch(open, (visible) => {
  if (!visible) {
    return
  }
  resetForm({
    values: props.account
      ? {
          username: props.account.username,
          password: '',
          nickname: props.account.nickname,
          role: props.account.role,
          enabled: props.account.enabled,
        }
      : { username: '', password: '', nickname: '', role: 'OPERATOR', enabled: true },
  })
})

const onSubmit = handleSubmit(async (form) => {
  const token = userStore.token
  if (!token) {
    return
  }
  try {
    if (props.account?.id) {
      await updateAccount(token, props.account.id, {
        nickname: form.nickname.trim() || undefined,
        role: form.role,
        enabled: form.enabled,
        password: form.password.trim() || undefined,
      })
      toast.success('账号已更新')
    }
    else {
      await createAccount(token, {
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim() || undefined,
        role: form.role,
      })
      toast.success('账号已创建')
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
    <DialogContent class="sm:max-w-lg">
      <DialogHeader>
        <DialogTitle>{{ isEdit ? '修改账号' : '新增后台账号' }}</DialogTitle>
        <DialogDescription>
          {{ isEdit
            ? '留空密码表示不改密码；改动角色或禁用会立即对该账号的后续请求生效。'
            : '密码至少 10 位、含字母与数字，且不能包含用户名。' }}
        </DialogDescription>
      </DialogHeader>

      <form class="flex flex-col gap-4" novalidate @submit="onSubmit">
        <FormField name="username" label="用户名" :error="errors.username" required>
          <Input
            v-model="username"
            maxlength="64"
            autocomplete="off"
            :disabled="isEdit || isSubmitting"
            :aria-invalid="Boolean(errors.username)"
          />
        </FormField>

        <FormField
          name="nickname"
          label="昵称"
          :error="errors.nickname"
          :description="isEdit ? undefined : '留空则与用户名相同'"
        >
          <Input
            v-model="nickname"
            maxlength="64"
            :disabled="isSubmitting"
            :aria-invalid="Boolean(errors.nickname)"
          />
        </FormField>

        <FormField name="role" label="角色" :error="errors.role" required>
          <Select v-model="role" :disabled="isSubmitting">
            <SelectTrigger class="w-full">
              <SelectValue placeholder="选择角色" />
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

        <FormField
          name="password"
          :label="isEdit ? '重置密码（留空不改）' : '密码'"
          :error="errors.password"
          :required="!isEdit"
        >
          <Input
            v-model="password"
            type="password"
            maxlength="72"
            autocomplete="new-password"
            :disabled="isSubmitting"
            :aria-invalid="Boolean(errors.password)"
          />
        </FormField>

        <Field v-if="isEdit" orientation="horizontal">
          <FieldLabel for="account-enabled">
            启用该账号
          </FieldLabel>
          <Switch
            id="account-enabled"
            v-model="enabled"
            :disabled="isSubmitting"
          />
          <FieldDescription>
            禁用后该账号的后续请求立即被拒（403），已签发的 token 不作废但会被拦。
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
