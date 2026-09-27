import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { toast } from 'vue-sonner'
import type { ExportJob } from '@/api/admin'
import { fetchExportDownload, fetchExportJob } from '@/api/admin'
import { ApiError } from '@/api/request'
import { useUserStore } from '@/stores/user'

/**
 * 导出作业跟踪（S11 的异步导出）。
 *
 * 为什么需要全局 store：导出是异步的（POST 建作业 → 轮询 → 完成才发下载地址），
 * 而「发起导出的那个弹窗」很可能在作业跑完前就被关掉了。
 * 所以作业列表挂在 store 上、由顶栏的「导出任务」入口统一查看。
 *
 * 刷新存活：未完成作业的 id 写 localStorage，重登后按 id 回查（作业归属由服务端校验，
 * 不属于当前账号会 403，此时静默丢弃 —— 可能是换了账号登录）。
 */
const STORAGE_KEY = 'diting_admin_export_jobs'
const AUTO_DOWNLOAD_KEY = 'diting_admin_export_auto_download'
const POLL_INTERVAL_MS = 3000

export interface TrackedExport extends ExportJob {
  /** 冗余记录来源与报告编号，弹窗里不用再回查录音 */
  recordingId: number
  reportNo: string
  reportVersion: number | null
  /** 本浏览器是否已经为这个作业触发过自动下载：刷新/多标签页都不会重下一遍 */
  autoDownloaded?: boolean
}

/**
 * 触发浏览器下载。
 *
 * 用临时 `<a>` 点击而不是 `window.open`：自动下载没有用户手势，`window.open` 会被弹窗拦截器吞掉。
 * 是否“另存为”由服务端的 `Content-Disposition` 决定（导出下载已带 attachment 与文件名），
 * 前端不依赖 `download` 属性——跨域时它本来就无效。
 */
export function saveToDisk(url: string) {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.rel = 'noopener'
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
}

/** 读 localStorage 里的「已自动下载」标记：另一个标签页可能刚刚下过同一个作业 */
function markedDownloaded(exportId: number): boolean {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed)
      && parsed.some(item => item && item.exportId === exportId && item.autoDownloaded === true)
  }
  catch {
    return false
  }
}

/**
 * 跨标签页互斥执行（Web Locks 不可用时降级为直接执行）。
 * 两个标签页同时轮询到同一作业完成，只应该有一个真的去下载：否则浏览器会把第二条
 * 自动下载拦成「允许本站多次下载？」。
 */
async function withCrossTabLock(exportId: number, run: () => Promise<void>) {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks
  if (!locks) {
    await run()
    return
  }
  await locks.request(`diting-export-download-${exportId}`, run)
}

function isPending(job: ExportJob) {
  return job.status === 'QUEUED' || job.status === 'RUNNING'
}

function readAutoDownload(): boolean {
  try {
    // 默认开：用户点导出时期望的就是“跑完自己下来”，关掉只是少数场景
    return localStorage.getItem(AUTO_DOWNLOAD_KEY) !== '0'
  }
  catch {
    return true
  }
}

/** 白名单投影：兼容旧缓存，同时彻底移除曾经落盘的签名 URL。 */
function persistable(job: TrackedExport): TrackedExport {
  return {
    exportId: job.exportId,
    status: job.status,
    withAudio: job.withAudio,
    fileType: job.fileType,
    errorCode: job.errorCode,
    createdAt: job.createdAt,
    expiresAt: job.expiresAt,
    recordingId: job.recordingId,
    reportNo: job.reportNo,
    reportVersion: job.reportVersion,
    autoDownloaded: job.autoDownloaded === true,
  }
}

function loadPersisted(): TrackedExport[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) : []
    const jobs = Array.isArray(parsed)
      ? parsed.filter(item => item && Number.isInteger(item.exportId)).map(persistable)
      : []
    localStorage.setItem(STORAGE_KEY, JSON.stringify(jobs))
    return jobs
  }
  catch {
    return []
  }
}

