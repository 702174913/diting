const { test } = require('node:test')
const assert = require('node:assert/strict')
const { Buffer } = require('node:buffer')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const source = fs.readFileSync(path.join(__dirname, '../src/utils/audioDuration.ts'), 'utf8')
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText

function load(sandbox = {}) {
  const exports = {}
  vm.runInNewContext(code, { exports, setTimeout, clearTimeout, ...sandbox })
  return exports
}

/** 模拟小程序文件系统：按 position/length 返回拷贝，避免共享底层 buffer。 */
function miniFileSystem(buffer) {
  return {
    uni: {
      getFileSystemManager: () => ({
        readFile: ({ position = 0, length, success, fail }) => {
          if (!buffer) {
            fail(new Error('gone'))
            return
          }
          const start = Math.min(Math.max(0, position), buffer.length)
          const end = Math.min(buffer.length, start + (length ?? buffer.length - start))
          const data = new ArrayBuffer(end - start)
          new Uint8Array(data).set(buffer.subarray(start, end))
          success({ data })
        },
        statSync: () => ({ size: buffer.length }),
      }),
    },
  }
}

function readBuffer(buffer, file = { name: 'call.m4a', path: 'wxfile://call', size: buffer.length }) {
  return load(miniFileSystem(buffer)).readAudioDurationMs(file)
}

function box(type, payload) {
  const header = Buffer.alloc(8)
  header.writeUInt32BE(8 + payload.length, 0)
  header.write(type, 4, 'latin1')
  return Buffer.concat([header, payload])
}

function mvhdPayload({ version = 0, timescale = 1000, duration = 45_270 } = {}) {
  const payload = Buffer.alloc(version === 1 ? 32 : 20)
  payload.writeUInt8(version, 0)
  if (version === 1) {
    payload.writeUInt32BE(timescale, 20)
    payload.writeUInt32BE(Math.floor(duration / 2 ** 32), 24)
    payload.writeUInt32BE(duration % 2 ** 32, 28)
  }
  else {
    payload.writeUInt32BE(timescale, 12)
    payload.writeUInt32BE(duration, 16)
  }
  return payload
}

function moovBox(options) {
  return box('moov', Buffer.concat([
    box('mvhd', mvhdPayload(options)),
    box('udta', Buffer.alloc(64)),
  ]))
}

function mp4File({ moovLast = false, mvhd } = {}) {
  const ftyp = box('ftyp', Buffer.from('M4A isom', 'latin1'))
  const mdat = box('mdat', Buffer.alloc(2048))
  const moov = moovBox(mvhd)
  return moovLast ? Buffer.concat([ftyp, mdat, moov]) : Buffer.concat([ftyp, moov, mdat])
}

const MPEG1_BITRATES = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]
const MPEG2_BITRATES = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]
const SAMPLE_RATES = { 3: [44_100, 48_000, 32_000], 2: [22_050, 24_000, 16_000], 0: [11_025, 12_000, 8_000] }

function frameHeader({ version = 3, bitrateIndex = 9, sampleRateIndex = 0, mono = true }) {
  return Buffer.from([
    0xFF,
    0xE0 | (version << 3) | (1 << 1) | 1,
    (bitrateIndex << 4) | (sampleRateIndex << 2),
    mono ? 0xC0 : 0x00,
  ])
}

function mp3Frame({ version = 3, bitrateIndex = 9, sampleRateIndex = 0, mono = true, xingFrames } = {}) {
  const bitrate = (version === 3 ? MPEG1_BITRATES : MPEG2_BITRATES)[bitrateIndex] * 1000
  const sampleRate = SAMPLE_RATES[version][sampleRateIndex]
  const length = Math.floor((version === 3 ? 144 : 72) * bitrate / sampleRate)
  const frame = Buffer.alloc(length)
  frameHeader({ version, bitrateIndex, sampleRateIndex, mono }).copy(frame, 0)
  if (xingFrames) {
    const sideInfo = version === 3 ? (mono ? 17 : 32) : (mono ? 9 : 17)
    const offset = 4 + sideInfo
    frame.write('Xing', offset, 'latin1')
    frame.writeUInt32BE(1, offset + 4)
    frame.writeUInt32BE(xingFrames, offset + 8)
  }
  return frame
}

function id3Tag(payloadSize) {
  const tag = Buffer.alloc(10 + payloadSize)
  tag.write('ID3', 0, 'latin1')
  tag[3] = 4
  tag[6] = (payloadSize >> 21) & 0x7F
  tag[7] = (payloadSize >> 14) & 0x7F
  tag[8] = (payloadSize >> 7) & 0x7F
  tag[9] = payloadSize & 0x7F
  return tag
}

test('m4a：moov 在文件头（faststart）时只读 box 头就能拿到精确时长', async () => {
  assert.equal(await readBuffer(mp4File()), 45_270)
})

