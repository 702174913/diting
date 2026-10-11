const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function loadUtil(relative) {
  const filename = path.join(__dirname, '../src', relative)
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText
  const context = { exports: {} }
  vm.runInNewContext(code, context)
  return context.exports
}

const { displaySentenceNote, displayViolationNote } = loadUtil('utils/reportNotes.ts')

test('逐句命中只给规则名，规则库的匹配词不外显', () => {
  assert.equal(
    displaySentenceNote('命中规则《威胁性后果或强制时限表述》：“让你待不下去”、“后果自负”'),
    '威胁性后果或强制时限表述',
  )
  assert.equal(
    displaySentenceNote('命中规则《联系无关第三人或透露欠款信息》： “告诉你妈”'),
    '联系无关第三人或透露欠款信息',
  )
  assert.equal(displaySentenceNote('命中规则《未按约定时间联系》'), '未按约定时间联系')
})

test('解析不到的结构原样返回，不吞信息', () => {
  assert.equal(displaySentenceNote('规则《其他》不适用'), '规则《其他》不适用')
  assert.equal(displaySentenceNote('说话人未确认，按原文核对'), '说话人未确认，按原文核对')
})

test('通话级缺失项：去掉必需表述词表与重复的规则名，保留结论', () => {
  assert.equal(
    displayViolationNote('本通话中未出现必需表述：“我是××公司的”、“工号”、“全程录音”'),
    '本通话中未出现必需表述',
  )
  assert.equal(
    displayViolationNote('触发后 3 条目标角色发言内未出现应答表述（规则《表明身份》）'),
    '触发后 3 条目标角色发言内未出现应答表述',
  )
  assert.equal(displayViolationNote('命中规则《表明身份》：“工号 8823”'), '表明身份')
})

test('句子组件与记录页都在展示层收口，不直接渲染服务端说明全文', () => {
  const item = fs.readFileSync(path.join(__dirname, '../src/components/SentenceItem.vue'), 'utf8')
  assert.match(item, /\{\{ displaySentenceNote\(note\) \}\}/)

  const page = fs.readFileSync(path.join(__dirname, '../src/pages/report/detail.vue'), 'utf8')
  assert.match(page, /\{\{ displayViolationNote\(item\.note\) \}\}/)
  assert.doesNotMatch(page, /\{\{ item\.note \}\}/)
})