export const useExportTaskStore = defineStore('exportTask', () => {
  const jobs = ref<TrackedExport[]>(loadPersisted())
  const autoDownloadEnabled = ref(readAutoDownload())
  let timer: ReturnType<typeof setInterval> | null = null
  /** 正在签发下载地址的作业：避免同一作业被并发签发两次（每次都会写一条审计） */
  const downloading = new Set<number>()

  const pendingCount = computed(() => jobs.value.filter(isPending).length)

  function setAutoDownload(enabled: boolean) {
    autoDownloadEnabled.value = enabled
    try {
      localStorage.setItem(AUTO_DOWNLOAD_KEY, enabled ? '1' : '0')
    }
    catch {
      // 存不下本次会话仍生效
    }
  }

  /**
   * 自动下载：完成态才签发一次短时地址，然后交给浏览器另存。
   *
   * force = 用户明确点了「导出」：不适用「已自动下载过就跳过」的去重
   * （那个标记是为了防轮询/多标签页/刷新后重复自动下载，不是用来挡住一次明确点击）；
   * silent = 成功提示由调用方给（页面那条 loading→success 更贴用户视角）。
   * 返回：这次是否真的签发了下载。
   */
  async function autoDownload(job: TrackedExport, options: { force?: boolean, silent?: boolean } = {}): Promise<boolean> {
    if (downloading.has(job.exportId)) {
      return false
    }
    if (!options.force && job.autoDownloaded) {
      return false
    }
    downloading.add(job.exportId)
    try {
      let started = false
      await withCrossTabLock(job.exportId, async () => {
        if (!options.force && markedDownloaded(job.exportId)) {
          job.autoDownloaded = true
          persist()
          // 服务端会复用同一份产物（同一个作业 id）：跳过下载必须说出来，不能装作没事
          toast.success(`这份导出之前已经下载过（作业 #${job.exportId}），需要再下一份就在「导出任务」里下载`)
          return
        }
        // 标记放在 await 之前：并发轮询不会把同一个作业下载两遍
        job.autoDownloaded = true
        persist()
        saveToDisk(await download(job.exportId))
        started = true
        if (!options.silent) {
          toast.success(`导出完成（作业 #${job.exportId}），已自动开始下载`)
        }
      })
      return started
    }
    catch (e) {
      toast.error(`导出完成，但自动下载失败${e instanceof Error ? `：${e.message}` : ''}，可在「导出任务」里手动下载`)
      return false
    }
    finally {
      downloading.delete(job.exportId)
    }
  }

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(jobs.value.map(persistable)))
    }
    catch {
      // 存不下就算了：作业本身在服务端跑，前端只是失去刷新后的跟踪
    }
  }

  function merge(job: ExportJob, meta?: { recordingId: number, reportNo: string, reportVersion: number | null }) {
    const index = jobs.value.findIndex(item => item.exportId === job.exportId)
    const previousStatus = index >= 0 ? jobs.value[index].status : null
    if (index >= 0) {
      jobs.value[index] = { ...jobs.value[index], ...job }
    }
    else if (meta) {
      jobs.value.unshift({ ...job, ...meta })
    }
    persist()
    // 跃迁到终态时给一次反馈：发起导出的弹窗可能早被关掉了，这是唯一的反馈通道。
    // 完成时直接自动下载，不再要求用户回弹窗再点一次。
    if (previousStatus && previousStatus !== job.status && job.status === 'COMPLETED') {
      const tracked = jobs.value.find(item => item.exportId === job.exportId)
      if (!tracked || tracked.autoDownloaded) {
        return
      }
      if (autoDownloadEnabled.value) {
        void autoDownload(tracked)
      }
      else {
        toast.success(`导出完成（作业 #${job.exportId}），在「导出任务」里下载`)
      }
    }
    else if (previousStatus && previousStatus !== job.status && job.status === 'FAILED') {
      toast.error(`导出失败（作业 #${job.exportId}）${job.errorCode ? `：${job.errorCode}` : ''}`)
    }
  }

  async function refreshAll() {
    const token = useUserStore().token
    if (!token || jobs.value.length === 0) {
      return
    }
    const snapshot = [...jobs.value]
    await Promise.all(snapshot.map(async (item) => {
      try {
        // 状态查询只回状态：下载地址由下载动作显式签发，不在这里合并
        const latest: ExportJob = await fetchExportJob(token, item.exportId)
        merge(latest)
        return null
      }
      catch (e) {
        // 403/404：作业不属于当前账号（或已不存在）→ 丢弃，不留幽灵条目
        if (e instanceof ApiError && (e.code === 403 || e.code === 404)) {
          jobs.value = jobs.value.filter(job => job.exportId !== item.exportId)
          persist()
        }
        return null
      }
    }))
    if (pendingCount.value === 0) {
      stopPolling()
    }
  }

  function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function ensurePolling() {
    if (!timer && pendingCount.value > 0) {
      timer = setInterval(() => {
        void refreshAll()
      }, POLL_INTERVAL_MS)
    }
  }

  /**
   * 新作业入列：建完就立刻开始轮询。
   * 返回这次是否已经签发下载，调用方据此决定提示文案（不说假话）。
   */
  async function track(job: ExportJob, meta: { recordingId: number, reportNo: string, reportVersion: number | null }): Promise<{ downloaded: boolean }> {
    merge(job, meta)
    ensurePolling()
    // 服务端会复用仍有效的产物，此时作业建完就是完成态、不经过「状态跃迁」。
    // 用户刚点完导出，所以这一跳必须强制下载：去重标记不能挡住一次明确点击。
    const tracked = jobs.value.find(item => item.exportId === job.exportId)
    let downloaded = false
    if (tracked && tracked.status === 'COMPLETED') {
      if (autoDownloadEnabled.value) {
        downloaded = await autoDownload(tracked, { force: true, silent: true })
      }
      else {
        toast.success(`导出产物已就绪（作业 #${job.exportId}），自动下载已关闭：在「导出任务」里下载`)
      }
    }
    await refreshAll()
    return { downloaded }
  }

  /** 供弹窗打开时手动刷新一次（比如刷新页面后回来） */
  async function refresh() {
    await refreshAll()
    ensurePolling()
  }

  function forget(exportId: number) {
    jobs.value = jobs.value.filter(job => job.exportId !== exportId)
    persist()
  }

  /**
   * 下载：完成态才签发一次短时地址并记一条签发审计。
   * 返回地址供调用方打开；失败抛 ApiError 由调用方提示。
   */
  async function download(exportId: number): Promise<string> {
    const token = useUserStore().token
    if (!token) {
      throw new ApiError(401, '登录已过期，请重新登录')
    }
    const result = await fetchExportDownload(token, exportId)
    return result.downloadUrl
  }

  /** 退出登录时清掉，避免下一个账号看到上一个账号的作业 */
  function clear() {
    stopPolling()
    jobs.value = []
    persist()
  }

  return { jobs, pendingCount, autoDownloadEnabled, setAutoDownload, track, refresh, forget, download, clear, stopPolling }
})
