<script setup lang="ts">
/**
 * 完整质检记录 —— 也是这条记录唯一的处理页（原独立的进度页已合并进来）。
 * 定稿的排序逻辑：结论 → 概览数字 → 问题 / 对话两个 Tab → 导出。
 * 问题页只给命中句与通话级缺失项（原文 / 高亮 / 规则名），对话页给完整原文；
 * 两边渲染同一份句子，切 Tab 不重新拉取，未发布的记录不出 Tab（不冒充结论）。
 * 每一处问题都必须带依据，否则这份记录拿不出手。
 *
 * 三种状态必须分清：
 * - 处理中：只给进度，不给任何结论（上传后直接跳进来就是这一态，历史 WAITING_ROLE 也走这里）；
 * - 还没有已发布版本：只给转写原文，不冒充「听完了、没问题」；
 * - 有已发布版本：按上面的定稿结构展示，导出走异步作业（创建 → 轮询 → 下载）。
 *
 * 轮询纪律：只由 polling 控制器持有定时器（重复进入不叠加），
 * 离开页面（onHide/onUnload）立刻停，终态自动停；隐藏后的登录异步续体不得启动定时器。
 */
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import SentenceItem from '@/components/SentenceItem.vue'
import MatchCard from '@/components/MatchCard.vue'
import ReportOverview from '@/components/ReportOverview.vue'
import ReportExportActions from '@/components/ReportExportActions.vue'
import ProcessingCard from '@/components/ProcessingCard.vue'
import { createExport, fetchAssets, fetchExportDownload, fetchExportPdfBytes, fetchOriginalBytes, fetchReport, fetchTaskStatus, validateDownloadedPdf, waitExport } from '@/api/record'
import { createZip } from '@/utils/zip'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { exportArtifactName } from '@/utils/exportArtifact'
import { displayViolationNote } from '@/utils/reportNotes'
import { messageOf } from '@/utils/errors'
import { ApiError, isTokenExpiredCode } from '@/api/request'
import { reportPresentation } from '@/utils/reportPresentation'
import { requireH5Login } from '@/utils/h5Auth'
import { createPolling } from '@/utils/polling'
import { isFailed, needsPolling } from '@/utils/taskStatus'
import type { AssetItem, ReportDetail, TaskStatus } from '@/types/api'
import checkSage from '@/static/icons/check-sage.png'

const userStore = useUserStore()
const report = ref<ReportDetail | null>(null)
const loading = ref(true)
const loadError = ref('')
const currentReportId = ref(0)
let loadGeneration = 0
const exporting = ref(false)
/** 问题 / 对话两个 Tab：默认先回答「这通电话有没有要留意的」 */
const activeTab = ref<'issues' | 'dialogue'>('issues')
/** 网络超时保留作业号，下次操作先查询原作业；不缓存签名地址。 */
const pendingExports = new Map<string, number>()
/** 记录下的图片（S16）：与报告分开拉取，避免报告接口承担对象存储的签名工作 */
const assets = ref<AssetItem[]>([])
/** 任务状态：处理中由本页轮询，进度与记录不再分两个页面 */
const taskStatus = ref<TaskStatus | null>(null)
/** 处理中的录音时长（来自状态接口：报告还没生成时也得有话可说） */
const taskDurationSec = ref(0)
const statusHint = ref('')
/** 登录态就绪后才允许启动轮询，避免 onShow 比 onLoad 先跑导致空转 */
const ready = ref(false)
let visible = false
const images = computed(() => assets.value.filter(item => item.kind === 'IMAGE'))
const audioAsset = computed(() => assets.value.find(item => item.kind === 'AUDIO') ?? null)
const audioUrl = computed(() => audioAsset.value?.url ?? '')
const audioPlaying = ref(false)
const audioError = ref('')
const audioCurrent = ref(0)
const audioDuration = ref(0)
/** 真实时长未就绪时先用报告里的时长，避免初次渲染滑块区间为 0 */
const audioTotal = computed(() => audioDuration.value > 0
  ? audioDuration.value
  : (report.value?.durationSec ?? 0))
let audioSeeking = false
let audioContext: ReturnType<typeof uni.createInnerAudioContext> | null = null
let audioContextUrl = ''

function audioClock(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds || 0))
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}

