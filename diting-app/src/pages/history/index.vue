<script setup lang="ts">
/**
 * Tab 3「记录」——全部听过的话。
 * 每一条都进同一个记录页：处理中它会自己轮询，处理完直接给正式内容。
 */
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import StatusBar from '@/components/StatusBar.vue'
import RecordCard from '@/components/RecordCard.vue'
import { deleteRecording, fetchTaskList } from '@/api/record'
import { useUserStore } from '@/stores/user'
import type { TaskItem } from '@/types/api'
import { hasMore, mergePage, nextPage } from '@/utils/paging'
import { messageOf } from '@/utils/errors'
import { requireH5Login } from '@/utils/h5Auth'

const userStore = useUserStore()
const records = ref<TaskItem[]>([])
const loading = ref(true)
const loadingMore = ref(false)
const page = ref(1)
const more = ref(false)

/** 重新加载第一页（进入页面、删除后刷新都走这里） */
async function load() {
  if (!requireH5Login(userStore.token, 'history')) {
    return
  }
  loading.value = true
  let token = userStore.token
  // #ifndef H5
  if (!token && await userStore.ensureLogin()) {
    token = userStore.token
  }
  // #endif
  if (!token) {
    records.value = []
    more.value = false
    loading.value = false
    return
  }
  try {
    page.value = 1
    const result = await fetchTaskList(token, 1, 20)
    records.value = mergePage<TaskItem>([], result)
    more.value = hasMore(records.value, result)
  }
  catch (error) {
    uni.showToast({ title: messageOf(error), icon: 'none' })
  }
  finally {
    loading.value = false
  }
}

/** 加载下一页：按 id 去重，避免重复项 */
async function loadMore() {
  const token = userStore.token
  if (!token || loadingMore.value || !more.value) {
    return
  }
  loadingMore.value = true
  try {
    const next = nextPage(page.value)
    const result = await fetchTaskList(token, next, 20)
    records.value = mergePage(records.value, result)
    page.value = next
    more.value = hasMore(records.value, result)
  }
  catch (error) {
    uni.showToast({ title: messageOf(error), icon: 'none' })
  }
  finally {
    loadingMore.value = false
  }
}

/** 单条删除：二次确认 → 立即从列表移除 → 提示清理状态 */
async function remove(record: TaskItem) {
  const token = userStore.token
  if (!token) {
    return
  }
  const confirmed = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '删除这段记录',
      content: '删除后录音与记录都不再可读，且无法恢复。',
      confirmColor: '#C96434',
      success: res => resolve(res.confirm),
      fail: () => resolve(false),
    })
  })
  if (!confirmed) {
    return
  }
  try {
    const job = await deleteRecording(record.id, token)
    records.value = records.value.filter(item => item.id !== record.id)
    uni.showToast({
      title: job.status === 'COMPLETED' ? '已删除' : '已删除，正在清理原件',
      icon: 'none',
    })
  }
  catch (error) {
    uni.showToast({ title: messageOf(error), icon: 'none' })
  }
}

function open(record: TaskItem) {
  uni.navigateTo({ url: `/pages/report/detail?id=${record.id}` })
}

onShow(load)
</script>

<template>
  <view class="page">
    <StatusBar />

    <view class="greet">
      <view class="greet-l">
        <text class="greet-hi">记录</text>
        <text class="greet-who">听过的话</text>
      </view>
      <text class="sec-all">{{ records.length }} 段</text>
    </view>

    <RecordCard
      v-for="record in records"
      :key="record.id"
      :record="record"
      @longpress="remove(record)"
      @open="open(record)"
    />

    <view v-if="!loading && records.length === 0" class="empty">
      <text class="empty-text">
        {{ userStore.token ? '还没有听过的话，去上传一段录音吧' : '登录后，才能看到属于你的记录' }}
      </text>
    </view>

    <text class="note">
      <text class="note-line">记录只对通话内容做转写与标注，</text>
      <text class="note-line">不构成法律意见，不作为处理结果承诺。</text>
    </text>
    <view v-if="more" class="load-more">
      <button class="btn btn-soft btn-block" :disabled="loadingMore" @click="loadMore">
        {{ loadingMore ? '加载中…' : '看更早的记录' }}
      </button>
    </view>
    <text v-else-if="records.length > 0" class="muted load-end">没有更多了</text>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: 40px;
  background: var(--bg);
}

.greet-l {
  flex: 1;
}
</style>
