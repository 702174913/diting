<script setup lang="ts">
/**
 * Tab 4「安心」——把边界写在明处。
 * 这一屏不卖功能，只回答「你会不会拿我的东西去做别的」。
 * 不该做的事逐条列出，比任何承诺话术都更可信。
 */
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import StatusBar from '@/components/StatusBar.vue'
import { bindWechat } from '@/api/auth'
import { messageOf } from '@/utils/errors'
import { useUserStore } from '@/stores/user'
import { requireH5Login } from '@/utils/h5Auth'
import { getDeletionReceipt } from '@/utils/deletionReceipt'

const userStore = useUserStore()
const bindingWechat = ref(false)

/** 一句话里需要加重语气的一段。小程序不支持 v-html，改用嵌套 text 分段渲染 */
interface Segment {
  text: string
  bold?: boolean
}

const notDoing: Segment[][] = [
  [{ text: '不教拖延、不教失联，' }, { text: '不教怎么不还钱', bold: true }],
  [{ text: '不承诺结果，不说包受理、不停催' }],
  [{ text: '不代理投诉，不代写材料' }],
  [{ text: '不评价你的债务本身' }],
  [{ text: '不攻击任何机构和个人' }],
]

const privacy: Segment[][] = [
  [{ text: '上传前' }, { text: '征求同意', bold: true }, { text: '，随时撤回' }],
  [{ text: '全链路加密', bold: true }],
  [{ text: '一键撤回，' }, { text: '清理状态可查', bold: true }],
  [{ text: '数据用途' }, { text: '以隐私协议为准', bold: true }],
]

const faqs = [
  {
    q: '录音会保存多久？',
    a: '原始录音从上传起保留 30 天，到期后停止签发下载；文字记录与判读结果的保留时长按平台策略执行。发起删除后撤销账号访问，已签发的短期链接可能到期前仍有效；清理完成以作业状态为准。已下载到你设备上的副本无法回收。',
  },
  {
    q: '判读结果是怎么来的？',
    a: '按平台已启用的规则逐句对照：命中就给证据与原文位置，需要回应的窗口内没回应就算缺失。没有说话人角色时，涉及角色的规则不会下结论。这不是法律意见，也不代表处理结果。',
  },
  {
    q: '这段录音能当成证据用吗？',
    a: '我们不对效力下判断。能做的是：把通话完整写出来，把有问题的句子逐条标注、附上依据，整理成你可以连同原始录音一起提交的东西。认不认，由受理方决定。',
  },
  {
    q: '方言和很吵的地方能听清吗？',
    a: '常见方言和一般嘈杂环境可以。听不准的地方我们会单独标出来，不会当作结论。',
  },
  {
    q: '要等多久？',
    a: '一般几分钟。比较长或者比较吵的录音会慢一点，列表里能看到进度。',
  },
  {
    q: '怎么收费？',
    a: '第一次免费。之后按录音数量或整理深度计价，页面上都会写明，不会自动续费。',
  },
  {
    q: '别人会知道我用过吗？',
    a: '上传前会征求同意。处理完可发起删除；账号访问先撤销，原件在短期链接过期后再次清理。已经下载的副本无法由平台回收。',
  },
  {
    q: '这份记录一定能派上用场吗？',
    a: '不能给承诺。建议连同原始录音一起提交，并且不要剪辑录音。',
  },
]

const openIndex = ref<number>(-1)

function toggle(index: number) {
  openIndex.value = openIndex.value === index ? -1 : index
}

function openPrivacy() {
  uni.navigateTo({ url: '/pages/privacy/index' })
}

/** 在已有账号上绑定当前微信，不创建第二个账号；已被他人绑定由服务端 409 拒绝。 */
async function bindCurrentWechat() {
  const token = userStore.token
  if (!token || bindingWechat.value || userStore.userInfo?.hasWechat) {
    return
  }
  bindingWechat.value = true
  try {
    const code = await new Promise<string>((resolve, reject) => {
      uni.login({
        provider: 'weixin',
        success: result => (result.code ? resolve(result.code) : reject(new Error('未取得微信授权码'))),
        fail: reject,
      })
    })
    const info = await bindWechat(code, token)
    if (token === userStore.token) {
      userStore.userInfo = info
      uni.showToast({ title: '微信已绑定', icon: 'success' })
    }
  }
  catch (error) {
    uni.showToast({ title: messageOf(error), icon: 'none' })
  }
  finally {
    bindingWechat.value = false
  }
}