function ensureAudioContext(): ReturnType<typeof uni.createInnerAudioContext> {
  if (audioContext) {
    return audioContext
  }
  const context = uni.createInnerAudioContext()
  context.onPlay(() => {
    audioPlaying.value = true
    audioError.value = ''
  })
  context.onPause(() => {
    audioPlaying.value = false
  })
  context.onStop(() => {
    audioPlaying.value = false
  })
  context.onEnded(() => {
    audioPlaying.value = false
    audioCurrent.value = 0
  })
  context.onCanplay(() => {
    if (Number.isFinite(context.duration) && context.duration > 0) {
      audioDuration.value = context.duration
    }
  })
  context.onTimeUpdate(() => {
    if (audioSeeking) {
      return
    }
    audioCurrent.value = context.currentTime
    if (Number.isFinite(context.duration) && context.duration > 0) {
      audioDuration.value = context.duration
    }
  })
  context.onError(() => {
    audioPlaying.value = false
    audioError.value = '录音加载失败，请稍后重试'
  })
  audioContext = context
  return context
}

function onAudioChanging(event: { detail: { value: number } }) {
  audioSeeking = true
  audioCurrent.value = event.detail.value
}

function onAudioChange(event: { detail: { value: number } }) {
  audioSeeking = false
  const url = audioUrl.value
  if (!url) {
    return
  }
  const context = ensureAudioContext()
  if (audioContextUrl !== url) {
    context.src = url
    audioContextUrl = url
  }
  context.seek(event.detail.value)
  audioCurrent.value = event.detail.value
}

function toggleAudio() {
  const url = audioUrl.value
  if (!url) {
    return
  }
  const context = ensureAudioContext()
  if (audioContextUrl !== url) {
    context.stop()
    context.src = url
    audioContextUrl = url
    audioCurrent.value = 0
    audioDuration.value = 0
  }
  if (audioPlaying.value) {
    context.pause()
  }
  else {
    context.play()
  }
}
function sourceLabelOf(sourceAssetId: number | null): string | undefined {
  if (sourceAssetId === null || sourceAssetId === undefined) {
    return undefined
  }
  const asset = images.value.find(item => item.assetId === sourceAssetId)
  return asset ? `图片 ${asset.sequence} · ${asset.filename}` : '图片原件（详情暂不可用）'
}

const sentenceCount = computed(() => report.value?.sentenceCount ?? report.value?.sentences.length ?? 0)
/** 有说明的句子即「要留意的话」 */
/** 问题 tab 直接展示接口的匹配项（match_result / picture_match_results） */
const matches = computed(() => report.value?.matches ?? [])
/** 正式报告必须成功发布；失败版本即使有 ID 也不能展示成功结论或导出。 */
const presentation = computed(() => reportPresentation(report.value))
const waitingReport = computed(() => !presentation.value.published)
/**
 * 处理中：有已发布报告就不再冒充「还在听」，直接给正式内容
 * （pipeline 可能在状态落终态前就已发布，用户不该为状态机多等一会儿）。
 */
const running = computed(() => taskStatus.value !== null
  && needsPolling(taskStatus.value)
  && !presentation.value.published)
const cardStatus = computed<TaskStatus>(() => taskStatus.value ?? 'QUEUED')
const cardDurationSec = computed(() => taskDurationSec.value || report.value?.durationSec || 0)
/** 状态接口失败时退回报告里的任务状态：失败入口不因一次请求失败消失 */
const taskFailed = computed(() => {
  const live = taskStatus.value
  if (live !== null) {
    return isFailed(live)
  }
  return report.value?.taskStatus === 'FAILED' || report.value?.taskStatus === 'INVALID'
})
const headline = computed(() => presentation.value.headline)
const lead = computed(() => presentation.value.lead)
const versionLabel = computed(() => presentation.value.versionLabel)
const callLevelViolations = computed(() => (report.value?.violations ?? []).filter(item => item.sentenceId === null))
/** 对话页：整份原文，命中句同样带高亮，不用在两个 Tab 之间拼上下文 */
const fullSentences = computed(() => report.value?.sentences ?? [])
/** 问题页卡片数：通话级缺失项 + 命中句，与概览的「要留意」同一份句子口径 */
const issueCount = computed(() => callLevelViolations.value.length + matches.value.length)
/** 原件可下载时才允许连同打包：录音或任意图片有可用地址即可。 */
const originalsAvailable = computed(() => assets.value.some(item => Boolean(item.url)))

