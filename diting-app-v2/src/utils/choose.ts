/** 上传接口接受 MP3 / M4A 音频（小程序端也只放行这两种后缀） */
export const AUDIO_EXTENSIONS = ['mp3', 'm4a']

/** 上传接口只接受 JPG / PNG，不接受 JPEG、WEBP 等其他后缀 */
export const IMAGE_EXTENSIONS = ['jpg', 'png']

/** 单文件不超过 10MB；每个文件单独发起一次上传请求 */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

export interface ChosenFile {
  path: string
  name: string
  size: number
  /** H5 选择时保留的 File，用来按偏移读文件头；小程序只有临时路径 */
  blob?: Blob
  /** 本地解析得到的录音时长；图片不需要 */
  durationMs?: number
}

/**
 * 一次提交可混合：最多一段录音，加上最多 6 张图片。
 * 两类文件都走同一提交会话，由后端分类处理。
 */

/** 后缀判定统一走这一份实现，选择、页面分类、签发、提交共用，避免各处正则漂移。 */
export function hasExtension(name: string, extensions: string[]): boolean {
  const extension = name.split('.').pop()?.toLowerCase() ?? ''
  return extensions.includes(extension)
}

/** 选择后统一判定图片，页面逻辑与选择列表使用同一格式口径。 */
export function isImage(file: Pick<ChosenFile, 'name'>): boolean {
  return hasExtension(file.name, IMAGE_EXTENSIONS)
}

/** 录音后缀口径只在这里定义一次；选择、签发、提交三处共用，避免各处正则漂移。 */
export function isAudio(file: Pick<ChosenFile, 'name'>): boolean {
  return hasExtension(file.name, AUDIO_EXTENSIONS)
}

interface ChooseResult {
  tempFiles: { path: string, name?: string, size?: number }[]
}

/** H5 使用浏览器文件选择器；blob URL 可被现有 uploadRaw 的 fetch 路径读取。 */
function chooseBrowserFiles(accept: string, multiple: boolean): Promise<File[]> {
  return new Promise((resolve, reject) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = accept
    input.multiple = multiple
    input.hidden = true
    input.addEventListener('change', () => {
      const files = Array.from(input.files ?? [])
      input.remove()
      if (files.length) {
        resolve(files)
      }
      else {
        reject(new Error('已取消选择'))
      }
    }, { once: true })
    input.addEventListener('cancel', () => {
      input.remove()
      reject(new Error('已取消选择'))
    }, { once: true })
    document.body.append(input)
    input.click()
  })
}

/** 释放 H5 选择时创建的临时 blob URL；小程序文件路径无需处理。 */
export function releaseChosenFile(file: ChosenFile): void {
  if (file.path.startsWith('blob:') && typeof URL !== 'undefined') {
    URL.revokeObjectURL(file.path)
  }
}

interface ChooseOptions {
  count: number
  type?: 'all' | 'video' | 'image' | 'file'
  extension?: string[]
  success: (res: ChooseResult) => void
  fail: (err: unknown) => void
}

/**
 * 选一段录音。
 * 小程序端从「聊天记录 / 通话录音 App 分享」里选，所以优先 chooseMessageFile；
 * H5 使用浏览器文件选择器，小程序使用 chooseMessageFile/chooseFile。
 */
export function chooseAudioFile(): Promise<ChosenFile> {
  // #ifdef H5
  if (typeof document !== 'undefined') {
    return chooseBrowserFiles(AUDIO_EXTENSIONS.map(ext => `.${ext}`).join(','), false).then(([file]) => {
      const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
      if (!AUDIO_EXTENSIONS.includes(extension)) {
        throw new Error('录音仅支持 MP3 / M4A 格式，请先转换')
      }
      if (!file.size || file.size > MAX_UPLOAD_BYTES) {
        throw new Error(`录音为空或超过 ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)}MB，请重新选择`)
      }
      return { path: URL.createObjectURL(file), name: file.name, size: file.size, blob: file }
    })
  }
  // #endif

  // SAFETY: uni 的类型定义按平台拆分，公共 d.ts 里没有 chooseMessageFile / chooseFile。
  // 两者都是可选能力，下面只把它们当成「有则调用」的弱类型入口，调用参数由本地 ChooseOptions 约束。
  const api = uni as unknown as {
    chooseMessageFile?: (options: ChooseOptions) => void
    chooseFile?: (options: ChooseOptions) => void
  }

  return new Promise<ChosenFile>((resolve, reject) => {
    const options: ChooseOptions = {
      count: 1,
      extension: AUDIO_EXTENSIONS,
      success: (res) => {
        const file = res.tempFiles?.[0]
        if (!file) {
          reject(new Error('没有选中文件'))
          return
        }
        const path = file.path
        const size = file.size ?? 0
        if (size > MAX_UPLOAD_BYTES) {
          reject(new Error(`文件超过 ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)}MB，请先压缩或剪裁`))
          return
        }
        const name = file.name || path.split('/').pop() || 'recording'
        if (!isAudio({ name })) {
          reject(new Error('录音仅支持 MP3 / M4A 格式，请先转换'))
          return
        }
        resolve({ path, name, size })
      },
      fail: reject,
    }

    if (api.chooseMessageFile) {
      api.chooseMessageFile({ ...options, type: 'file' })
      return
    }
    if (api.chooseFile) {
      api.chooseFile(options)
      return
    }
    reject(new Error('当前平台不支持选择文件'))
  })
}