onShow(() => {
  // 已注销的用户仍可从安心页进入清理状态，不应被 H5 登录门禁拦回登录页。
  if (!userStore.token && getDeletionReceipt()) {
    return
  }
  if (requireH5Login(userStore.token, 'about') && userStore.token && !userStore.userInfo) {
    void userStore.loadUserInfo().catch(() => {})
  }
})
</script>

<template>
  <view class="page">
    <StatusBar />

    <view class="greet">
      <view class="greet-l">
        <text class="greet-hi">写在明处</text>
        <text class="greet-who">我们的边界</text>
      </view>
    </view>

    <view class="care">
      <text class="care-p">欠的钱要还<text class="care-br" /><text>说的话也要算数</text></text>
      <text class="care-fine">这是谛听的态度，也是它一千年前就定下的规矩。</text>
    </view>

    <view class="list">
      <text class="list-t">我们不做的事</text>
      <view
        v-for="(segments, index) in notDoing"
        :key="index"
        class="li"
        :class="{ 'li-last': index === notDoing.length - 1 }"
      >
        <text class="li-k no">✕</text>
        <text class="li-t"><text v-for="(seg, i) in segments" :key="i" :class="{ 'li-b': seg.bold }">{{ seg.text }}</text></text>
      </view>
    </view>

    <view class="list">
      <text class="list-t">数据与隐私</text>
      <view v-for="(segments, index) in privacy" :key="index" class="li">
        <text class="li-k">✓</text>
        <text class="li-t"><text v-for="(seg, i) in segments" :key="i" :class="{ 'li-b': seg.bold }">{{ seg.text }}</text></text>
      </view>
      <view class="li li-last li-link" @click="openPrivacy">
        <text class="li-k">→</text>
        <text class="li-t">查看数据删除与清理状态</text>
      </view>
    </view>

    <!-- #ifdef MP-WEIXIN -->
    <view v-if="userStore.token" class="list">
      <text class="list-t">账号与微信</text>
      <view v-if="userStore.userInfo?.hasWechat" class="li li-last">
        <text class="li-k">✓</text>
        <text class="li-t">已绑定当前微信登录</text>
      </view>
      <view v-else class="li li-last li-link" role="button" tabindex="0" @click="bindCurrentWechat">
        <text class="li-k">→</text>
        <text class="li-t">{{ bindingWechat ? '绑定中…' : '把这个账号绑定到当前微信' }}</text>
      </view>
    </view>
    <!-- #endif -->

    <view class="list">
      <text class="list-t">你可能想问</text>
      <view
        v-for="(faq, index) in faqs"
        :key="faq.q"
        class="li li-col"
        :class="{ 'li-last': index === faqs.length - 1 }"
        @click="toggle(index)"
      >
        <view class="faq-q">
          <text class="li-t faq-q-text">{{ faq.q }}</text>
          <text class="faq-sign" :class="{ 'faq-sign-open': openIndex === index }">＋</text>
        </view>
        <text v-if="openIndex === index" class="faq-a">{{ faq.a }}</text>
      </view>
    </view>

    <view class="list">
      <text class="list-t">名字的来历</text>
      <view class="li li-last">
        <text class="li-k">·</text>
        <text class="li-t">
          谛听，读 <text class="li-b">dì tīng</text>，就是「侧耳细听」。《西游记》里它是地藏座下的神兽，<text class="li-b">伏地一听，辨万物真假</text>。两个孙悟空没人分得清的时候，是它听出来的。
        </text>
      </view>
    </view>

    <text class="note">
      <text class="note-line">谛听 · 催收录音质检</text>
      <text class="note-line">一听辨是非</text>
    </text>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: 40px;
  background: var(--bg);
}

.care-br {
  display: block;
}

.li-col {
  flex-direction: column;
  gap: 0;
}

.faq-q {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-height: 30px;
}

.faq-q-text {
  font-size: 13.5px;
  color: var(--ink);
}

.faq-sign {
  font-size: 15px;
  color: #c96434;
  transition: transform 0.25s;
}

.faq-sign-open {
  transform: rotate(45deg);
}

.faq-a {
  width: 100%;
  padding-top: 4px;
  font-size: 12.5px;
  line-height: 1.85;
  color: var(--ink-2);
}

.li-link .li-t {
  color: #b05526;
}
</style>
