<script setup lang="ts">
/** 账号入口；微信首登须显式阅读协议并提交快照，不存储密码。 */
import { onMounted, ref } from 'vue'
import { fetchAgreementVersions, fetchAgreements } from '@/api/agreement'
import type { AgreementItem } from '@/api/agreement'
import { loginByPassword, loginByWechat, registerAccount } from '@/api/auth'
import { ApiError } from '@/api/request'
import { useUserStore } from '@/stores/user'
import { messageOf } from '@/utils/errors'

const props = defineProps<{
  visible: boolean
  purpose?: string
  standalone?: boolean
}>()
const emit = defineEmits<{
  close: []
  success: []
}>()

const userStore = useUserStore()
const registering = ref(false)
const username = ref('')
const password = ref('')
const busy = ref(false)
const errorText = ref('')
const agreed = ref(false)
const agreementList = ref<AgreementItem[]>([])
const seenVersions = ref<Record<string, number>>({})
const agreementsLoading = ref(false)
const isWeChatMiniProgram = ref(false)
// #ifdef MP-WEIXIN
isWeChatMiniProgram.value = true
// #endif
onMounted(() => {
  if (isWeChatMiniProgram.value) {
    void loadAgreements()
  }
})

function close() {
  if (busy.value || props.standalone) {
    return
  }
  password.value = ''
  errorText.value = ''
  emit('close')
}

function toggleMode() {
  registering.value = !registering.value
  password.value = ''
  errorText.value = ''
  if (registering.value && agreementList.value.length === 0 && !agreementsLoading.value) {
    loadAgreements()
  }
}

/** 注册及微信首登展示协议清单与版本快照；前端未加载完整时不允许勾选。 */
async function loadAgreements() {
  agreementsLoading.value = true
  try {
    const [list, versions] = await Promise.all([fetchAgreements(), fetchAgreementVersions()])
    agreementList.value = list
    seenVersions.value = versions
  }
  catch (error) {
    errorText.value = messageOf(error)
  }
  finally {
    agreementsLoading.value = false
  }
}

function toggleAgreement() {
  // 数量以接口下发的清单为准：后台增删协议后这里不该再写死 4
  if (!agreementsLoading.value && agreementList.value.length > 0
    && Object.keys(seenVersions.value).length === agreementList.value.length) {
    agreed.value = !agreed.value
  }
}

function openAgreement(key: string) {
  uni.navigateTo({ url: `/pages/agreement/index?key=${encodeURIComponent(key)}` })
}

function onUsernameInput(event: { detail: { value: string } }) {
  username.value = event.detail.value
}

function onPasswordInput(event: { detail: { value: string } }) {
  password.value = event.detail.value
}

function currentInput(index: number, fallback: string): string {
  // H5 自动填充及快速点击可能晚于 uni-input 的 v-model 事件；提交时以实际输入框为准。
  // #ifdef H5
  const input = document.querySelectorAll<HTMLInputElement>('.auth-sheet .auth-input input')[index]
  if (input) {
    return input.value
  }
  // #endif
  return fallback
}

async function submitWechat() {
  if (busy.value || !props.visible) {
    return
  }
  if (!agreed.value || agreementList.value.length === 0
    || Object.keys(seenVersions.value).length !== agreementList.value.length) {
    errorText.value = '请先阅读并同意当前全部协议'
    return
  }
  busy.value = true
  errorText.value = ''
  try {
    const code = await new Promise<string>((resolve, reject) => {
      uni.login({
        provider: 'weixin',
        success: result => (result.code ? resolve(result.code) : reject(new Error('未取得微信授权码'))),
        fail: reject,
      })
    })
    const result = await loginByWechat(code, true, seenVersions.value)
    userStore.setLogin(result.token)
    userStore.userInfo = result.userInfo
    emit('success')
  }
  catch (error) {
    errorText.value = messageOf(error)
    if (error instanceof ApiError && error.code === 409) {
      agreed.value = false
      void loadAgreements()
    }
  }
  finally {
    busy.value = false
  }
}

async function submit() {
  if (busy.value || !props.visible) {
    return
  }
  const name = currentInput(0, username.value).trim()
  const secret = currentInput(1, password.value)
  if (!name || !secret) {
    errorText.value = '请填写账号和密码'
    return
  }
  if (registering.value && (name.length < 4 || name.length > 32 || secret.length < 8 || secret.length > 64)) {
    errorText.value = '账号需 4–32 位，密码需 8–64 位'
    return
  }
  if (registering.value && !agreed.value) {
    errorText.value = '请先阅读并勾选同意用户协议'
    return
  }
  busy.value = true
  errorText.value = ''
  try {
    // 注册必须带同意快照；版本对不上（admin 刚更新过协议）时后端 409，前端提示重看
    const result = registering.value
      ? await registerAccount(name, secret, agreed.value, seenVersions.value)
      : await loginByPassword(name, secret)
    userStore.setLogin(result.token)
    userStore.userInfo = result.userInfo
    password.value = ''
    emit('success')
  }
  catch (error) {
    errorText.value = messageOf(error)
  }
  finally {
    busy.value = false
  }
}
</script>