async function loadAssets(id: number) {
  const token = userStore.token
  if (!token) {
    return
  }
  try {
    assets.value = await fetchAssets(id, token)
  }
  catch {
    // 资产拉取失败不影响报告阅读：图片区显示为空
    assets.value = []
  }
}

/** 点开图片：多张时可在预览里左右滑动；已清理或过期的给明确提示，不给假链接 */
function previewImage(item: AssetItem) {
  const urls = images.value
    .map(image => image.url)
    .filter((url): url is string => Boolean(url))
  if (!item.url) {
    uni.showToast({ title: '这张图片已过保留期或已清理', icon: 'none' })
    return
  }
  uni.previewImage({ urls, current: item.url })
}

async function loadReport(id: number, keepVisible = false) {
  currentReportId.value = id
  const generation = ++loadGeneration
  const token = userStore.token
  // 处理中自动补拉时保留屏幕上的内容：不闪回加载态，旧内容由新报告覆盖
  if (!keepVisible) {
    loading.value = true
    loadError.value = ''
    report.value = null
  }
  if (!token) {
    loadError.value = '请先登录后查看记录'
    loading.value = false
    return
  }
  try {
    const result = await fetchReport(id, token)
    if (generation === loadGeneration && token === userStore.token) {
      report.value = result
      loadError.value = ''
    }
  }
  catch (error) {
    if (generation === loadGeneration && !keepVisible) {
      loadError.value = error instanceof ApiError && error.code === 404 ? '' : messageOf(error)
    }
  }
  finally {
    if (generation === loadGeneration) {
      loading.value = false
    }
  }
}

function retryReport() {
  if (currentReportId.value > 0 && requireH5Login(userStore.token, 'history', `/pages/report/detail?id=${currentReportId.value}`)) {
    loadReport(currentReportId.value)
  }
}

/** 一页两态：处理中叫「正在聆听」，出了结果叫「质检记录」 */
function syncTitle() {
  try {
    uni.setNavigationBarTitle({ title: running.value ? '正在聆听' : '质检记录' })
  }
  catch {
    // 标题不是关键路径：个别容器没有这个能力也不影响阅读
  }
}

/**
 * 处理中：查任务状态（不拉整份报告，也不签对象存储地址）。
 * 终态再补一次正式报告：处理中拿到的那份可能只有转写。
 */
const polling = createPolling({
  // 5s 一次：进度不需要更密，而并发用户多时每个在飞任务都在消耗服务端一圈查询
  intervalMs: 5000,
  run: async () => {
    const token = userStore.token
    const id = currentReportId.value
    if (!id || !token || !visible) {
      return false
    }
    const res = await fetchTaskStatus(id, token)
    if (!visible || userStore.token !== token || currentReportId.value !== id) {
      return false
    }
    const previous = taskStatus.value
    taskStatus.value = res.status
    if (typeof res.durationSec === 'number' && res.durationSec > 0) {
      taskDurationSec.value = res.durationSec
    }
    statusHint.value = ''
    syncTitle()
    if (previous !== null && previous !== res.status && !needsPolling(res.status)) {
      if (res.status === 'DELETED') {
        // 记录已被删除（别处删的）：页面留不住内容，回到「不存在」态
        report.value = null
        assets.value = []
      }
      else {
        void loadReport(id, true)
      }
    }
    // 终态和删除中停止轮询；历史 WAITING_ROLE 仍由后台自动生成报告
    return needsPolling(res.status)
  },
  onError: (error) => {
    // 登录失效由请求层统一处理，不在这里重复提示
    if (error instanceof ApiError && isTokenExpiredCode(error.code)) {
      return
    }
    // 网络抖动不打扰用户，也不无限重试：只在失败时给一句提示
    statusHint.value = '网络不稳定，正在重试…'
  },
})

/** 「先去别处」：停掉轮询再返回，不留后台定时器 */
function leaveNow() {
  polling.stop()
  uni.navigateBack({
    fail: () => uni.switchTab({ url: '/pages/history/index' }),
  })
}

