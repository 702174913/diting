import type { ChosenFile } from '@/utils/choose'

const PROBE_TIMEOUT_MS = 5000
const UNKNOWN_DURATION = '无法读取录音时长，请换一个受支持的录音文件后重试'

/** 只读本地文件元数据；读不到时不允许继续上传，避免超长录音白传 OSS。 */
export function readAudioDurationMs(file: ChosenFile): Promise<number> {
  // #ifdef H5
  if (typeof document !== 'undefined') {
    return new Promise((resolve, reject) => {
      const audio = document.createElement('audio')
      let settled = false
      const timer = setTimeout(() => finish(null), PROBE_TIMEOUT_MS)
      function finish(seconds: number | null) {
        if (settled)
          return
        settled = true
        clearTimeout(timer)
        audio.removeEventListener('loadedmetadata', onMetadata)
        audio.removeEventListener('error', onError)
        audio.removeAttribute('src')
        audio.load()
        if (seconds !== null && Number.isFinite(seconds) && seconds > 0) {
          resolve(Math.ceil(seconds * 1000))
        }
        else {
          reject(new Error(UNKNOWN_DURATION))
        }
      }
      function onMetadata() {
        finish(audio.duration)
      }
      function onError() {
        finish(null)
      }
      audio.preload = 'metadata'
      audio.addEventListener('loadedmetadata', onMetadata)
      audio.addEventListener('error', onError)
      audio.src = file.path
      audio.load()
    })
  }
  // #endif

  // 小程序的临时文件路径交给本地音频引擎解析；部分格式/机型无法给出 duration，超时后拒绝。
  return new Promise((resolve, reject) => {
    const audio = uni.createInnerAudioContext()
    let settled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    const startedAt = Date.now()
    function finish(seconds: number | null) {
      if (settled)
        return
      settled = true
      if (timer !== null)
        clearTimeout(timer)
      audio.destroy()
      if (seconds !== null && Number.isFinite(seconds) && seconds > 0) {
        resolve(Math.ceil(seconds * 1000))
      }
      else {
        reject(new Error(UNKNOWN_DURATION))
      }
    }
    function checkDuration() {
      if (settled) {
        return
      }
      const duration = audio.duration
      if (Number.isFinite(duration) && duration > 0) {
        finish(duration)
      }
      else if (Date.now() - startedAt >= PROBE_TIMEOUT_MS) {
        finish(null)
      }
      else {
        timer = setTimeout(checkDuration, 200)
      }
    }
    audio.onCanplay(() => {
      if (timer !== null) {
        clearTimeout(timer)
      }
      checkDuration()
    })
    audio.onError(() => finish(null))
    audio.src = file.path
    timer = setTimeout(checkDuration, 200)
  })
}