test('m4a：moov 在文件尾（iOS 语音备忘录常见）也能按 box 链走到', async () => {
  assert.equal(await readBuffer(mp4File({ moovLast: true })), 45_270)
})

test('m4a：mvhd v1 的 64 位 duration 解析正确', async () => {
  assert.equal(
    await readBuffer(mp4File({ mvhd: { version: 1, timescale: 44_100, duration: 44_100 * 37 } })),
    37_000,
  )
})

test('mdat 里恰好出现 mvhd 字节不会被当成真 box（不返回垃圾时长）', async () => {
  const fake = Buffer.alloc(1024)
  fake.write('mvhd', 100, 'latin1')
  fake.writeUInt32BE(0, 104)
  fake.writeUInt32BE(1000, 116)
  fake.writeUInt32BE(900_000, 120)
  const file = Buffer.concat([box('ftyp', Buffer.from('M4A isom', 'latin1')), box('mdat', fake)])
  assert.equal(await readBuffer(file), null)
})

test('m4a：mvhd 时长无效（fragmented mp4 的 0）或文件被截断时返回 null', async () => {
  assert.equal(await readBuffer(mp4File({ mvhd: { timescale: 1000, duration: 0 } })), null)
  assert.equal(await readBuffer(Buffer.from([0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70])), null)
})

test('mp3：Xing 帧数换算时长（MPEG-1 44.1kHz）', async () => {
  const frame = mp3Frame({ xingFrames: 1000 })
  assert.equal(await readBuffer(Buffer.concat([id3Tag(64), frame, frame]), { name: 'call.mp3' }), 26_122)
})

test('mp3：MPEG-2 16kHz/64kbps 用对码率表，不会算成 4 倍时长', async () => {
  const frame = mp3Frame({ version: 2, bitrateIndex: 12, sampleRateIndex: 2, xingFrames: 1000 })
  assert.equal(await readBuffer(Buffer.concat([id3Tag(64), frame, frame]), { name: 'call.mp3' }), 36_000)
})

test('mp3：ID3v2 大封面（超过单次读取窗口）按 tag 长度跳过', async () => {
  const frame = mp3Frame({ xingFrames: 500 })
  const file = Buffer.concat([id3Tag(200_000), frame, frame])
  assert.equal(await readBuffer(file, { name: 'call.mp3' }), 13_061)
})

test('mp3：没有 Xing/Info 时按首帧码率估算，误差在 1% 内', async () => {
  const frames = Array.from({ length: 100 }, () => mp3Frame())
  const file = Buffer.concat(frames)
  const parsed = await readBuffer(file, { name: 'call.mp3' })
  const exact = (file.length * 8) / 128_000 * 1000
  assert.ok(Math.abs(parsed - exact) <= 2)
})

test('扩展名不可信：内容是 mp4 但名字叫 .mp3 也能解析，反之返回 null', async () => {
  assert.equal(await readBuffer(mp4File(), { name: 'call.mp3' }), 45_270)
  assert.equal(await readBuffer(Buffer.from('not an audio file at all'), { name: 'call.m4a' }), null)
})

test('本地文件读不到（iCloud 未下载/路径失效）返回 null，由调用方放行', async () => {
  const read = load({ uni: { getFileSystemManager: () => ({ readFile: ({ fail }) => fail(new Error('gone')) }) } }).readAudioDurationMs
  assert.equal(await read({ name: 'call.m4a', path: 'wxfile://gone', size: 100 }), null)
})

test('H5 用保留的 File 按偏移切片，不整读文件', async () => {
  const buffer = mp4File()
  const slices = []
  const blob = {
    size: buffer.length,
    slice: (start, end) => ({
      arrayBuffer: async () => {
        slices.push([start, end])
        const data = new ArrayBuffer(end - start)
        new Uint8Array(data).set(buffer.subarray(start, end))
        return data
      },
    }),
  }
  const read = load({ document: {} }).readAudioDurationMs
  assert.equal(await read({ name: 'call.m4a', path: 'blob:call', size: buffer.length, blob }), 45_270)
  assert.ok(slices.every(([, end]) => end <= 64 * 1024), '首轮只读 64KB 以内的窗口')
})

const golden = [
  ['cuishou-sample-10s.mp3', 10_271],
  ['cuishou-sample-45s.mp3', 45_270],
  ['cuishou-sample-48s.mp3', 48_171],
  ['cuishou-voice-audition.mp3', 40_085],
]

for (const [name, expected] of golden) {
  const file = path.join(__dirname, '../../example', name)
  test(`样音 ${name} 解出的时长与 ffprobe 相差不超过 300ms`, { skip: !fs.existsSync(file) }, async () => {
    const parsed = await readBuffer(fs.readFileSync(file), { name, path: 'wxfile://call', size: fs.statSync(file).size })
    assert.ok(Math.abs(parsed - expected) <= 300, `解析值 ${parsed} 与 ${expected} 相差过大`)
  })
}
