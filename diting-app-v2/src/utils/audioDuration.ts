import type { ChosenFile } from '@/utils/choose'

/**
 * 只读本地文件头解析录音时长，不创建播放器：
 * m4a 走 mvhd，mp3 走 Xing/Info 帧数或首帧码率估算。
 * 这样 iOS/Android 行为一致，不出声、不抢音频会话；也就在 iOS 上不会打断用户正在听的东西。
 *
 * 读不出来（格式伪装、文件损坏、iCloud 未下载）一律返回 null，由调用方放行、交给服务端复核。
 */

/** 单次读文件头的上限：mp3 的 ID3/首帧、m4a 的 moov 头都在这个范围；不够再按 box 跳读。 */
const HEAD_BYTES = 64 * 1024
/** box 头固定 16 字节：size(4)+type(4)，size==1 时再带 8 字节 largesize。 */
const BOX_HEADER_BYTES = 16
/** 防御损坏文件：box 链最多看这么多个，避免死循环。 */
const MAX_BOX_COUNT = 128
/** 业务上不可能出现的时长，用来丢弃解析出的垃圾值（服务端仍会复核）。 */
const MAX_SANE_DURATION_MS = 24 * 60 * 60 * 1000

/** 按偏移读本地文件：H5 用保留的 File 走 slice，小程序用 readFile 的 position/length。 */
interface ByteSource {
  size: number
  read: (offset: number, length: number) => Promise<Uint8Array | null>
}

interface FileSystemManagerLike {
  readFile: (options: {
    filePath: string
    position?: number
    length?: number
    success: (res: { data: ArrayBuffer | string }) => void
    fail: (err: unknown) => void
  }) => void
  statSync?: (path: string) => { size?: number }
}

interface Mp3Frame {
  offset: number
  version: number
  bitrate: number
  sampleRate: number
  padding: number
  mono: boolean
}

interface BoxHeader {
  type: string
  size: number
  headerSize: number
}

const MP3_BITRATES_MPEG1 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]
const MP3_BITRATES_MPEG2 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]
const MP3_SAMPLE_RATES: Record<number, number[]> = {
  0: [11025, 12000, 8000],
  2: [22050, 24000, 16000],
  3: [44100, 48000, 32000],
}
/** 首层 box 类型，用来在没有扩展名可信度时判断是不是 mp4 容器 */
const MP4_BOX_TYPES = ['ftyp', 'moov', 'mdat', 'free', 'skip', 'wide', 'uuid', 'styp', 'meta']

function readUint32(bytes: Uint8Array, offset: number): number {
  if (offset < 0 || offset + 4 > bytes.length) {
    return Number.NaN
  }
  return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0
}

function readUint64(bytes: Uint8Array, offset: number): number {
  const high = readUint32(bytes, offset)
  const low = readUint32(bytes, offset + 4)
  if (!Number.isFinite(high) || !Number.isFinite(low)) {
    return Number.NaN
  }
  return high * 2 ** 32 + low
}

function asciiAt(bytes: Uint8Array, offset: number, length: number): string {
  let text = ''
  for (let i = 0; i < length; i++) {
    const code = bytes[offset + i]
    if (code === undefined) {
      return ''
    }
    text += String.fromCharCode(code)
  }
  return text
}

/** 秒级时长统一在这里收口：非法值、0、超过 24 小时都当作“解析不出”。 */
function saneDurationMs(duration: number, timescale: number): number | null {
  if (!Number.isFinite(duration) || !Number.isFinite(timescale)) {
    return null
  }
  if (duration <= 0 || timescale <= 0 || timescale > 1_000_000) {
    return null
  }
  const ms = Math.round((duration / timescale) * 1000)
  if (!Number.isFinite(ms) || ms <= 0 || ms > MAX_SANE_DURATION_MS) {
    return null
  }
  return ms
}

function createMiniProgramSource(file: ChosenFile): ByteSource | null {
  const fs = (uni as unknown as { getFileSystemManager?: () => FileSystemManagerLike })
    .getFileSystemManager?.()
  if (!fs) {
    return null
  }
  let size = file.size
  if (!size && fs.statSync) {
    try {
      size = fs.statSync(file.path).size ?? 0
    }
    catch {
      size = 0
    }
  }
  if (size <= 0) {
    return null
  }
  return {
    size,
    read: (offset, length) => new Promise<Uint8Array | null>((resolve) => {
      fs.readFile({
        filePath: file.path,
        position: offset,
        length,
        success: res => resolve(typeof res.data === 'string' ? null : new Uint8Array(res.data)),
        fail: () => resolve(null),
      })
    }),
  }
}

