// S04 前端限定验证：上传必须是「原始字节 PUT」，不能退化成 multipart 表单
import assert from 'node:assert/strict'

// ---- 桩：小程序能力（有 getFileSystemManager 走微信分支） ----
const uniCalls = []
const storage = new Map()
let wechatMode = false
let fakeFileData = new Uint8Array([0, 0, 0, 32, 0x66, 0x74, 0x79, 0x70]).buffer
let requestResponder = () => ({ statusCode: 200 })

globalThis.uni = {
  getStorageSync: k => storage.get(k) ?? '',
  setStorageSync: (k, v) => storage.set(k, v),
  removeStorageSync: k => storage.delete(k),
  request: (options) => {
    uniCalls.push(options)
    options.success(requestResponder())
  },
  // 注意：要在调用时判断模式，不能在建对象时定死（否则切到微信分支后仍拿不到能力）
  getFileSystemManager: () => (wechatMode
    ? { readFile: ({ filePath, success }) => { assert.equal(filePath, 'wxfile://rec.m4a'); success({ data: fakeFileData }) } }
    : undefined),
}

// ---- 桩：H5 的 fetch / XHR ----
const xhrCalls = []
class FakeXhr {
  constructor() { this.headers = {}; this.upload = {} }
  open(method, url) { this.method = method; this.url = url }
  setRequestHeader(k, v) { this.headers[k] = v }
  send(body) {
    xhrCalls.push({ method: this.method, url: this.url, headers: this.headers, body })
    this.upload.onprogress?.({ lengthComputable: true, loaded: 512, total: 1024 })
    this.status = this._status ?? 200
    this.onload?.()
  }
}
globalThis.XMLHttpRequest = FakeXhr
globalThis.Blob = class { constructor(parts, opts) { this.parts = parts; this.type = opts?.type } }
globalThis.fetch = async () => ({ blob: async () => new globalThis.Blob(['x'], { type: 'audio/mp4' }) })

const { uploadRaw, signUpload, submitTask, completeUpload } = await import('/tmp/s04-fe/api-upload.mjs')

const sign = {
  uploadId: 42,
  objectKey: 'recordings/1/abc.m4a',
  uploadUrl: 'https://oss.example.com/recordings/1/abc.m4a?signature=x',
  method: 'PUT',
  headers: { 'Content-Type': 'audio/mp4' },
  expiresAt: '2026-09-24T12:00:00',
  cleanupAfter: '2026-09-25T12:00:00',
}

// 1) H5：PUT + 原始 Blob（不是 FormData），并回调进度
let percent = null
await uploadRaw(sign, 'blob:http://localhost/tmp-1', p => { percent = p })
assert.equal(xhrCalls.length, 1)
assert.equal(xhrCalls[0].method, 'PUT', '上传方法必须是 PUT')
assert.equal(xhrCalls[0].url, sign.uploadUrl)
assert.ok(!(xhrCalls[0].body instanceof FormData), '请求体不能是 FormData')
assert.ok(!/multipart/i.test(JSON.stringify(xhrCalls[0].headers)), '不能出现 multipart 头')
assert.equal(xhrCalls[0].headers['Content-Type'], 'audio/mp4')
assert.equal(percent, 50, 'H5 应能从 XHR 上报进度')

// 2) H5：非 2xx 必须失败，不能假装成功
xhrCalls.length = 0
const originalSend = FakeXhr.prototype.send
FakeXhr.prototype.send = function (body) { this._status = 403; xhrCalls.push({ body }); this.status = 403; this.onload?.() }
await assert.rejects(() => uploadRaw(sign, 'blob:x'), /HTTP 403/)
FakeXhr.prototype.send = originalSend

// 3) 微信：读文件成二进制后 PUT，拿不到百分比时回调 null（不造假进度）
wechatMode = true
uniCalls.length = 0
let wxPercent = 999
await uploadRaw(sign, 'wxfile://rec.m4a', p => { wxPercent = p })
assert.equal(uniCalls.length, 1)
assert.equal(uniCalls[0].method, 'PUT')
assert.ok(uniCalls[0].data instanceof ArrayBuffer, '微信端必须发送 ArrayBuffer 原始字节')
assert.equal(uniCalls[0].header['Content-Type'], 'audio/mp4')
assert.equal(wxPercent, null, '拿不到进度时回调 null，由界面显示状态')
wechatMode = false

// 4) 接口请求体：sign 带同意版本与声明大小；submit 只传 uploadId
globalThis.fetch = async () => ({ blob: async () => new globalThis.Blob(['x']) })
const requestCalls = []
globalThis.uni.request = (options) => { requestCalls.push(options); options.success({ statusCode: 200, data: { code: 0, message: 'ok', data: { id: 7, uploadId: 42 } } }) }
storage.set('diting_token', 'token-abc')
await signUpload('rec.m4a', 2048, 'token-abc')
await completeUpload(42, 'token-abc')
await submitTask(42, 0, 'token-abc')
// uni.request 的 data 直接是对象（由 request 层序列化），这里按对象断言
const bodies = requestCalls.map(c => (typeof c.data === 'string' ? JSON.parse(c.data) : c.data))
assert.equal(bodies[0].consentVersion, 'v1', '签名必须带同意条款版本')
assert.equal(bodies[0].declaredSize, 2048)
assert.deepEqual(Object.keys(bodies[2]).sort(), ['declaredDurationSec', 'uploadId'], 'submit 只能传 uploadId，不能传 objectKey')

console.log('app 上传链路限定验证：4 组断言全部通过（PUT + 原始字节 + 无 multipart + submit 只传 uploadId）')
