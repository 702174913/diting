const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const source = fs.readFileSync(path.join(__dirname, '../src/utils/reportPresentation.ts'), 'utf8')
const code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText
const context = { exports: {} }
vm.runInNewContext(code, context)
const { reportPresentation } = context.exports
function report(overrides) {
  return {
    reportVersionId: 10,
    status: 'PUBLISHED',
    latestStatus: 'PUBLISHED',
    taskStatus: 'COMPLETED',
    reportVersion: 1,
    flaggedCount: 0,
    violationCount: 0,
    ...overrides,
  }
}

test('首次失败报告即使有版本ID也不能当成功或导出', () => {
  const value = reportPresentation(report({ status: 'FAILED', latestStatus: 'FAILED' }))
  assert.equal(value.published, false)
  assert.equal(value.headline, '记录生成失败')
  assert.match(value.lead, /不能.*导出/)
  assert.doesNotMatch(value.headline, /未检出|没有发现/)
})

test('must-have缺失项没有命中句也必须明确提示问题', () => {
  const value = reportPresentation(report({ violationCount: 1, flaggedCount: 0, riskScore: 80 }))
  assert.equal(value.published, true)
  assert.match(value.headline, /1 项需要留意/)
  assert.match(value.lead, /通话级缺失/)
})

test('require只有触发证据而无直接命中句时不会显示无问题', () => {
  assert.match(reportPresentation(report({ violationCount: 2, flaggedCount: 0 })).headline, /2 项/)
})

test('旧成功版本可读但必须提示最新重算失败', () => {
  const value = reportPresentation(report({ latestStatus: 'FAILED' }))
  assert.equal(value.published, true)
  assert.match(value.versionLabel, /最新一次判读失败/)
})

test('历史待生成报告、分析中、无数据都不能导出或假报成功', () => {
  for (const value of [null, report({ status: null, reportVersionId: null, taskStatus: 'WAITING_ROLE' }), report({ status: 'ANALYZING', latestStatus: 'ANALYZING' }), report({ reportVersionId: null })]) {
    const view = reportPresentation(value)
    assert.equal(view.published, false)
    assert.doesNotMatch(view.headline, /未检出|没有发现/)
  }
  assert.match(reportPresentation(report({ status: null, reportVersionId: null, taskStatus: 'WAITING_ROLE' })).lead, /正在根据转写结果生成/)
})

test('真正无命中的已发布报告仍明确限定为本轮规则', () => {
  const value = reportPresentation(report())
  assert.equal(value.headline, '本轮规则未检出问题')
  assert.match(value.lead, /不代表/)
})

test('识别失败不引导用户把空文本当正式报告', () => {
  const value = reportPresentation(report({ status: null, reportVersionId: null, taskStatus: 'INVALID', latestStatus: null }))
  assert.equal(value.headline, '本次识别未完成')
  assert.equal(value.published, false)
})

test('报告页面脚本与模板可编译且实际绑定发布守卫/通话级证据', () => {
  const filename = path.join(__dirname, '../src/pages/report/detail.vue')
  const vue = fs.readFileSync(filename, 'utf8')
  const parsed = parse(vue, { filename })
  assert.equal(parsed.errors.length, 0)
  const script = compileScript(parsed.descriptor, { id: 'report-review' })
  const template = compileTemplate({
    source: parsed.descriptor.template.content,
    filename,
    id: 'report-review',
    compilerOptions: { bindingMetadata: script.bindings },
  })
  assert.equal(template.errors.length, 0)
  assert.ok(script.bindings.presentation)
  assert.ok(script.bindings.callLevelViolations)
  assert.match(vue, /!presentation\.value\.published/)
  assert.match(vue, /v-for="item in callLevelViolations"/)
  assert.match(vue, /\{\{ displayViolationNote\(item\.note\) \}\}/)
  // 问题 / 对话两个 Tab：绑定与两个页签的渲染都必须在
  assert.ok(script.bindings.activeTab)
  assert.ok(script.bindings.issueCount)
  assert.ok(script.bindings.fullSentences)
  assert.match(vue, /@click="activeTab = 'issues'"/)
  assert.match(vue, /@click="activeTab = 'dialogue'"/)
  assert.match(vue, /v-for="sentence in fullSentences"/)
  assert.ok(script.bindings.matches)
  assert.match(vue, /v-for="item in matches"/)
  assert.match(vue, /<text class="tab-t">\s*问题\s*<\/text>/)
  assert.match(vue, /<text class="tab-t">\s*对话\s*<\/text>/)
  const actions = fs.readFileSync(path.join(__dirname, '../src/components/ReportExportActions.vue'), 'utf8')
  assert.match(vue, /<ReportExportActions[\s\S]*?@save="save"/)
  assert.match(actions, /:disabled="exporting \|\| waitingReport"/)
  assert.match(actions, /:disabled="exporting \|\| waitingReport \|\| !originalsAvailable"/)
  for (const child of ['ReportOverview', 'ReportExportActions']) {
    const pathToChild = path.join(__dirname, `../src/components/${child}.vue`)
    const { descriptor, errors } = parse(fs.readFileSync(pathToChild, 'utf8'), { filename: pathToChild })
    assert.equal(errors.length, 0)
    const childScript = compileScript(descriptor, { id: child })
    const compiled = compileTemplate({
      source: descriptor.template.content,
      filename: pathToChild,
      id: child,
      compilerOptions: { bindingMetadata: childScript.bindings },
    })
    assert.equal(compiled.errors.length, 0)
  }
})