function createByteSource(file: ChosenFile): ByteSource | null {
  // #ifdef H5
  if (typeof document !== 'undefined') {
    const blob = file.blob
    if (!blob) {
      return null
    }
    return {
      size: blob.size,
      read: (offset, length) => blob.slice(offset, offset + length).arrayBuffer()
        .then(buffer => new Uint8Array(buffer))
        .catch(() => null),
    }
  }
  // #endif
  return createMiniProgramSource(file)
}

function parseBoxHeader(bytes: Uint8Array): BoxHeader | null {
  if (bytes.length < 8) {
    return null
  }
  const size32 = readUint32(bytes, 0)
  let size = size32
  let headerSize = 8
  if (size32 === 1) {
    if (bytes.length < BOX_HEADER_BYTES) {
      return null
    }
    size = readUint64(bytes, 8)
    headerSize = 16
  }
  const type = asciiAt(bytes, 4, 4)
  if (!Number.isFinite(size) || type.length !== 4) {
    return null
  }
  if (size !== 0 && size < headerSize) {
    return null
  }
  if (!/^[\x20-\x7E]{4}$/.test(type)) {
    return null
  }
  return { type, size, headerSize }
}

/** 读一个 box 头；size==0 表示“一直到文件尾”。偏移必须落在 [0, end] 内。 */
async function readBox(source: ByteSource, offset: number, end: number): Promise<BoxHeader | null> {
  if (offset < 0 || offset + 8 > end) {
    return null
  }
  const head = await source.read(offset, BOX_HEADER_BYTES)
  if (!head) {
    return null
  }
  const box = parseBoxHeader(head)
  if (!box) {
    return null
  }
  const size = box.size === 0 ? end - offset : box.size
  if (size < box.headerSize || offset + size > end) {
    return null
  }
  return { ...box, size }
}

async function readMvhd(source: ByteSource, payloadOffset: number): Promise<number | null> {
  const bytes = await source.read(payloadOffset, 40)
  if (!bytes || bytes.length < 20) {
    return null
  }
  const version = bytes[0]
  if (version !== 0 && version !== 1) {
    return null
  }
  if (version === 1) {
    if (bytes.length < 32) {
      return null
    }
    const timescale = readUint32(bytes, 4 + 16)
    const high = readUint32(bytes, 4 + 20)
    const low = readUint32(bytes, 4 + 24)
    return saneDurationMs(high * 2 ** 32 + low, timescale)
  }
  const timescale = readUint32(bytes, 4 + 8)
  const duration = readUint32(bytes, 4 + 12)
  return saneDurationMs(duration, timescale)
}

async function durationFromMoov(source: ByteSource, start: number, end: number): Promise<number | null> {
  let offset = start
  for (let count = 0; count < MAX_BOX_COUNT && offset + 8 <= end; count++) {
    const box = await readBox(source, offset, end)
    if (!box) {
      return null
    }
    if (box.type === 'mvhd') {
      return await readMvhd(source, offset + box.headerSize)
    }
    if (box.size <= 0) {
      return null
    }
    offset += box.size
  }
  return null
}

/**
 * 从文件头按 box 链走，只读每个 box 的 16 字节头，按 size 跳到下一个。
 * moov 在头部（faststart）或尾部（iOS 语音备忘录常见）都能走到，10MB 文件也只读几百字节。
 */
async function durationFromMp4(source: ByteSource): Promise<number | null> {
  let offset = 0
  for (let count = 0; count < MAX_BOX_COUNT && offset + 8 <= source.size; count++) {
    const box = await readBox(source, offset, source.size)
    if (!box) {
      return null
    }
    if (box.type === 'moov') {
      return await durationFromMoov(source, offset + box.headerSize, offset + box.size)
    }
    if (box.size <= 0) {
      return null
    }
    offset += box.size
  }
  return null
}

/** ID3v2 头 10 字节，size 是 4 个 7 位；带 footer 时再多 10 字节。 */
function id3v2Size(head: Uint8Array): number {
  if (head.length < 10 || head[0] !== 0x49 || head[1] !== 0x44 || head[2] !== 0x33) {
    return 0
  }
  const size = (head[6] & 0x7F) * 2 ** 21
    + (head[7] & 0x7F) * 2 ** 14
    + (head[8] & 0x7F) * 2 ** 7
    + (head[9] & 0x7F)
  return 10 + size + ((head[5] & 0x10) ? 10 : 0)
}

