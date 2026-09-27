const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

function evaluate(relative) {
  const filename = path.join(__dirname, '../src', relative)
  const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText
  const exports = {}
  vm.runInNewContext(source, { exports, require: () => ({}) }, { filename })
  return exports
}

test('OCR 失败原因：官方错误码映射成中文，没映射到的原样显示', () => {
  const { OCR_ERROR_TEXT, OCR_STATUS_TEXT, labelOf } = evaluate('lib/enums.ts')
  // 上线门禁里最容易踩的两个：服务没开通、子账号没授权——文案必须给出可执行的处置动作
  assert.match(OCR_ERROR_TEXT.ocrServiceNotOpen, /尚未开通/)
  assert.match(OCR_ERROR_TEXT.noPermission, /AliyunOCRFullAccess/)
  assert.match(OCR_ERROR_TEXT.illegalImageSize, /15–8192/)
  assert.match(OCR_ERROR_TEXT['Throttling.User'], /自动重试/)
  // 服务端会写 EXHAUSTED（重试次数用尽 / 云端明确拒绝），状态文案不能漏，否则界面直接露英文码
  assert.match(OCR_STATUS_TEXT.EXHAUSTED, /已停止重试/)
  assert.match(OCR_STATUS_TEXT.FAILED, /识别失败/)
  // 未知码不静默、不假装认识：原样返回，运维拿原码去查阿里云错误码表
  assert.equal(labelOf(OCR_ERROR_TEXT, 'SomeNewCode2027'), 'SomeNewCode2027')
  assert.equal(labelOf(OCR_ERROR_TEXT, null), '—')
})

test('后台图片清单展示 OCR 失败原因，且模板能编译', () => {
  const filename = path.join(__dirname, '../src/views/recordings/index.vue')
  const source = fs.readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'recordings-ocr-error' })
  assert.equal(
    compileTemplate({
      source: descriptor.template.content,
      filename,
      id: 'recordings-ocr-error',
      compilerOptions: { bindingMetadata: script.bindings },
    }).errors.length,
    0,
  )
  // 有失败原因才出现这一行；成功的资产不占位置
  assert.match(source, /v-if="asset\.ocrError"[\s\S]*?labelOf\(OCR_ERROR_TEXT, asset\.ocrError\)/)
  assert.match(source, /OCR_ERROR_TEXT,/)
  // 接口类型要带 ocrError：后端加了字段而前端类型没跟上，模板里的 asset.ocrError 只会是 undefined
  const api = fs.readFileSync(path.join(__dirname, '../src/api/admin.ts'), 'utf8')
  assert.match(api, /ocrStatus: string\n\s+ocrError: string \| null/)
})
