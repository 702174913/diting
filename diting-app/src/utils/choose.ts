/** 支持的录音格式（与客服端「通话录音」导出一致；服务端会按文件头复核） */
export const AUDIO_EXTENSIONS = ['mp3', 'wav', 'm4a', 'amr', 'aac', 'flac']

/** 客户端预检大小上限，避免白传一次；服务端 diting.media.max-size-bytes 才是权威值 */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024

export interface ChosenFile {
  path: string
  name: string
  size: number
}

/**
 * 一次提交只交一种：一段录音，或最多 6 张图片。
 * 服务端仍能受理混合任务，但产品口径是二选一，页面按这个类型收口。
 */
export type UploadMode = 'audio' | 'image'

/** 选择后统一判定图片，页面逻辑与选择列表使用同一格式口径。 */
export function isImage(file: Pick<ChosenFile, 'name'>): boolean {
  return /\.(?:jpg|jpeg|png|webp|bmp|gif|tiff|tif)$/i.test(file.name)
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
        throw new Error('录音格式不支持，请选择 MP3、WAV、M4A、AMR、AAC 或 FLAC')
      }
      if (!file.size || file.size > MAX_UPLOAD_BYTES) {
        throw new Error(`录音为空或超过 ${Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)}MB，请重新选择`)
      }
      return { path: URL.createObjectURL(file), name: file.name, size: file.size }
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
        resolve({
          path,
          name: file.name || path.split('/').pop() || 'recording',
          size,
        })
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

/** 图片：与阿里云 OCR 受理格式一致（HEIC 不在其中，需先转 JPG） */
export const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp', 'bmp', 'gif', 'tiff', 'tif']

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
          throw new Error('图片格式不支持：HEIC 等格式请先转成 JPG 或 PNG')
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
            reject(new Error('图片格式不支持：HEIC 等格式请先转成 JPG 或 PNG'))
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
