<script setup lang="ts">
/**
 * 冷启动授权页：每次重新启动小程序的第一屏。
 *
 * 协议清单与登录页同源（`/configure/list` 里名称命中协议关键词的条目），
 * 后台增删一份，这里跟着变，前端不写死数量；接口不可用时用同一份兜底清单。
 *
 * 同意 → 已有登录态直接进首页；未登录则用微信 code 登录（与登录页同一接口）后再进首页。
 * 不同意 → 也能进首页浏览，但上传等入口会再引导回本页。
 */
import { onLoad, onShareAppMessage } from '@dcloudio/uni-app'
import { ref } from 'vue'
import StatusBar from '@/components/StatusBar.vue'
import type { AgreementItem } from '@/api/agreement'
import { fetchAgreements } from '@/api/agreement'
import { loginByWechat } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { markAgreed, markDeclined } from '@/utils/consentGate'
import { messageOf } from '@/utils/errors'
import { captureParentUserId } from '@/utils/referral'
import { useShareContent } from '@/utils/share'

const userStore = useUserStore()
const agreements = ref<AgreementItem[]>([])
const loading = ref(true)
const busy = ref(false)
const errorText = ref('')

onLoad(async (query) => {
  captureParentUserId(query)
  try {
    agreements.value = await fetchAgreements()
  }
  finally {
    loading.value = false
  }
})

// #ifdef MP-WEIXIN
onShareAppMessage(useShareContent('/pages/home/index', '谛听 · 那段通话，有人替你再听一遍'))
// #endif

function openAgreement(key: string) {
  uni.navigateTo({ url: `/pages/agreement/index?key=${encodeURIComponent(key)}` })
}

/** 从启动页进来是冷启动（没有上一页）；从功能入口被拦回来时是栈内页面 */
function leave() {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: '/pages/home/index' })
}

/**
 * 已登录（本地有 token）直接跳过；微信环境用 wx.login 的 code 走与登录页相同的接口。
 * 非微信端没有可用凭证，不阻断授权流程。
 */
async function loginIfNeeded() {
  if (userStore.token) {
    return
  }
  // #ifdef MP-WEIXIN
  const code = await new Promise<string>((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: res => (res.code ? resolve(res.code) : reject(new Error('未取得微信授权码'))),
      fail: reject,
    })
  })
  const seenVersions = agreements.value.reduce<Record<string, number>>((acc, item) => {
    acc[item.key] = item.version
    return acc
  }, {})
  const result = await loginByWechat(code, true, seenVersions)
  userStore.setLogin(result.token)
  userStore.userInfo = result.userInfo
  // #endif
}

async function agree() {
  if (busy.value) {
    return
  }
  busy.value = true
  errorText.value = ''
  try {
    await loginIfNeeded()
  }
  catch (error) {
    errorText.value = messageOf(error)
    busy.value = false
    return
  }
  busy.value = false
  markAgreed()
  leave()
}

function decline() {
  if (busy.value) {
    return
  }
  markDeclined()
  leave()
}
</script>

<template>
  <view class="gate">
    <StatusBar />

    <view class="hero">
      <text class="hero-kicker">开始前</text>
      <text class="hero-title">这些条款，请你先过一眼</text>
      <text class="hero-sub">
        每次启动都会再问你一次。同意之后才能上传录音、查看记录；不同意也可以先四处看看。
      </text>
    </view>

    <view class="arts">
      <view v-for="(item, index) in agreements" :key="item.key" class="art">
        <view class="art-l">
          <text class="art-no">{{ index + 1 }}</text>
          <view class="art-mid">
            <text class="art-title">{{ item.title }}</text>
            <text class="art-ver">版本 V{{ item.version }}</text>
          </view>
        </view>
        <text class="art-open" role="button" tabindex="0" @click="openAgreement(item.key)">查看</text>
      </view>
      <view v-if="loading" class="art-empty">
        <text class="muted">正在载入协议…</text>
      </view>
      <view v-else-if="agreements.length === 0" class="art-empty">
        <text class="muted">协议暂时取不到，请稍后重进；不会在你没看过条款时让你同意。</text>
      </view>
    </view>

    <view class="acts">
      <button
        class="btn btn-main btn-block"
        :disabled="loading || agreements.length === 0 || busy"
        @click="agree"
      >
        <text v-if="busy">正在登录…</text>
        <text v-else>同意以上 {{ agreements.length }} 份条款</text>
      </button>
      <text v-if="errorText" class="gate-error" role="alert">{{ errorText }}</text>
      <button class="btn btn-soft btn-block gate-decline" :disabled="busy" @click="decline">
        不同意，先看看
      </button>
      <text class="gate-note">
        不同意时：可以浏览首页与已有记录，上传、发起删除等操作会被要求先同意。
      </text>
    </view>
  </view>
</template>

<style scoped>
.gate {
  box-sizing: border-box;
  min-height: 100vh;
  padding-bottom: 28px;
  background: var(--bg);
}

.hero {
  padding: 20px 24px 16px;
}

.hero-kicker {
  display: block;
  font-size: 12px;
  letter-spacing: 2px;
  color: var(--sage);
}

.hero-title {
  display: block;
  margin-top: 8px;
  font-family: var(--serif);
  font-size: 22px;
  font-weight: 600;
  line-height: 1.6;
}

.hero-sub {
  display: block;
  margin-top: 8px;
  font-size: 12.5px;
  line-height: 1.85;
  color: var(--ink-2);
}

.arts {
  margin: 0 20px 16px;
  padding: 4px 16px;
  background: var(--card);
  border-radius: var(--r-lg);
  box-shadow: var(--sh);
}

.art {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 13px 0;
  border-bottom: 1px dashed var(--line);
}

.art:last-child {
  border-bottom: none;
}

.art-l {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 11px;
  min-width: 0;
}

.art-no {
  flex: none;
  width: 22px;
  height: 22px;
  font-size: 11.5px;
  line-height: 22px;
  text-align: center;
  color: #b05526;
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
  border-radius: 7px;
}

.art-mid {
  flex: 1;
  min-width: 0;
}

.art-title {
  display: block;
  font-size: 14px;
  line-height: 1.5;
  color: var(--ink);
}

.art-ver {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-3);
}

.art-open {
  flex: none;
  padding: 4px 2px;
  font-size: 12.5px;
  color: #b05526;
}

.art-empty {
  padding: 14px 0;
}

.acts {
  padding: 0 22px;
}

.gate-decline {
  margin-top: 10px;
}

.gate-error {
  display: block;
  margin-top: 10px;
  font-size: 12.5px;
  line-height: 1.7;
  text-align: center;
  color: #ac3c2a;
}

.gate-note {
  display: block;
  margin-top: 12px;
  font-size: 11.5px;
  line-height: 1.8;
  text-align: center;
  color: var(--ink-3);
}
</style>
