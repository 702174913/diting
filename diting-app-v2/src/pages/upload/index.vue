<script setup lang="ts">
/**
 * Tab 2「上传」——转化最关键的一屏。
 * 定稿要求把「交出去会发生什么」讲在前面：三步说明 + 四条录音承诺，
 * 再给一个唯一的主行动按钮。
 *
 * 本页只负责「交出去」：提交成功给提示并返回 pages/history/index（记录列表），
 * 进度、结果与失败重传都由它讲，这里不再内联第二份进度。
 */
import { onHide } from '@dcloudio/uni-app'
import { computed, onUnmounted, ref } from 'vue'
import StatusBar from '@/components/StatusBar.vue'
import UploadSelection from '@/components/UploadSelection.vue'
import type { UploadSign } from '@/api/upload'
import {
  completeUpload,
  fetchQuota,
  refreshUpload,
  signUpload,
  submitTask,
  uploadRaw,
} from '@/api/upload'
import { ApiError } from '@/api/request'
import { readAudioDurationMs } from '@/utils/audioDuration'
import { messageOf } from '@/utils/errors'
import { newIdempotencyKey } from '@/utils/idempotency'
import { useUserStore } from '@/stores/user'
import type { ChosenFile, UploadMode } from '@/utils/choose'
import { MAX_IMAGES, chooseAudioFile, chooseImages, isImage, releaseChosenFile } from '@/utils/choose'
import shieldSage from '@/static/icons/shield-sage.png'
import { requireH5Login } from '@/utils/h5Auth'

const userStore = useUserStore()

const sheetVisible = ref(false)
/**
 * 这次要交的东西只有一种：录音一段，或图片最多 6 张（S16 的混合提交已按产品要求取消）。
 * tab 就是「交哪种」，切 tab 等于换交法，旧的整批作废。
 */
const mode = ref<UploadMode>('audio')
/** 本次要提交的文件：录音 ≤1（S16）；顺序即图片在记录里的顺序 */
const pendingFiles = ref<ChosenFile[]>([])
interface UploadAttempt {
  key: string
  ownerId: number
  submitted: boolean
  uploads: { sign: UploadSign, uploaded: boolean, verified: boolean }[]
  declaredDuration?: number
}
/** 同一批文件的逻辑提交凭证；网络错误不销毁，收到提交成功响应才清空。 */
const attempt = ref<UploadAttempt | null>(null)
const busy = ref(false)
/** 上传阶段文案：拿不到百分比时只显示状态，不造进度条 */
const uploadStage = ref('')
const uploadPercent = ref<number | null>(null)
/** 剩余份数：0 时不允许提交，直接给出去处 */
const remaining = ref<number | null>(null)
const maxMinutes = ref<number | null>(null)

/**
 * 预签名失效时刷新同一上传会话，只重试一次，避免重复提交。
 * 服务端 complete 后冻结 refresh（防 TOCTOU 覆盖）：这里只在「还没传过字节」
 * 的 PUT 阶段重试；刷新被拒（409）说明会话已校验，直接抛给上层提示重传。
 */
async function putWithRefresh(sign: UploadSign, filePath: string, token: string) {
  const onProgress = (percent: number | null) => {
    uploadPercent.value = percent
  }
  try {
    await uploadRaw(sign, filePath, onProgress)
  }
  catch (error) {
    if (!(error instanceof Error) || !/HTTP (?:401|403)\b/.test(error.message)) {
      throw error
    }
    // 刷新只对「尚未校验」的会话有效；已校验/已消费会 409，由上层转为重传提示
    const renewed = await refreshUpload(sign.uploadId, token)
    await uploadRaw(renewed, filePath, onProgress)
  }
}

const audioSelected = computed(() => pendingFiles.value.find(file => !isImage(file)) ?? null)
const imageSelected = computed(() => pendingFiles.value.filter(isImage))

/** 同意弹层上的一句话摘要：让用户清楚这次要交出去什么（只会有一种） */
const pendingSummary = computed(() => {
  if (mode.value === 'audio') {
    return audioSelected.value ? `录音 1 个（${audioSelected.value.name}）` : ''
  }
  const count = imageSelected.value.length
  return count > 0 ? `图片 ${count} 张` : ''
})