<template>
  <view v-if="visible" class="auth-mask" :class="{ 'auth-page': standalone }" @click="close">
    <view class="auth-sheet" @click.stop>
      <view class="auth-head">
        <text class="auth-kicker">谛听 · 你的记录</text>
        <text v-if="!standalone" class="auth-close" role="button" tabindex="0" @click="close">关闭</text>
      </view>
      <text class="auth-title">{{ registering ? '先建一个账号' : '接着听，接着记' }}</text>
      <text class="auth-desc">{{ purpose || '登录后，只有你能看见自己的记录。' }}</text>

      <view class="auth-form">
        <text class="auth-label">账号</text>
        <input
          v-model="username"
          class="auth-input"
          type="text"
          maxlength="32"
          autocomplete="username"
          placeholder="输入你的账号"
          @input="onUsernameInput"
        >
        <text class="auth-label">密码</text>
        <input
          v-model="password"
          class="auth-input"
          type="password"
          maxlength="64"
          :autocomplete="registering ? 'new-password' : 'current-password'"
          placeholder="输入密码"
          @input="onPasswordInput"
          @confirm="submit"
        >
        <text v-if="errorText" class="auth-error" role="alert">{{ errorText }}</text>
        <view v-if="registering || isWeChatMiniProgram" class="auth-consent">
          <view
            class="auth-checkbox"
            :class="{ checked: agreed }"
            role="checkbox"
            :aria-checked="agreed"
            tabindex="0"
            @click="toggleAgreement"
          >
            <text v-if="agreed" class="auth-checkmark">✓</text>
          </view>
          <text class="auth-consent-text">
            我已阅读并同意
            <template v-for="(item, index) in agreementList" :key="item.key">
              <text class="auth-link" @click="openAgreement(item.key)">《{{ item.title }}》</text>
              <text v-if="index < agreementList.length - 1">、</text>
            </template>
            <text v-if="agreementList.length === 0">用户协议（正在载入…）</text>
          </text>
        </view>
        <!-- #ifdef MP-WEIXIN -->
        <button class="btn btn-main btn-block auth-submit" :disabled="busy || agreementsLoading || agreementList.length === 0" @click="submitWechat">
          {{ busy ? '请稍候…' : '同意并用微信登录' }}
        </button>
        <!-- #endif -->
        <button class="btn btn-main btn-block auth-submit" :disabled="busy" @click="submit">
          {{ busy ? '请稍候…' : registering ? '注册并登录' : '账号密码登录' }}
        </button>
        <button class="auth-switch" :disabled="busy" @click="toggleMode">
          {{ registering ? '已有账号？去登录' : '还没有账号？先注册' }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.auth-mask {
  position: fixed;
  inset: 0;
  z-index: 110;
  display: flex;
  align-items: flex-end;
  background: rgba(46, 42, 38, 0.5);
}

.auth-sheet {
  box-sizing: border-box;
  width: 100%;
  max-width: 480px;
  max-height: 90vh;
  margin: 0 auto;
  padding: 23px 22px max(28px, env(safe-area-inset-bottom));
  overflow-y: auto;
  border-radius: var(--r-lg) var(--r-lg) 0 0;
  background: var(--card);
}

.auth-page {
  position: relative;
  min-height: 100vh;
  align-items: center;
  padding: 20px;
  box-sizing: border-box;
  background: var(--bg);
}

.auth-page .auth-sheet {
  max-width: 440px;
  max-height: none;
  margin: auto;
  border-radius: var(--r-lg);
  box-shadow: var(--sh);
}

.auth-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.auth-kicker {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 2px;
  color: var(--sage);
}

.auth-close {
  padding: 4px 0 4px 10px;
  font-size: 13px;
  color: var(--ink-3);
  cursor: pointer;
}

.auth-title {
  display: block;
  margin-top: 12px;
  font-family: var(--serif);
  font-size: 25px;
  font-weight: 600;
  color: var(--ink);
}

.auth-desc {
  display: block;
  margin-top: 4px;
  font-size: 13px;
  color: var(--ink-2);
}

.auth-form {
  margin-top: 24px;
}

.auth-label {
  display: block;
  margin: 13px 0 7px;
  font-size: 13px;
  font-weight: 600;
}

.auth-input {
  box-sizing: border-box;
  width: 100%;
  height: 48px;
  padding: 0 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg);
  color: var(--ink);
  font-size: 15px;
}

.auth-input:focus-visible,
.auth-close:focus-visible,
.auth-switch:focus-visible {
  outline: 2px solid var(--warm);
  outline-offset: 2px;
}

.auth-error {
  display: block;
  margin-top: 12px;
  color: #ac3c2a;
  font-size: 13px;
}

.auth-consent {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
}

.auth-checkbox {
  box-sizing: border-box;
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border: 1.5px solid var(--line-strong, #d8cfc2);
  border-radius: 4px;
  background: var(--bg);
  text-align: center;
  line-height: 16px;
  cursor: pointer;
}

.auth-checkbox.checked {
  border-color: #a84f26;
  background: #a84f26;
}

.auth-checkmark {
  color: #fff;
  font-size: 12px;
}

.auth-consent-text {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-2);
}

.auth-link {
  color: #a84f26;
  cursor: pointer;
}

.auth-submit {
  margin-top: 20px;
}

.auth-switch {
  display: block;
  margin: 12px auto 0;
  padding: 8px 12px;
  border: 0;
  background: transparent;
  color: #a84f26;
  font-size: 13px;
  cursor: pointer;
}

.auth-switch::after {
  border: 0;
}
</style>