function parseFrameHeader(bytes: Uint8Array, offset: number): Mp3Frame | null {
  if (offset + 4 > bytes.length) {
    return null
  }
  if (bytes[offset] !== 0xFF || (bytes[offset + 1] & 0xE0) !== 0xE0) {
    return null
  }
  const version = (bytes[offset + 1] >> 3) & 0x03
  const layer = (bytes[offset + 1] >> 1) & 0x03
  const bitrateIndex = (bytes[offset + 2] >> 4) & 0x0F
  const sampleRateIndex = (bytes[offset + 2] >> 2) & 0x03
  const sampleRates = MP3_SAMPLE_RATES[version]
  if (layer !== 1 || !sampleRates || sampleRateIndex === 3) {
    return null
  }
  if (bitrateIndex < 1 || bitrateIndex > 14) {
    return null
  }
  const bitrates = version === 3 ? MP3_BITRATES_MPEG1 : MP3_BITRATES_MPEG2
  return {
    offset,
    version,
    bitrate: bitrates[bitrateIndex] * 1000,
    sampleRate: sampleRates[sampleRateIndex],
    padding: (bytes[offset + 2] >> 1) & 0x01,
    mono: ((bytes[offset + 3] >> 6) & 0x03) === 3,
  }
}

/** 帧长：MPEG-1 Layer III 用 144，MPEG-2/2.5 用 72（bitrate 单位 bps）。 */
function frameLength(frame: Mp3Frame): number {
  return Math.floor((frame.version === 3 ? 144 : 72) * frame.bitrate / frame.sampleRate) + frame.padding
}

/** 要求连续两帧都能解析，避免把随机字节当成帧头（mp4 的 mdat 里也常出现 0xFF 0xFB）。 */
function findFirstFrame(bytes: Uint8Array): Mp3Frame | null {
  for (let offset = 0; offset + 4 <= bytes.length; offset++) {
    const frame = parseFrameHeader(bytes, offset)
    if (!frame) {
      continue
    }
    const next = offset + frameLength(frame)
    if (next + 4 > bytes.length || parseFrameHeader(bytes, next)) {
      return frame
    }
  }
  return null
}

async function durationFromMp3(source: ByteSource): Promise<number | null> {
  const head = await source.read(0, 10)
  if (!head) {
    return null
  }
  const audioStart = id3v2Size(head)
  if (audioStart < 0 || audioStart >= source.size) {
    return null
  }
  const chunk = await source.read(audioStart, HEAD_BYTES)
  if (!chunk) {
    return null
  }
  const frame = findFirstFrame(chunk)
  if (!frame) {
    return null
  }

  // Xing/Info 是本帧内的扩展头，位置由 MPEG 版本和声道数决定；帧数换算出的时长是精确值。
  const sideInfo = frame.version === 3 ? (frame.mono ? 17 : 32) : (frame.mono ? 9 : 17)
  const xingOffset = frame.offset + 4 + sideInfo
  if (xingOffset + 16 <= chunk.length) {
    const tag = asciiAt(chunk, xingOffset, 4)
    if (tag === 'Xing' || tag === 'Info') {
      const flags = readUint32(chunk, xingOffset + 4)
      if ((flags & 1) === 1) {
        const frames = readUint32(chunk, xingOffset + 8)
        const samplesPerFrame = frame.version === 3 ? 1152 : 576
        if (Number.isFinite(frames) && frames > 0) {
          const parsed = saneDurationMs(frames * samplesPerFrame, frame.sampleRate)
          if (parsed !== null) {
            return parsed
          }
        }
      }
    }
  }

  // 没有 Xing/Info（少见）：按首帧码率估算，误差 <1%；服务端仍会复核。
  return saneDurationMs((source.size - audioStart) * 8, frame.bitrate)
}

function sniffContainer(head: Uint8Array): 'mp4' | 'mp3' | null {
  if (head.length >= 8 && MP4_BOX_TYPES.includes(asciiAt(head, 4, 4))) {
    return 'mp4'
  }
  if (head.length >= 3 && head[0] === 0x49 && head[1] === 0x44 && head[2] === 0x33) {
    return 'mp3'
  }
  // 少数 mp3 没有 ID3 头，直接从帧同步开始；只看开头一小段，避免误判。
  if (findFirstFrame(head.subarray(0, 4096))) {
    return 'mp3'
  }
  return null
}

/**
 * 只读本地文件头解析录音时长；解析不出返回 null。
 * 调用方拿到 null 时不要拦用户，交给服务端复核。
 */
export async function readAudioDurationMs(file: ChosenFile): Promise<number | null> {
  const source = createByteSource(file)
  if (!source) {
    return null
  }
  const head = await source.read(0, HEAD_BYTES)
  if (!head || head.length < 8) {
    return null
  }
  try {
    const container = sniffContainer(head)
    if (container === 'mp4') {
      return await durationFromMp4(source)
    }
    if (container === 'mp3') {
      return await durationFromMp3(source)
    }
  }
  catch {
    // 解析失败不是错误：交给服务端
  }
  return null
}
