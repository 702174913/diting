const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const root = path.resolve(__dirname, '..')

test('选择区包含文件预览和同意弹层，只发事件；父页保留幂等会话', () => {
  for (const target of ['src/components/UploadSelection.vue', 'src/pages/upload/index.vue']) {
    const filename = path.join(root, target)
    const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename })
    assert.equal(errors.length, 0)
    const script = compileScript(descriptor, { id: 'upload-split' })
    const template = compileTemplate({ source: descriptor.template.content, filename, id: 'upload-split', compilerOptions: { bindingMetadata: script.bindings } })
    assert.equal(template.errors.length, 0)
    if (target.includes('UploadSelection')) {
      assert.match(descriptor.template.content, /<ConsentSheet[\s\S]*?@confirm="emit\('confirm'\)"/)
      assert.match(descriptor.template.content, /@click="emit\('removeFile', file\.path\)"/)
      assert.doesNotMatch(script.content, /submitTask|newIdempotencyKey/)
      assert.doesNotMatch(script.content, /fileKindLabel:/)
    }
    else {
      assert.match(descriptor.template.content, /<UploadSelection[\s\S]*?@confirm="confirmUpload"/)
      assert.match(script.content, /attempt = ref<UploadAttempt \| null>/)
    }
  }
})

test('录音与图片二选一：tab 切换只在组件内发事件，父页负责清空另一种', () => {
  const filename = path.join(root, 'src/components/UploadSelection.vue')
  const source = readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'upload-mode' })
  assert.equal(compileTemplate({ source: descriptor.template.content, filename, id: 'upload-mode', compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  // 类型口径只有一份：UploadMode 来自 utils/choose，组件不再自己声明
  assert.match(source, /import type \{ ChosenFile, UploadMode \} from '@\/utils\/choose'/)
  assert.match(script.content, /mode: \{ type: String, required: true \}/)
  assert.match(descriptor.template.content, /emit\('changeMode', item\.key\)/)
  // 两种选择器互斥：同一个按钮位置只能开其中一个
  assert.match(descriptor.template.content, /v-if="mode === 'audio'"[\s\S]*?emit\('chooseAudio'\)[\s\S]*?v-else[\s\S]*?emit\('chooseImages'\)/)
  assert.doesNotMatch(descriptor.template.content, /一起交/)

  const page = readFileSync(path.join(root, 'src/pages/upload/index.vue'), 'utf8')
  assert.match(page, /@change-mode="switchMode"/)
  // 两条选择路径都只排一种：录音只留录音，图片只留图片
  assert.match(page, /pendingFiles\.value = \[file\]/)
  assert.match(page, /pendingFiles\.value = merged/)
  assert.doesNotMatch(page, /pendingFiles\.value = audioSelected\.value/)
})

test('文件类型判断在选择逻辑与子组件间共用', () => {
  const source = readFileSync(path.join(root, 'src/utils/choose.ts'), 'utf8')
  const exports = {}
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  vm.runInNewContext(code, { exports }, { filename: 'choose.ts' })
  assert.equal(exports.isImage({ name: 'photo.WEBP' }), true)
  assert.equal(exports.isImage({ name: 'call.m4a' }), false)
})