/**
 * 切 tab 就是换这次要交的东西：两种不能一起交，所以旧的整批释放掉，
 * 并明确告诉用户移除了什么——留着不说会让人以为交了两样。
 */
function switchMode(next: UploadMode) {
  if (next === mode.value || !canChangeFiles()) {
    return
  }
  const dropped = pendingFiles.value.length
  pendingFiles.value.forEach(releaseChosenFile)
  pendingFiles.value = []
  attempt.value = null
  mode.value = next
  if (dropped > 0) {
    uni.showToast({
      title: next === 'audio' ? '已切到录音，之前选的图片已移除' : '已切到图片，之前选的录音已移除',
      icon: 'none',
    })
  }
}

function canChangeFiles() {
  if (busy.value) {
    return false
  }
  if (attempt.value?.submitted) {
    uni.showToast({ title: '上次提交结果未确认，请先查看记录列表', icon: 'none' })
    return false
  }
  return true
}

/** 选录音：替换已选的那一段；图片与录音二选一，这里不会再带上图片 */
async function chooseFile() {
  if (!canChangeFiles() || !requireH5Login(userStore.token, 'upload')) {
    return
  }
  // #ifndef H5
  const signed = await userStore.ensureLogin()
  if (!signed) {
    if (!userStore.consentRequired) {
      uni.showToast({ title: '微信登录暂不可用，请稍后重试', icon: 'none' })
    }
    return
  }
  // #endif
  // H5 文件选择器必须在点击事件的用户激活期内立即打开，不能先 await 网络预检。
  let file: ChosenFile | null = null
  busy.value = true
  try {
    file = await chooseAudioFile()
    uploadStage.value = '正在检查录音时长…'
    if (!(await precheckQuota(true)) || !(await checkAudioLimit(file))) {
      releaseChosenFile(file)
      return
    }
    if (audioSelected.value) {
      releaseChosenFile(audioSelected.value)
    }
    pendingFiles.value = [file]
    attempt.value = null
  }
  catch (error) {
    if (file) {
      releaseChosenFile(file)
    }
    if (error instanceof Error && error.message !== '已取消选择' && error.message) {
      uni.showToast({ title: error.message, icon: 'none' })
    }
  }
  finally {
    busy.value = false
    uploadStage.value = ''
  }
}

/** 选图片：最多 6 张，可多选；与已选图片合并（重复路径只留一份）；不会再带上录音 */
async function chooseImageFiles() {
  if (!canChangeFiles() || !requireH5Login(userStore.token, 'upload')) {
    return
  }
  // #ifndef H5
  const signed = await userStore.ensureLogin()
  if (!signed) {
    if (!userStore.consentRequired) {
      uni.showToast({ title: '微信登录暂不可用，请稍后重试', icon: 'none' })
    }
    return
  }
  // #endif
  const slots = MAX_IMAGES - imageSelected.value.length
  if (slots <= 0) {
    uni.showToast({ title: `一次最多 ${MAX_IMAGES} 张图片`, icon: 'none' })
    return
  }
  // #ifndef H5
  if (!(await precheckQuota())) {
    return
  }
  // #endif
  try {
    const files = await chooseImages(slots)
    const merged = [...imageSelected.value]
    for (const file of files) {
      if (merged.some(item => item.path === file.path || (item.name === file.name && item.size === file.size))) {
        releaseChosenFile(file)
      }
      else {
        merged.push(file)
      }
    }
    pendingFiles.value = merged
    attempt.value = null
  }
  catch (error) {
    if (error instanceof Error && error.message !== '已取消选择' && error.message) {
      uni.showToast({ title: error.message, icon: 'none' })
    }
  }
}

