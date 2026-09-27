const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')
const { JSDOM } = require('jsdom')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const root = path.resolve(__dirname, '..')

test('协议编辑器运行时白名单拒绝畸形标签、事件、脚本链接并保留表格', async () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>')
  globalThis.window = dom.window
  const filename = path.join(root, 'src/utils/sanitizeAgreement.ts')
  const compiled = new Module(filename, module)
  compiled.filename = filename
  compiled.paths = Module._nodeModulePaths(path.dirname(filename))
  const js = ts.transpileModule(readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText
  compiled._compile(js, filename)
  const { sanitizeAgreementHtml } = compiled.exports
  const clean = sanitizeAgreementHtml('<svg/onload=alert(1)><img src=x onerror=evil()>'
    + '<a href=javascript:evil()>链接</a><a href="https://example.com">正常</a>'
    + '<p onclick=evil() style="background:url(javascript:evil())">正文</p>'
    + '<table><tr><td>条款</td></tr></table>')
  assert.doesNotMatch(clean, /<svg|<img|onload|onerror|onclick|javascript:|style=/i)
  assert.match(clean, /<p>正文<\/p>/)
  assert.match(clean, /<table>/)
  assert.match(clean, /href="https:\/\/example.com"/)
  dom.window.close()
  delete globalThis.window
})

test('协议编辑器在 innerHTML、粘贴与输入前清洗，模板可编译', () => {
  const source = readFileSync(path.join(root, 'src/components/RichTextEditor.vue'), 'utf8')
  assert.match(source, /sanitizeAgreementHtml\(props\.modelValue/)
  assert.match(source, /sanitizeAgreementHtml\(editor\.value\.innerHTML\)/)
  assert.match(source, /@paste="onPaste"/)
  assert.match(source, /@drop\.prevent/)
  const parsed = parse(source)
  assert.equal(parsed.errors.length, 0)
  compileScript(parsed.descriptor, { id: 'agreement-editor' })
  const template = compileTemplate({ source: parsed.descriptor.template.content, filename: 'RichTextEditor.vue', id: 'agreement-editor' })
  assert.equal(template.errors.length, 0)
})
