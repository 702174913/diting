const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const source = fs.readFileSync(path.join(__dirname, '../src/utils/audioDuration.ts'), 'utf8')
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText

function durationReader(sandbox) {
  const exports = {}
  vm.runInNewContext(code, { exports, setTimeout, clearTimeout, ...sandbox })
  return exports.readAudioDurationMs
}

test('H5 从本地 blob 读取音频元数据，结束后释放播放器但不撤销待上传文件', async () => {
  const listeners = new Map()
  let cleaned = false
  const audio = {
    duration: 600.001,
    addEventListener: (name, handler) => listeners.set(name, handler),
    removeEventListener: name => listeners.delete(name),
    removeAttribute: () => { cleaned = true },
    load: () => {
      if (!cleaned)
        queueMicrotask(() => listeners.get('loadedmetadata')?.())
    },
  }
  const read = durationReader({ document: { createElement: () => audio } })
  assert.equal(await read({ name: 'call.mp3', path: 'blob:call', size: 20 }), 600001)
  assert.equal(cleaned, true)
  assert.equal(listeners.size, 0)
})

test('H5 元数据时长无效时拒绝，不当作零秒通过', async () => {
  const listeners = new Map()
  const audio = {
    duration: Infinity,
    addEventListener: (name, handler) => listeners.set(name, handler),
    removeEventListener: name => listeners.delete(name),
    removeAttribute() {},
    load: () => queueMicrotask(() => listeners.get('loadedmetadata')?.()),
  }
  const read = durationReader({ document: { createElement: () => audio } })
  await assert.rejects(read({ name: 'call.mp3', path: 'blob:call', size: 20 }), /无法读取录音时长/)
})

test('小程序从本地音频引擎读取时长，不发送文件，并销毁播放器', async () => {
  let onCanplay
  let destroyed = false
  const audio = {
    duration: 92.3,
    onCanplay: (cb) => { onCanplay = cb },
    onError() {},
    destroy: () => { destroyed = true },
  }
  const read = durationReader({ uni: { createInnerAudioContext: () => audio } })
  const promise = read({ name: 'call.m4a', path: 'wxfile://call', size: 20 })
  onCanplay()
  assert.equal(await promise, 92300)
  assert.equal(destroyed, true)
})