/** 音频必须拿到服务端当前上限；图片沿用原额度预检的容错口径。 */
async function precheckQuota(requireAudioLimit = false) {
  try {
    const quota = await fetchQuota(userStore.token)
    remaining.value = quota.remaining
    maxMinutes.value = quota.maxRecordingMinutes
    if (quota.remaining <= 0) {
      uni.showToast({ title: '今天的免费份数用完了，明天再来', icon: 'none' })
      return false
    }
    if (requireAudioLimit && (!Number.isFinite(quota.maxRecordingMinutes) || quota.maxRecordingMinutes <= 0)) {
      throw new Error('无效的录音时长上限')
    }
  }
  catch {
    if (requireAudioLimit) {
      maxMinutes.value = null
      uni.showToast({ title: '暂时无法获取录音时长上限，请稍后重试', icon: 'none' })
      return false
    }
    // 图片预检失败不阻塞（服务端仍会校验）
  }
  return true
}

/** 选完文件以及正式签发前各检查一次；未知时长一律拒绝，不上传 OSS。 */
async function checkAudioLimit(file: ChosenFile) {
  try {
    const duration = file.durationMs ?? await readAudioDurationMs(file)
    if (!Number.isFinite(duration) || duration <= 0) {
      throw new Error('无法读取录音时长，请换一个受支持的录音文件后重试')
    }
    file.durationMs = duration
    if (maxMinutes.value === null) {
      return false
    }
    if (duration > maxMinutes.value * 60_000) {
      uni.showToast({ title: `录音超过 ${maxMinutes.value} 分钟上限，请换一段`, icon: 'none' })
      return false
    }
    return true
  }
  catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '无法读取录音时长，请换文件', icon: 'none' })
    return false
  }
}

function removeFile(path: string) {
  if (!canChangeFiles()) {
    return
  }
  attempt.value = null
  const file = pendingFiles.value.find(item => item.path === path)
  if (file) {
    releaseChosenFile(file)
  }
  pendingFiles.value = pendingFiles.value.filter(file => file.path !== path)
}

async function confirmUpload() {
  const files = pendingFiles.value
  // 连点守卫：没有它，两次「同意并上传」会登记两个任务、扣两份额度
  if (files.length === 0 || busy.value) {
    return
  }
  if (attempt.value?.submitted) {
    uni.showToast({ title: '提交结果未确认，请先到记录列表查看，避免重复提交', icon: 'none' })
    files.forEach(releaseChosenFile)
    pendingFiles.value = []
    attempt.value = null
    sheetVisible.value = false
    uni.switchTab({ url: '/pages/history/index' })
    return
  }
  if (!requireH5Login(userStore.token, 'upload')) {
    return
  }
  busy.value = true
  if (!(await userStore.ensureLogin())) {
    busy.value = false
    if (!userStore.consentRequired) {
      uni.showToast({ title: '登录失败，请稍后重试', icon: 'none' })
    }
    return
  }
  const token = userStore.token
  try {
    if (!userStore.userInfo) {
      await userStore.loadUserInfo()
    }
  }
  catch (error) {
    busy.value = false
    uni.showToast({ title: messageOf(error), icon: 'none' })
    return
  }
  const ownerId = userStore.userInfo?.id
  if (!ownerId || (attempt.value && attempt.value.ownerId !== ownerId)) {
    busy.value = false
    uni.showToast({ title: '请使用原账号确认本次上传结果', icon: 'none' })
    return
  }
  // 正式提交前再检查一次当前配置。
  if (!attempt.value?.submitted && !(await precheckQuota(mode.value === 'audio'))) {
    sheetVisible.value = false
    busy.value = false
    return
  }
  if (mode.value === 'audio' && !attempt.value?.submitted
    && audioSelected.value && !(await checkAudioLimit(audioSelected.value))) {
    sheetVisible.value = false
    busy.value = false
    return
  }
  if (!attempt.value) {
    attempt.value = { key: newIdempotencyKey('upload'), ownerId, submitted: false, uploads: [] }
  }
  const currentAttempt = attempt.value
  sheetVisible.value = false
  uploadPercent.value = null
  try {
    // 逐个上传并确认已取得返回文件名；任一失败不登记半个任务。
    if (mode.value === 'audio' && currentAttempt.declaredDuration === undefined && audioSelected.value?.durationMs) {
      currentAttempt.declaredDuration = Math.ceil(audioSelected.value.durationMs / 1000)
    }
    const uploadIds: number[] = []
    let index = 0
    for (const file of files) {
      index++
      uploadStage.value = files.length > 1
        ? `准备上传 ${index}/${files.length}…`
        : '准备上传…'
      let entry = currentAttempt.uploads[index - 1]
      if (!entry) {
        entry = { sign: await signUpload(file.name, file.size, token), uploaded: false, verified: false }
        currentAttempt.uploads[index - 1] = entry
      }
      if (!entry.uploaded) {
        uploadStage.value = files.length > 1 ? `上传中 ${index}/${files.length}…` : '上传中…'
        await putWithRefresh(entry.sign, file.path, token)
        entry.uploaded = true
      }
      if (!entry.verified) {
        uploadStage.value = files.length > 1 ? `校验中 ${index}/${files.length}…` : '校验中…'
        const done = await completeUpload(entry.sign.uploadId, token)
        entry.verified = true
        if (!isImage(file) && typeof done.durationMs === 'number' && done.durationMs > 0) {
          currentAttempt.declaredDuration = Math.ceil(done.durationMs / 1000)
        }
      }
      uploadIds.push(entry.sign.uploadId)
    }
    uploadStage.value = '登记中…'
    currentAttempt.submitted = true
    await submitTask(uploadIds, currentAttempt.declaredDuration, token, currentAttempt.key)
    uploadStage.value = ''
    sheetVisible.value = false
    files.forEach(releaseChosenFile)
    pendingFiles.value = []
    attempt.value = null
    // add 不返回记录 ID：成功后返回列表，不猜测最新一条属于本次提交。
    // 小延迟是为了让 toast 在真机上先显示出来；跳转失败就留在本页，记录可在「记录」里找到。
    uni.showToast({ title: '上传成功', icon: 'success', duration: 1500 })
    scheduleOpenHistory()
  }
  catch (e) {
    if (e instanceof ApiError && e.code !== 0 && e.code < 500 && attempt.value) {
      attempt.value.submitted = false
    }
    uploadStage.value = ''
    uni.showToast({ title: messageOf(e), icon: 'none' })
  }
  finally {
    busy.value = false
  }
}