/** 单张图片上限：官方文档写「二进制不超过 10MB」 */
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024

/** 一次最多几张图片（与后端 max-images-per-task 一致） */
export const MAX_IMAGES = 6

interface ChooseImageResult {
  tempFilePaths?: string[]
  tempFiles?: { path?: string, size?: number }[]
}

interface ChooseImageOptions {
  count: number
  sizeType?: ('original' | 'compressed')[]
  sourceType?: ('album' | 'camera')[]
  success: (res: ChooseImageResult) => void
  fail: (err: unknown) => void
}

/**
 * 选图片（最多 6 张）。
 * H5 使用浏览器多选文件，小程序使用 chooseImage（平台压缩）。
 * HEIC 在客户端按扩展名预检，服务端仍会按文件头校验。
 */
export function chooseImages(max: number = MAX_IMAGES): Promise<ChosenFile[]> {
  // #ifdef H5
  if (typeof document !== 'undefined') {
    return chooseBrowserFiles(IMAGE_EXTENSIONS.map(ext => `.${ext}`).join(','), true).then((files) => {
      if (files.length > Math.min(max, MAX_IMAGES)) {
        throw new Error(`一次最多 ${Math.min(max, MAX_IMAGES)} 张图片`)
      }
      for (const file of files) {
        const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
        if (!IMAGE_EXTENSIONS.includes(extension)) {
          throw new Error('图片仅支持 JPG / PNG，请先转换')
        }
        if (!file.size || file.size > MAX_IMAGE_BYTES) {
          throw new Error(`图片为空或超过 ${Math.round(MAX_IMAGE_BYTES / 1024 / 1024)}MB，请重新选择`)
        }
      }
      return files.map(file => ({ path: URL.createObjectURL(file), name: file.name, size: file.size }))
    })
  }
  // #endif

  // SAFETY: uni 的公共类型未覆盖所有平台的 chooseImage；运行前检查能力是否存在。
  const api = uni as unknown as { chooseImage?: (options: ChooseImageOptions) => void }
  const chooseImage = api.chooseImage
  if (!chooseImage) {
    return Promise.reject(new Error('当前平台不支持选择图片'))
  }
  return new Promise<ChosenFile[]>((resolve, reject) => {
    chooseImage({
      count: Math.min(max, MAX_IMAGES),
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const paths = res.tempFilePaths ?? (res.tempFiles ?? [])
          .map(item => item.path ?? '')
          .filter(Boolean)
        if (paths.length === 0) {
          reject(new Error('没有选中图片'))
          return
        }
        const files: ChosenFile[] = []
        for (const path of paths) {
          const name = path.split('/').pop() || 'photo.jpg'
          const extension = name.split('.').pop()?.toLowerCase() ?? ''
          if (!IMAGE_EXTENSIONS.includes(extension)) {
            reject(new Error('图片仅支持 JPG / PNG，请先转换'))
            return
          }
          const size = (res.tempFiles ?? []).find(item => item.path === path)?.size ?? 0
          if (size > MAX_IMAGE_BYTES) {
            reject(new Error(`单张图片超过 ${Math.round(MAX_IMAGE_BYTES / 1024 / 1024)}MB，请先压缩`))
            return
          }
          files.push({ path, name, size })
        }
        resolve(files)
      },
      fail: reject,
    })
  })
}
