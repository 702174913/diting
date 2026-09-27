<script setup lang="ts">
/**
 * Tab 1「今天」——面向焦虑中的新用户的首屏。
 * 目标：先把人留下来，再谈功能。情绪安抚在前，行动号召在后。
 */
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import StatusBar from '@/components/StatusBar.vue'
import HeroCard from '@/components/HeroCard.vue'
import RecordCard from '@/components/RecordCard.vue'
import { fetchTaskList } from '@/api/record'
import { useUserStore } from '@/stores/user'
import { greeting } from '@/utils/format'
import type { TaskItem } from '@/types/api'
import { requireH5Login } from '@/utils/h5Auth'
import markWarm from '@/static/icons/mark-warm.png'

const userStore = useUserStore()
const records = ref<TaskItem[]>([])
const total = ref<number | null>(null)
const loading = ref(false)
const loadError = ref(false)
let requestEpoch = 0
const hello = greeting()

/** 首屏只露最近 4 段，其余去「记录」看 */
const preview = computed(() => records.value.slice(0, 4))

async function load() {
  const token = userStore.token
  const epoch = ++requestEpoch
  records.value = []
  total.value = null
  loadError.value = false
  if (!token) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await fetchTaskList(token, 1, 20)
    if (epoch !== requestEpoch || userStore.token !== token) {
      return
    }
    records.value = result.items
    total.value = result.total
  }
  catch {
    if (epoch === requestEpoch && userStore.token === token) {
      loadError.value = true
    }
  }
  finally {
    if (epoch === requestEpoch) {
      loading.value = false
    }
  }
}

function goUpload() {
  uni.switchTab({ url: '/pages/upload/index' })
}

function goHistory() {
  uni.switchTab({ url: '/pages/history/index' })
}

/** 处理中/已完成都进记录页：那一页自己轮询、自己讲进度，不再分两个入口 */
function open(record: TaskItem) {
  uni.navigateTo({ url: `/pages/report/detail?id=${record.id}` })
}

onShow(() => {
  if (requireH5Login(userStore.token, 'home')) {
    void load()
  }
  else {
    ++requestEpoch
    records.value = []
    total.value = null
    loading.value = false
    loadError.value = false
  }
})
</script>

<template>
  <view class="page">
    <StatusBar />

    <view class="greet">
      <view class="greet-l">
        <text class="greet-hi">{{ hello }}</text>
        <text class="greet-who">谛听一直在</text>
      </view>
      <view class="avatar">
        <image class="avatar-img" :src="markWarm" mode="widthFix" />
      </view>
    </view>

    <HeroCard
      title="那段通话，有人替你再听一遍"
      description="你听得出情绪，听不出问题的地方，交给谛听。它不评判你，只把那几句话找出来。"
      button-text="上传录音，让它听一遍"
      caption="第一次免费，处理完可以一键删除"
      tip-text="你的录音只用于这一次判读，作任何别的用途之前都会先问过你。"
      @action="goUpload"
    />

    <view class="sec">
      <text class="sec-h2">听过的话</text>
      <text class="sec-all" @click="goHistory">{{ total === null ? (loading ? '加载中' : '—') : `${total} 段` }}</text>
    </view>

    <RecordCard
      v-for="record in preview"
      :key="record.id"
      :record="record"
      @open="open(record)"
    />

    <view v-if="loadError" class="empty">
      <text class="empty-text">记录读取失败，请检查网络后重试</text>
      <text class="sec-all" role="button" tabindex="0" @click="load">重新加载</text>
    </view>
    <view v-else-if="loading" class="empty">
      <text class="empty-text">正在读取最近的记录…</text>
    </view>
    <view v-else-if="total === 0" class="empty">
      <text class="empty-text">还没有听过的话，去上传一段录音吧</text>
    </view>

    <view class="care">
      <text class="care-p">我们不判谁欠谁<text class="care-br" /><text>只判哪句话说错了</text></text>
      <text class="care-fine">欠的钱要还，说的话也要算数。这一点，我们从来不含糊。</text>
    </view>

    <text class="note">
      <text class="note-line">本服务仅记录通话内容，不构成法律意见，</text>
      <text class="note-line">不作为处理结果承诺。</text>
    </text>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: 40px;
  background: var(--bg);
}

.avatar-img {
  width: 24px;
  height: 24px;
}

.care-br {
  display: block;
}

/*
  空态/错误态与「安心」卡之间的间距。
  全局 .empty 只有 padding-top: 100px、没有下内边距，而 .care 是 margin: 0 20px 14px、
  没有上外边距，两者原本是 0 间距（「重新加载」几乎贴住卡片顶边）。
  只在本页补回，不放进 App.vue：history 页的 .empty 后面接的是带 margin-top 的
  .note，report 页的 .empty 是页尾，改全局会连带改动它们既有的留白节奏。
*/
.empty {
  padding-bottom: 28px;
}
</style>