/** 没听成的记录：回上传页重来一次 */
function retryUpload() {
  polling.stop()
  uni.switchTab({ url: '/pages/upload/index' })
}

/**
 * 导出：POST 创建作业 → 轮询到完成 → 按文件类型分流。
 * PDF 用系统组件打开；ZIP（含原始录音）走保存/转发——openDocument 打不开压缩包。
 */
async function save(withAudio: boolean) {
  const current = report.value
  const token = userStore.token
  const versionId = current?.reportVersionId
  if (!current || !token || exporting.value) {
    return
  }
  if (!versionId || !presentation.value.published) {
    uni.showToast({ title: '报告成功发布后才能保存', icon: 'none' })
    return
  }
  if (withAudio) {
    await saveBundle(current, token, versionId)
    return
  }
  exporting.value = true
  // 进度只用 loading 表达，页面不再多占一行文字。
  // 生成阶段不 mask：可能几十秒，页面要能滚动、能点「先去别处」；下载阶段才 mask 防误点。
  uni.showLoading({ title: '正在生成…' })
  try {
    const key = `${current.id}/${versionId}/${withAudio}`
    let exportId = pendingExports.get(key)
    if (exportId === undefined) {
      const job = await createExport(current.id, versionId, token, withAudio)
      exportId = job.exportId
      pendingExports.set(key, exportId)
    }
    const done = await waitExport(exportId, token)
    if (!done || done.status !== 'COMPLETED') {
      if (done?.status === 'FAILED') {
        pendingExports.delete(key)
      }
      uni.hideLoading()
      uni.showToast({
        title: done?.status === 'FAILED' ? '导出失败，请稍后再试' : '导出还在准备，请稍后再来',
        icon: 'none',
      })
      return
    }
    // 状态查询不再返回下载地址：完成后显式调一次 /download 签发（只记一条签发审计）
    let downloadUrl = ''
    try {
      const download = await fetchExportDownload(exportId, token, current.id)
      downloadUrl = download.downloadUrl
    }
    catch {
      downloadUrl = ''
    }
    if (!downloadUrl) {
      uni.hideLoading()
      uni.showToast({
        title: '导出已完成，获取下载地址失败，请重试',
        icon: 'none',
      })
      return
    }
    // #ifdef H5
    // 浏览器端自己拉字节流另存（见 saveInBrowser 注释：不走 uni.downloadFile）
    const fileName = exportArtifactName(current.reportNo, versionId, done.fileType)
    uni.showLoading({ title: '正在下载…', mask: true })
    try {
      await saveInBrowser(downloadUrl, fileName)
      uni.hideLoading()
      uni.showToast({ title: '已保存，可在浏览器下载列表里查看', icon: 'none' })
    }
    catch {
      // 兜底：fetch 被 CORS/网络挡下时，仍把地址交给浏览器（服务端已带 attachment 文件名）
      uni.hideLoading()
      saveViaUrl(downloadUrl, fileName)
      uni.showToast({ title: '已改为直接下载，请留意浏览器提示', icon: 'none' })
    }
    if (downloadUrl.startsWith('blob:')) {
      setTimeout(() => URL.revokeObjectURL(downloadUrl), 4000)
    }
    // #endif
    // #ifndef H5
    // 小程序端只有下载任务级进度，没有回调百分比：遮罩 loading 到回调里关
    uni.showLoading({ title: '正在下载…', mask: true })
    openPdf(downloadUrl)
    // #endif
  }
  catch (e) {
    uni.hideLoading()
    uni.showToast({ title: messageOf(e), icon: 'none' })
  }
  finally {
    exporting.value = false
  }
}

/** 文件名去重：同名原件在压缩包内不能互相覆盖 */
function uniqueZipName(name: string, used: Set<string>): string {
  const candidate = name || 'files'
  if (!used.has(candidate)) {
    used.add(candidate)
    return candidate
  }
  const dot = candidate.lastIndexOf('.')
  const stem = dot > 0 ? candidate.slice(0, dot) : candidate
  const ext = dot > 0 ? candidate.slice(dot) : ''
  let index = 1
  while (used.has(`${stem}-${index}${ext}`)) {
    index++
  }
  const result = `${stem}-${index}${ext}`
  used.add(result)
  return result
}

