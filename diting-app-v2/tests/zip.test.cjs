const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

/** 在独立 vm 上下文里执行：该上下文没有 TextEncoder，等同小程序真机环境 */
function loadZip() {
  const source = readFileSync(path.join(__dirname, '../src/utils/zip.ts'), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  } }).outputText
  const exports = {}
  vm.runInNewContext(code, { exports }, { filename: 'zip.ts' })
  return { exports, source }
}

function asBuffer(bytes) {
  return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength)
}

test('不依赖 TextEncoder：小程序端也能生成 ZIP', () => {
  const { exports, source } = loadZip()
  assert.doesNotMatch(source, /new TextEncoder/)
  assert.equal(vm.runInNewContext('typeof TextEncoder'), 'undefined')

  const zip = exports.createZip([{ name: '报告.zip', data: new Uint8Array([1, 2, 3]) }])
  const buffer = asBuffer(zip)
  assert.equal(buffer.readUInt32LE(0), 0x04034B50)
  assert.ok(buffer.includes(Buffer.from('报告.zip', 'utf8')))
})

test('中文与 emoji 文件名按 UTF-8 编码且不产生代理对残留', () => {
  const { exports } = loadZip()
  const buffer = asBuffer(exports.createZip([
    { name: '录音-😀.mp3', data: new Uint8Array([9]) },
  ]))
  assert.ok(buffer.includes(Buffer.from('录音-😀.mp3', 'utf8')))
})
