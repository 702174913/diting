const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const root = path.resolve(__dirname, '..')

test('统一查询串正确编码名称、中文、0/false，忽略缺失和空值', () => {
  const source = readFileSync(path.join(root, 'src/api/request.ts'), 'utf8')
  const part = source.match(/export function buildQuery\([\s\S]*?(?=\nexport interface RequestOptions)/)?.[0]
  assert.ok(part)
  const code = ts.transpileModule(part, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  vm.runInNewContext(code, { exports, URLSearchParams }, { filename: 'request.ts' })
  const query = exports.buildQuery({ 'name[]': '张 三', 'page': 0, 'enabled': false, 'empty': '', 'missing': undefined, 'nil': null })
  assert.equal(query, 'name%5B%5D=%E5%BC%A0+%E4%B8%89&page=0&enabled=false')
  for (const api of ['admin.ts', 'stats.ts']) {
    assert.doesNotMatch(readFileSync(path.join(root, 'src/api', api), 'utf8'), /Object\.entries\(params\)/)
  }
})

test('四张后台表格只由分页组件渲染页码，不重复显示', () => {
  for (const view of ['accounts', 'audit', 'rules', 'recordings']) {
    const filename = path.join(root, `src/views/${view}/index.vue`)
    const { descriptor, errors } = parse(readFileSync(filename, 'utf8'))
    assert.equal(errors.length, 0, view)
    assert.doesNotMatch(descriptor.template.content, /<span[^>]*>第 \{\{ page \}\}/)
    assert.match(descriptor.template.content, /<DataTablePagination/)
    const script = compileScript(descriptor, { id: view })
    assert.equal(compileTemplate({ source: descriptor.template.content, filename, id: view, compilerOptions: { bindingMetadata: script.bindings } }).errors.length, 0)
  }
})