/**
 * 连同原件打包：先取记录 PDF，再把录音与原图按原件名打进同一个 ZIP。
 * 原件本身已是压缩格式，ZIP 只做 store；同名原件自动加序号。
 */
async function saveBundle(current: ReportDetail, token: string, versionId: number) {
  exporting.value = true
  uni.showLoading({ title: '正在打包…' })
  try {
    const originals = assets.value.filter(item => Boolean(item.url))
    if (originals.length === 0) {
      throw new ApiError(422, '没有可打包的原件')
    }
    const used = new Set<string>()
    const pdfName = exportArtifactName(current.reportNo, versionId, 'pdf')
    used.add(pdfName)
    const entries = [{ name: pdfName, data: await fetchExportPdfBytes(current.id, token) }]
    for (const item of originals) {
      entries.push({
        name: uniqueZipName(item.filename, used),
        data: await fetchOriginalBytes(item.url as string, token),
      })
    }
    const zip = createZip(entries)
    const fileName = exportArtifactName(current.reportNo, versionId, 'zip')
    uni.showLoading({ title: '正在保存…', mask: true })
    // #ifdef H5
    saveBlobInBrowser(zip, fileName)
    uni.hideLoading()
    uni.showToast({ title: '已保存，可在浏览器下载列表里查看', icon: 'none' })
    // #endif
    // #ifndef H5
    await saveZipBytes(zip, fileName)
    // #endif
  }
  catch (error) {
    uni.hideLoading()
    uni.showToast({ title: messageOf(error), icon: 'none' })
  }
  finally {
    exporting.value = false
  }
}

// #ifdef H5
/**
 * H5 下载（主路径）：自己拉字节流 → Blob 另存。
 *
 * 为什么不把地址直接交给浏览器、也不走 `uni.downloadFile`：
 * - `uni.downloadFile` 在 H5 里会 `xhr.getResponseHeader('content-disposition')` 取文件名，
 *   而 OSS 没通过 CORS 暴露这个响应头 —— 浏览器直接报
 *   `Refused to get unsafe header "content-disposition"`，文件名也一起丢掉（退化成 OSS 对象名）；
 * - 直接跳转会拿不到“下载中”这个状态：浏览器自己的下载栏是唯一反馈。
 * 自己拉流后既能看到进度，文件名也由前端确定（Blob 同源，`download` 属性生效）。
 */
