const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const ctx = { exports: {} }
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/utils/taskSummary.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText, ctx)
const { taskSummary } = ctx.exports

test('列表使用违规总数，零命中句的通话级问题也不能显示绿色无问题', () => {
  const view = taskSummary({ status: 'COMPLETED', reportPublished: true, flaggedCount: 0, violationCount: 2 })
  assert.equal(view.variant, 'warn')
  assert.match(view.text, /2 项/)
})
test('无发布报告不是无违规；历史待确认态自动生成报告', () => {
  assert.equal(taskSummary({ status: 'COMPLETED' }).text, '尚无已发布报告')
  assert.equal(taskSummary({ status: 'WAITING_ROLE' }).text, '正在生成报告')
  assert.equal(taskSummary({ status: 'COMPLETED', reportPublished: true }).text, '本轮规则未检出问题')
})
test('失败或处理中的任务不展示无问题结论', () => {
  for (const status of ['FAILED', 'INVALID', 'TRANSCRIBING', 'QUEUED']) {
    assert.equal(taskSummary({ status }).variant, 'run')
  }
})
test('实际卡片绑定新摘要并可编译', () => {
  const filename = path.join(__dirname, '../src/components/RecordCard.vue')
  const source = fs.readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'record-card' })
  assert.equal(compileTemplate({ source: descriptor.template.content, filename, id: 'record-card', compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  assert.match(source, /taskSummary\(props.record\)/)
  assert.doesNotMatch(source, /progress%|record\.progress|没有发现要留意的话/)
})
