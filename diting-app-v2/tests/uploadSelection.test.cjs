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

test('录音与图片可以一起交：组件给两个入口，父页合并两类文件', () => {
  const filename = path.join(root, 'src/components/UploadSelection.vue')
  const source = readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'upload-pick' })
  assert.equal(compileTemplate({ source: descriptor.template.content, filename, id: 'upload-pick', compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  // 组件不再自己声明互斥模式：类型口径只保留 ChosenFile
  assert.match(source, /import type \{ ChosenFile \} from '@\/utils\/choose'/)
  assert.doesNotMatch(script.content, /UploadMode|changeMode/)
  // 录音与图片各有一个入口，可以先后选择、一起提交
  assert.match(descriptor.template.content, /emit\('chooseAudio'\)/)
  assert.match(descriptor.template.content, /emit\('chooseImages'\)/)
  assert.doesNotMatch(descriptor.template.content, /不能混合/)

  const page = readFileSync(path.join(root, 'src/pages/upload/index.vue'), 'utf8')
  assert.doesNotMatch(page, /@change-mode|switchMode/)
  // 两条选择路径都合并两类：录音只替换录音，图片只合并图片
  assert.match(page, /pendingFiles\.value = composePending\(file, imageSelected\.value\)/)
  assert.match(page, /pendingFiles\.value = composePending\(audioSelected\.value, merged\)/)
})

test('文件类型判断在选择逻辑与子组件间共用', () => {
  const source = readFileSync(path.join(root, 'src/utils/choose.ts'), 'utf8')
  const exports = {}
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  vm.runInNewContext(code, { exports }, { filename: 'choose.ts' })
  assert.equal(exports.isImage({ name: 'photo.WEBP' }), true)
  assert.equal(exports.isImage({ name: 'call.m4a' }), false)
})