async function saveInBrowser(url: string, fileName: string) {
  const res = await fetch(url, { credentials: 'omit' })
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`)
  }
  const objectUrl = URL.createObjectURL(await res.blob())
  saveViaUrl(objectUrl, fileName)
  // 立刻 revoke 会让部分浏览器拿到空文件：留一拍再释放
  setTimeout(() => URL.revokeObjectURL(objectUrl), 4000)
}

/**
 * H5 兜底：把地址交给浏览器自己另存。
 * 服务端在预签名 URL 里带了 `response-content-disposition=attachment; filename=...`，
 * 所以即使跨域下 `download` 属性被忽略，文件名依然是报告编号-v版本.扩展名。
 */
function saveViaUrl(url: string, fileName: string) {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.rel = 'noopener'
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
}

/** 字节流另存为 ZIP：Blob 同源，download 属性生效 */
function saveBlobInBrowser(data: Uint8Array, fileName: string) {
  const url = URL.createObjectURL(new Blob([data], { type: 'application/zip' }))
  saveViaUrl(url, fileName)
  setTimeout(() => URL.revokeObjectURL(url), 4000)
}
// #endif

// #ifndef H5
function openPdf(url: string) {
  const token = userStore.token
  uni.downloadFile({
    url,
    success: async (r) => {
      try {
        await validateDownloadedPdf(r.tempFilePath, r.statusCode, token)
        uni.openDocument({
          filePath: r.tempFilePath,
          fileType: 'pdf',
          showMenu: true,
          fail: () => uni.showToast({ title: '打不开这份记录，请稍后再试', icon: 'none' }),
        })
      }
      catch (error) {
        uni.showToast({ title: messageOf(error), icon: 'none' })
      }
      finally {
        uni.hideLoading()
      }
    },
    fail: () => {
      uni.hideLoading()
      uni.showToast({ title: '下载失败，请重试', icon: 'none' })
    },
  })
}

/** ZIP 不能 openDocument：微信端转发到聊天保存，其他端写入小程序文件目录 */
function saveZipBytes(data: Uint8Array, fileName: string): Promise<void> {
  const wxAny = (globalThis as unknown as {
    wx?: {
      env?: { USER_DATA_PATH?: string }
      getFileSystemManager?: () => {
        writeFile: (options: {
          filePath: string
          data: ArrayBuffer
          success: () => void
          fail: () => void
        }) => void
      }
    }
  }).wx
  const fs = wxAny?.getFileSystemManager?.()
  const dir = wxAny?.env?.USER_DATA_PATH
  if (!fs?.writeFile || !dir) {
    uni.hideLoading()
    uni.showToast({ title: '当前平台暂不支持保存压缩包', icon: 'none' })
    return Promise.resolve()
  }
  const filePath = `${dir}/${fileName}`
  const buffer = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)
  return new Promise((resolve, reject) => {
    fs.writeFile({
      filePath,
      data: buffer,
      success: () => {
        uni.hideLoading()
        const share = (uni as unknown as {
          shareFileMessage?: (options: { filePath: string, fileName?: string, fail?: () => void }) => void
        }).shareFileMessage
        if (typeof share === 'function') {
          share({
            filePath,
            fileName,
            fail: () => uni.showToast({ title: '保存失败，请重试', icon: 'none' }),
          })
        }
        else {
          uni.showToast({ title: '已保存到小程序文件，可在聊天里转发', icon: 'none' })
        }
        resolve()
      },
      fail: () => reject(new ApiError(500, '保存压缩包失败，请重试')),
    })
  })
}
// #endif

onLoad(async (query) => {
  const id = Number(query?.id ?? 0)
  if (!requireH5Login(userStore.token, 'history', id > 0 ? `/pages/report/detail?id=${id}` : undefined)) {
    return
  }
  if (!id) {
    loadError.value = '记录编号无效，请从记录列表重新进入'
    loading.value = false
    return
  }
  currentReportId.value = id
  if (!(await userStore.ensureLogin())) {
    loadError.value = '请在小程序里打开后再看'
    loading.value = false
    return
  }
  ready.value = true
  void loadReport(id)
  void loadAssets(id)
  syncTitle()
  // 页面已隐藏（用户快速返回）时不起定时器：由 onShow 负责启动，避免隐藏后空转
  if (visible) {
    polling.start()
  }
})

onShow(() => {
  visible = true
  if (ready.value) {
    polling.start()
  }
})

onHide(() => {
  visible = false
  polling.stop()
  audioContext?.pause()
})

onUnload(() => {
  visible = false
  polling.stop()
  audioContext?.destroy()
  audioContext = null
  audioContextUrl = ''
})
</script>

<template>
  <view class="page">
    <!-- 处理中：这一屏只讲「还在听」，不给任何结论 -->
    <template v-if="running">
      <view class="greet">
        <view class="greet-l">
          <text class="greet-hi">正在听</text>
          <text class="greet-who">交给我，你先歇会儿</text>
        </view>
      </view>

      <ProcessingCard :status="cardStatus" :duration-sec="cardDurationSec" />

      <text v-if="statusHint" class="hint">{{ statusHint }}</text>

      <view class="actions">
        <button class="btn btn-soft btn-block" @click="leaveNow">
          让它慢慢听，我先去别处
        </button>
      </view>

      <text class="note">
        <text class="note-line">听完整段通话通常几分钟，</text>
        <text class="note-line">越吵或越长的录音会慢一点。</text>
      </text>
    </template>

    <template v-else-if="report">
      <view class="greet">
        <view class="greet-l">
          <text class="greet-hi">{{ formatDateTime(report.createdAt) }}</text>
          <text class="greet-who">{{ waitingReport ? headline : '记录已生成' }}</text>
        </view>
      </view>

      <ReportOverview
        v-if="!waitingReport"
        :report="report"
        :headline="headline"
        :version-label="versionLabel"
      />

      <text class="lead">{{ lead }}</text>
      <view
        v-for="(note, index) in report.notes"
        :key="`report-note-${index}`"
        class="report-note"
      >
        <text>{{ note }}</text>
      </view>

      <view v-if="audioAsset" class="audio-card">
        <view class="audio-head">
          <view class="audio-meta">
            <text class="audio-title">原录音</text>
            <text class="audio-name">{{ audioAsset.filename }}</text>
          </view>
          <button
            class="btn btn-soft audio-btn"
            :disabled="!audioUrl"
            @click="toggleAudio"
          >
            {{ audioPlaying ? '暂停' : '试听' }}
          </button>
        </view>
        <view v-if="audioUrl" class="audio-progress">
          <text class="audio-time">{{ audioClock(audioCurrent) }}</text>
          <view v-if="audioTotal > 0" class="audio-slider">
            <slider
              :min="0"
              :max="audioTotal"
              :value="audioCurrent"
              :step="1"
              block-size="14"
              active-color="#b05526"
              @changing="onAudioChanging"
              @change="onAudioChange"
            />
          </view>
          <view v-else class="audio-slider audio-slider-empty">
            <text class="audio-loading">加载中…</text>
          </view>
          <text class="audio-time">{{ audioClock(audioTotal) }}</text>
        </view>
      </view>
      <text v-if="audioAsset && !audioUrl" class="hint audio-hint">原录音地址暂不可用，无法试听。</text>
      <text v-if="audioError" class="hint audio-hint">{{ audioError }}</text>

      <!-- 尚未发布时只展示转写；说话人编号直接来自听悟 SpeakerId。 -->
      <template v-if="waitingReport">
        <view class="roles">
          <text class="list-t">完整转写（{{ sentenceCount }} 句）</text>
        </view>
        <SentenceItem
          v-for="sentence in report.sentences"
          :key="`t-${sentence.id}`"
          :sentence="sentence"
          :source-label="sourceLabelOf(sentence.sourceAssetId)"
        />
      </template>

      <view v-if="images.length" class="roles">
        <text class="list-t">图片（{{ images.length }} 张）</text>
        <view class="imgs">
          <view
            v-for="item in images"
            :key="item.assetId"
            class="img-cell"
            @click="previewImage(item)"
          >
            <image v-if="item.url" class="img-thumb" :src="item.url" mode="aspectFill" />
            <view v-else class="img-thumb img-none">
              <text class="img-none-t">已清理</text>
            </view>
            <text class="img-cap">{{ item.filename }}</text>
          </view>
        </view>
      </view>

      <!-- 已发布：问题（命中与缺失）/ 对话（完整原文）两个 Tab -->
      <template v-if="!waitingReport">
        <view class="tabs">
          <view
            class="tab"
            :class="{ 'tab-on': activeTab === 'issues' }"
            @click="activeTab = 'issues'"
          >
            <text class="tab-t">
              问题
            </text>
            <text class="tab-n">
              {{ issueCount }}
            </text>
          </view>
          <view
            class="tab"
            :class="{ 'tab-on': activeTab === 'dialogue' }"
            @click="activeTab = 'dialogue'"
          >
            <text class="tab-t">
              对话
            </text>
            <text class="tab-n">
              {{ sentenceCount }}
            </text>
          </view>
        </view>

        <template v-if="activeTab === 'issues'">
          <view v-for="item in callLevelViolations" :key="`violation-${item.id}`" class="find">
            <text class="f-ts">通话级问题 · {{ item.ruleName || '规则缺失项' }}</text>
            <text class="f-note">{{ displayViolationNote(item.note) }}</text>
            <text v-if="item.deduct !== null" class="hint">规则扣分：{{ item.deduct }}</text>
          </view>
          <MatchCard
            v-for="item in matches"
            :key="`match-${item.id}`"
            :match="item"
          />
          <view v-if="issueCount === 0" class="find pass">
            <text class="f-ts">
              本轮规则未检出问题
            </text>
            <text class="f-note f-note-flush">
              没有命中本次启用的规则，不代表内容一定没有问题；可切到「对话」逐句核对原文。
            </text>
          </view>
        </template>

        <template v-else>
          <view class="roles">
            <text class="list-t">
              完整对话（{{ sentenceCount }} 句）
            </text>
          </view>
          <SentenceItem
            v-for="sentence in fullSentences"
            :key="`all-${sentence.id}`"
            :sentence="sentence"
            :source-label="sourceLabelOf(sentence.sourceAssetId)"
          />
          <view v-if="!sentenceCount" class="find">
            <text class="f-ts">
              没有可展示的原文
            </text>
            <text class="f-note f-note-flush">
              本次记录没有句子可供核对。
            </text>
          </view>
        </template>
      </template>

      <view v-if="waitingReport" class="find">
        <text class="f-ts">尚无成功发布的记录</text>
        <text class="f-note f-note-flush">上面仅供核对识别文本，不是正式判读结论。</text>
      </view>

      <view v-if="taskFailed" class="actions">
        <button class="btn btn-soft btn-block" @click="retryUpload">
          这段没听成，可以重新上传
        </button>
      </view>

      <ReportExportActions
        :exporting="exporting"
        :waiting-report="waitingReport"
        :originals-available="originalsAvailable"
        @save="save"
      />

      <text class="note">
        <text class="note-line">本记录仅对通话内容进行转写与标注，</text>
        <text class="note-line">不构成法律意见，不作为处理结果承诺。</text>
        <text class="note-line">所引规范条文请以现行有效版本为准。</text>
      </text>
    </template>

    <view v-else-if="loading" class="empty">
      <text class="empty-text">正在加载记录…</text>
    </view>
    <view v-else-if="loadError" class="empty">
      <text class="empty-text">{{ loadError }}</text>
      <button v-if="currentReportId > 0" class="btn btn-soft" @click="retryReport">重新加载</button>
    </view>
    <view v-else class="empty">
      <image class="empty-img" :src="checkSage" mode="widthFix" />
      <text class="empty-text">记录不存在或已删除</text>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 4px 0 40px;
  background: var(--bg);
}

.roles {
  padding: 0 20px 16px;
}

/* ---------- 问题 / 对话 切换 ---------- */
.tabs {
  position: sticky;
  top: 0;
  z-index: 6;
  display: flex;
  gap: 6px;
  margin: 0 20px 14px;
  padding: 4px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 999px;
  box-shadow: var(--sh);
}

.tab {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 34px;
  border-radius: 999px;
}

.tab-on {
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
}

.tab-t {
  font-size: 14px;
  color: var(--ink-2);
}

.tab-on .tab-t {
  font-weight: 600;
  color: #b05526;
}

.tab-n {
  font-size: 11.5px;
  color: var(--ink-3);
}

.roles .li {
  display: flex;
  align-items: center;
  padding: 10px 0;
}

.f-note-flush {
  margin-top: 0;
}

.actions {
  padding: 0 20px 12px;
}

.hint {
  display: block;
  margin-top: 10px;
  font-size: 12px;
  color: var(--ink-3);
}

/* 报告级提示：用块级容器保证各端都能撑开外边距、按正文同宽居中 */
.report-note {
  margin: 0 20px 14px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-3);
  text-align: center;
}

.audio-card {
  margin: 0 20px 16px;
  padding: 12px 14px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--r-md, 10px);
}

.audio-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.audio-meta {
  flex: 1;
  min-width: 0;
}

.audio-title {
  display: block;
  font-size: 14px;
  color: var(--ink);
}

.audio-name {
  display: block;
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--ink-3);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.audio-btn {
  flex: none;
  min-width: 68px;
  margin: 0;
}

.audio-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.audio-slider {
  flex: 1;
  margin: 0;
}

.audio-slider-empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.audio-loading {
  font-size: 11px;
  color: var(--ink-3);
}

.audio-time {
  flex: none;
  width: 40px;
  font-size: 11px;
  color: var(--ink-3);
  text-align: center;
}

.audio-hint {
  margin: -8px 20px 14px;
}

/* 处理中：这一屏只有卡、提示和按钮 */
.actions + .note {
  margin-top: 0;
}

.imgs {
  display: flex;
  flex-wrap: wrap;
  margin-top: 8px;
}

.img-cell {
  width: 33.3%;
  padding: 4px;
  box-sizing: border-box;
}

.img-thumb {
  width: 100%;
  height: 88px;
  border-radius: 8px;
  background: var(--bg-2, #f4efe8);
}

.img-none {
  display: flex;
  align-items: center;
  justify-content: center;
}

.img-none-t {
  font-size: 12px;
  color: var(--ink-3);
}

.img-cap {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--ink-3);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.empty-img {
  width: 40px;
  height: 40px;
  margin-bottom: 12px;
}
</style>
