<script setup lang="ts">
/**
 * 后台登录。
 * 服务端已接入 admin_users + BCrypt 真实校验（禁用账号、失败次数限制都会返回明确文案），
 * 这里把服务端的 message 直接 toast 给操作者，不再一律说“账号密码错误”，也不在页面内展示。
 *
 * 校验与后台其他表单同一套：vee-validate + zod；字段布局走 FormField（FieldGroup + Field）。
 * 品牌资产（金色谛听徽标、衬线字样、DÌ TĪNG）按定稿保留，颜色改走语义令牌。
 */
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import { z } from 'zod'
import { ApiError, request } from '@/api/request'
import FormField from '@/components/FormField.vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Spinner } from '@/components/ui/spinner'
import { useUserStore } from '@/stores/user'
import { useViewStore } from '@/stores/view'
import type { LoginResult } from '@/types/api'

const router = useRouter()
const userStore = useUserStore()
const view = useViewStore()

const schema = toTypedSchema(z.object({
  username: z.string().min(1, '请输入账号'),
  password: z.string().min(1, '请输入密码'),
}))

const { handleSubmit, errors, defineField, isSubmitting } = useForm({
  validationSchema: schema,
  initialValues: { username: '', password: '' },
})

const [username] = defineField('username')
const [password] = defineField('password')

const onSubmit = handleSubmit(async (values) => {
  try {
    const result = await request<LoginResult>({
      url: '/api/admin/login',
      method: 'POST',
      data: { ...values },
    })
    userStore.setLogin(result)
    // 投资人账号默认落到聚合脱敏视图
    view.setMode(result.role === 'INVESTOR' ? 'ir' : 'ops')
    router.replace('/overview')
  }
  catch (e) {
    toast.error(e instanceof ApiError ? e.message : '登录失败，请检查账号密码')
  }
})
</script>

<template>
  <div class="flex min-h-screen items-center justify-center bg-background">
    <Card class="w-[340px] gap-0 rounded-2xl py-0">
      <CardHeader class="flex flex-col items-center gap-0 px-8 pt-8 text-center">
        <div class="mb-3 grid size-10 place-items-center rounded-full border border-gold-deep bg-gold/10 text-gold">
          <svg aria-hidden="true" fill="none" height="20" viewBox="0 0 40 40" width="20">
            <path d="M12 25c1.5-3.5 4.5-5.5 8-5.5s6.5 2 8 5.5c-2 1-4 1.5-8 1.5s-6-.5-8-1.5z" fill="currentColor" />
            <path d="M13.5 19.5c0-3.5 2-6 6.5-6s6.5 2.5 6.5 6" stroke="currentColor" stroke-linecap="round" stroke-width="1.2" />
          </svg>
        </div>
        <CardTitle class="font-heading text-xl font-semibold tracking-[4px]">
          谛听
        </CardTitle>
        <CardDescription class="mt-1 text-[9px] tracking-[3px] text-gold">
          DÌ TĪNG
        </CardDescription>
        <span class="mt-2.5 rounded-md border border-line-strong px-2.5 py-0.5 text-[11px] tracking-[1px] text-muted-foreground">
          管理后台
        </span>
      </CardHeader>

      <CardContent class="px-8 pt-7 pb-7">
        <form class="flex flex-col gap-3" novalidate @submit="onSubmit">
          <FormField name="username" label="账号" :error="errors.username" hide-label required>
            <Input
              v-model="username"
              type="text"
              placeholder="账号"
              autocomplete="username"
              :aria-invalid="Boolean(errors.username)"
              :disabled="isSubmitting"
            />
          </FormField>

          <FormField name="password" label="密码" :error="errors.password" hide-label required>
            <Input
              v-model="password"
              type="password"
              placeholder="密码"
              autocomplete="current-password"
              :aria-invalid="Boolean(errors.password)"
              :disabled="isSubmitting"
            />
          </FormField>

          <Button type="submit" class="mt-1 h-11 w-full" :disabled="isSubmitting">
            <Spinner v-if="isSubmitting" data-icon="inline-start" aria-label="登录中" />
            {{ isSubmitting ? '登录中…' : '登录' }}
          </Button>
        </form>

        <p class="mt-5 text-[11px] leading-[1.8] text-muted-foreground">
          个体数据在任何视图中均不可导出。需要听录音原文的情形走单独授权并留审计日志。
        </p>
      </CardContent>
    </Card>
  </div>
</template>