/** 待跳转的定时器：这段等待里页面可能已被切走，不能把人弹到另一页去 */
let jumpTimer: ReturnType<typeof setTimeout> | null = null

function cancelJump() {
  if (jumpTimer !== null) {
    clearTimeout(jumpTimer)
    jumpTimer = null
  }
}

/** 提示 + 跳转是一个动作：先给 toast 留出可见时间，再回记录列表 */
function scheduleOpenHistory() {
  cancelJump()
  jumpTimer = setTimeout(() => {
    jumpTimer = null
    uni.switchTab({ url: '/pages/history/index' })
  }, 900)
}

// Tab 页切走不触发 onUnmounted：待跳转的定时器必须在这里撤掉
onHide(cancelJump)
onUnmounted(() => {
  cancelJump()
  pendingFiles.value.forEach(releaseChosenFile)
})
</script>

<template>
  <view class="page">
    <StatusBar />

    <view class="greet greet-tight">
      <view class="greet-l">
        <text class="greet-hi">第一步</text>
        <text class="greet-who">把录音交给我</text>
      </view>
    </view>

    <UploadSelection
      :busy="busy"
      :upload-stage="uploadStage"
      :upload-percent="uploadPercent"
      :mode="mode"
      :pending-files="pendingFiles"
      :pending-summary="pendingSummary"
      :sheet-visible="sheetVisible"
      @change-mode="switchMode"
      @choose-audio="chooseFile"
      @choose-images="chooseImageFiles"
      @remove-file="removeFile"
      @show-consent="sheetVisible = true"
      @confirm="confirmUpload"
      @cancel="sheetVisible = false"
    />

    <view class="tip">
      <image class="tip-ic" :src="shieldSage" mode="widthFix" />
      <text class="tip-p">可在记录里发起删除；访问先撤销，原件清理完成以作业状态为准。</text>
    </view>

    <text class="note">
      <text class="note-line">谛听不评价你的债务本身，</text>
      <text class="note-line">只判断通话里的话有没有说错。</text>
    </text>

  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: 40px;
  background: var(--bg);
}

.greet-tight {
  padding-bottom: 6px;
}
</style>
