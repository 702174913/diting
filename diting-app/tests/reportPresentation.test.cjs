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
  assert.match(vue, /v-for="sentence in flaggedSentences"/)
  assert.match(vue, /<text class="tab-t">\s*问题\s*<\/text>/)
  assert.match(vue, /<text class="tab-t">\s*对话\s*<\/text>/)
  const actions = fs.readFileSync(path.join(__dirname, '../src/components/ReportExportActions.vue'), 'utf8')
  assert.match(vue, /<ReportExportActions[\s\S]*?@save="save"/)
  assert.match(actions, /:disabled="exporting \|\| waitingReport"/)
  assert.match(actions, /:disabled="exporting \|\| waitingReport \|\| !originalsAvailable"/)
  for (const child of ['ReportOverview', 'ReportExportActions', 'ClauseList', 'SentenceItem']) {
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

test('命中项下的适用条款：默认只列条款号，点开才看条文原文', () => {
  const types = fs.readFileSync(path.join(__dirname, '../src/types/api.ts'), 'utf8')
  // 服务端在 app 详情里一直在返（admin 详情与 app 详情同一份组装），前端类型此前漏了
  assert.match(types, /export interface Violation \{[\s\S]*?legalClauses: ReportLegalClause\[\]/)

  const clauseList = fs.readFileSync(path.join(__dirname, '../src/components/ClauseList.vue'), 'utf8')
  // 折叠默认收起：状态是空数组，条文原文只在展开后渲染
  assert.match(clauseList, /const openIds = ref<number\[\]>\(\[\]\)/)
  assert.match(clauseList, /@click="toggle\(clause\.id\)"/)
  assert.match(clauseList, /v-if="openIds\.includes\(clause\.id\)"[\s\S]*?\{\{ clause\.content \}\}/)
  // 条款号口径：法规名缺书名号时补上（与后台详情、PDF 导出件一致）
  assert.match(clauseList, /law\.startsWith\('《'\) \? `《\$\{law\}》` : law/)

  const sentenceItem = fs.readFileSync(path.join(__dirname, '../src/components/SentenceItem.vue'), 'utf8')
  assert.match(sentenceItem, /clauses\?: ReportLegalClause\[\]/)
  assert.match(sentenceItem, /<ClauseList :clauses="clauses" \/>/)

  const filename = path.join(__dirname, '../src/pages/report/detail.vue')
  const vue = fs.readFileSync(filename, 'utf8')
  // 句子级命中：按命中句聚合条款（同一句命中多条规则时按 id 去重）
  assert.match(vue, /const clausesBySentenceId = computed\(\(\) => \{/)
  assert.match(vue, /if \(!bucket\.some\(item => item\.id === clause\.id\)\) \{/)
  assert.match(vue, /:clauses="clausesBySentenceId\.get\(sentence\.id\) \?\? \[\]"/)
  // 通话级命中（sentenceId 为空）的条款跟着卡片走
  assert.match(vue, /function clausesOf\(violation: Violation\)/)
  assert.match(vue, /<ClauseList :clauses="clausesOf\(item\)" \/>/)
  // 口径两个页签共用同一句：条款按规则当前绑定，不是发布时快照
  assert.match(vue, /const CLAUSE_DISCLAIMER = '适用条款为规则当前绑定，条文原文以现行有效版本为准；是否构成违规由受理方认定。'/)
  assert.equal((vue.match(/v-if="hasAnyClause"/g) || []).length, 2)
  assert.equal((vue.match(/\{\{ CLAUSE_DISCLAIMER \}\}/g) || []).length, 2)
  // 条款既进问题页也进「对话」页：对话页是完整原文，命中句同样要能看到适用条款
  assert.equal((vue.match(/:clauses=/g) || []).length, 3)
  assert.match(vue, /v-for="sentence in fullSentences"[\s\S]{0,300}?:clauses="clausesBySentenceId\.get\(sentence\.id\) \?\? \[\]"/)

  const parsed = parse(vue, { filename })
  assert.equal(parsed.errors.length, 0)
  const script = compileScript(parsed.descriptor, { id: 'report-clauses' })
  const template = compileTemplate({
    source: parsed.descriptor.template.content,
    filename,
    id: 'report-clauses',
    compilerOptions: { bindingMetadata: script.bindings },
  })
  assert.equal(template.errors.length, 0)
  assert.ok(script.bindings.clausesBySentenceId)
  assert.ok(script.bindings.hasAnyClause)
})
