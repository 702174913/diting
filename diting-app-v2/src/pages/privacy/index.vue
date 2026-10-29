<script setup lang="ts">
/**
 * 隐私与数据：把「可以随时撤回」这句话落成一个真的按钮。
 * 抹除先撤销账号访问，清理作业要等旧签名失效后复扫，不能提前宣称彻底完成。
 */
import { onHide, onShareAppMessage, onShow, onUnload } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { eraseAll as eraseAllRequest, fetchDeletion } from '@/api/record'
import { ApiError, isTokenExpiredCode } from '@/api/request'
import type { DeletionStatus } from '@/types/api'
import type { DeletionReceipt } from '@/utils/deletionReceipt'
import { clearDeletionReceipt, getDeletionReceipt, saveDeletionReceipt } from '@/utils/deletionReceipt'
import { useUserStore } from '@/stores/user'
import { messageOf } from '@/utils/errors'
import { requireH5Login } from '@/utils/h5Auth'
import { useShareContent } from '@/utils/share'

const userStore = useUserStore()

// #ifdef MP-WEIXIN
onShareAppMessage(useShareContent('/pages/home/index', '谛听 · 那段通话，有人替你再听一遍'))
// #endif
const deleting = ref(false)
const cleanStatus = ref('')
const statusError = ref('')
const receiptWarning = ref('')
const cleanupReceipt = ref<DeletionReceipt | null>(null)
let visible = false
let polling: ReturnType<typeof setInterval> | null = null
let inFlight = false

function stopPolling() {
  if (polling !== null) {
    clearInterval(polling)
    polling = null
  }
}

function showCleanupStatus(job: DeletionStatus) {
  if (job.status === 'COMPLETED') {
    cleanStatus.value = '数据清理已完成'
    receiptWarning.value = ''
    cleanupReceipt.value = null
    clearDeletionReceipt()
    stopPolling()
  }
  else if (job.status === 'FAILED') {
    cleanStatus.value = `账号访问已撤销，但清理未完成（作业 #${job.deletionJobId}），请联系管理员`
    stopPolling()
  }
  else {
    cleanStatus.value = `账号访问已撤销，清理中（${job.pendingObjects} 项待复扫）；已签发的短期链接到期后才会完成清理`
  }
}

async function refreshCleanup() {
  const receipt = cleanupReceipt.value
  if (!receipt || !visible || inFlight) {
    return
  }
  inFlight = true
  try {
    const job = await fetchDeletion(receipt.jobId, receipt.token)
    if (visible && cleanupReceipt.value === receipt) {
      statusError.value = ''
      showCleanupStatus(job)
    }
  }
  catch (error) {
    if (visible && cleanupReceipt.value === receipt) {
      if (error instanceof ApiError && isTokenExpiredCode(error.code)) {
        cleanStatus.value = '清理状态查询凭据已过期，请联系管理员核对作业'
        cleanupReceipt.value = null
        clearDeletionReceipt()
        stopPolling()
      }
      else {
        statusError.value = '状态暂不可用，请稍后重试'
      }
    }
  }
  finally {
    inFlight = false
  }
}

function startPolling() {
  stopPolling()
  if (cleanupReceipt.value && visible) {
    polling = setInterval(() => void refreshCleanup(), 10_000)
  }
}

onShow(() => {
  visible = true
  if (!userStore.token) {
    cleanupReceipt.value = getDeletionReceipt() ?? cleanupReceipt.value
    if (cleanupReceipt.value) {
      void refreshCleanup()
      startPolling()
      return
    }
  }
  requireH5Login(userStore.token, 'about', '/pages/privacy/index')
})
onHide(() => {
  visible = false
  stopPolling()
})
onUnload(() => {
  visible = false
  stopPolling()
})

const items = [
  '上传前征求同意，随时可以撤回',
  '传输和存储全程加密',
  '不会用于训练，不会给任何第三方',
  '请求删除后撤销账号访问；已签发链接过期、对象复扫后才算清理完成',
  '原始录音保留 30 天，到期停止签发下载',
]

async function eraseAll() {
  const token = userStore.token
  if (!token || deleting.value) {
    return
  }
  const confirmed = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '彻底删除',
      content: '将删除你的全部录音、记录与账号数据，不可恢复。',
      confirmColor: '#C96434',
      success: res => resolve(res.confirm),
      fail: () => resolve(false),
    })
  })
  if (!confirmed) {
    return
  }
  deleting.value = true
  cleanStatus.value = ''
  try {
    const job = await eraseAllRequest(token)
    try {
      cleanupReceipt.value = saveDeletionReceipt(job.deletionJobId, token)
    }
    catch {
      cleanupReceipt.value = { jobId: job.deletionJobId, token, createdAt: Date.now() }
      receiptWarning.value = '本机无法保存清理凭据，请保持本页打开直到完成'
    }
    // 原身份立即清掉；旧 token 在服务端只能查询本人清理作业，不能访问任何业务数据。
    userStore.clear()
    showCleanupStatus(job)
    startPolling()
    void refreshCleanup()
    uni.showToast({ title: job.status === 'COMPLETED' ? '清理已完成' : '访问已撤销，清理中', icon: 'none' })
  }
  catch (error) {
    uni.showToast({ title: messageOf(error), icon: 'none' })
  }
  finally {
    deleting.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="care">
      <text class="care-p">你的录音，只属于你</text>
      <text class="care-fine">下面这些不是措辞，是系统里真实执行的行为。</text>
      <text v-if="cleanStatus" class="care-fine">{{ cleanStatus }}</text>
      <text v-if="statusError" class="care-fine" role="alert">{{ statusError }}</text>
      <text v-if="receiptWarning" class="care-fine" role="alert">{{ receiptWarning }}</text>
    </view>

    <view class="list">
      <text class="list-t">数据与隐私</text>
      <view
        v-for="(item, index) in items"
        :key="item"
        class="li"
        :class="{ 'li-last': index === items.length - 1 }"
      >
        <text class="li-k">✓</text>
        <text class="li-t">{{ item }}</text>
      </view>
    </view>

    <view class="actions">
      <button v-if="cleanupReceipt" class="btn btn-soft btn-block" @click="refreshCleanup">
        刷新清理状态
      </button>
      <button v-else-if="userStore.token" class="btn btn-soft btn-block" :disabled="deleting" @click="eraseAll">
        {{ deleting ? '删除中…' : '发起全部数据删除' }}
      </button>
    </view>

    <text class="note">
      <text class="note-line">账号访问先撤销，原件与记录异步清理；</text>
      <text class="note-line">已签发的短期链接到期后还会再次清理，已下载的副本无法回收。</text>
    </text>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 8px 0 40px;
  background: var(--bg);
}

.actions {
  padding: 4px 20px 0;
}
</style>
