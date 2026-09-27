const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const filename = path.join(__dirname, '../src/components/SentenceItem.vue')
const { descriptor, errors } = parse(fs.readFileSync(filename, 'utf8'), { filename })
const script = compileScript(descriptor, { id: 'source-item' })
const template = compileTemplate({
  source: descriptor.template.content,
  filename,
  id: 'source-item',
  compilerOptions: { bindingMetadata: script.bindings },
})
function label(sentence, sourceLabel) {
  const compiled = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  vm.runInNewContext(compiled, {
    exports,
    require: name => name === 'vue'
      ? vue
      : name === '@/utils/reportNotes'
        ? { displaySentenceNote: note => note }
        : { formatClock: () => '00:00' },
  })
  return exports.default.setup({ sentence, sourceLabel }, { expose() {} }).speakerLabel.value
}

test('OCR句子依据sourceAssetId显示真实图片，不伪造说话人/00:00', () => {
  assert.equal(errors.length, 0)
  assert.equal(template.errors.length, 0)
  assert.equal(label({ sourceAssetId: 7, role: null, speakerId: null }, '图片 2 · 截图.png'), '图片 2 · 截图.png')
  assert.match(descriptor.template.content, /sentence\.sourceAssetId != null \? speakerLabel/)
})

test('普通音频句子直接展示听悟 SpeakerId，不按角色或字母重命名', () => {
  assert.equal(label({ sourceAssetId: null, role: 'AGENT', speakerId: 27 }), '说话人 27')
})
