const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const root = path.resolve(__dirname, '..')

/** 复用 request.ts 里真实的 buildQuery：URL 断言要验的是真编码行为，不是字面量 */
function loadBuildQuery() {
  const source = readFileSync(path.join(root, 'src/api/request.ts'), 'utf8')
  const part = source.match(/export function buildQuery\([\s\S]*?(?=\nexport interface RequestOptions)/)?.[0]
  assert.ok(part, 'buildQuery 未找到')
  const code = ts.transpileModule(part, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  vm.runInNewContext(code, { exports, URLSearchParams }, { filename: 'request.ts' })
  return exports.buildQuery
}

/**
 * 把 admin.ts 装进一个假的 ./request 上跑：记录每次调用的 url/method/data。
 * 断的是「前端真的这么调」而不是「源码里有这行字」。
 * 用 runInThisContext 包一层而不是 new Function：既避开 no-new-func，
 * 也让被记录的调用对象留在当前 realm，deepStrictEqual 才能正常比较。
 */
function loadAdminApi() {
  const source = readFileSync(path.join(root, 'src/api/admin.ts'), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const calls = []
  const exports = {}
  const fakeRequest = (options) => {
    calls.push(options)
    return Promise.resolve({ items: [], total: 0, page: 1, pageSize: 20 })
  }
  const fakeRequire = (id) => {
    if (id === './request') {
      return { buildQuery: loadBuildQuery(), request: fakeRequest }
    }
    throw new Error(`未预期的依赖：${id}`)
  }
  const factory = vm.runInThisContext(
    `(function (exports, require, module) {${code}\n})`,
    { filename: 'admin.ts' },
  )
  factory(exports, fakeRequire, { exports })
  return { api: exports, calls }
}

test('绑定条款：PUT 替换式提交 clauseIds，空数组即解绑全部', async () => {
  const { api, calls } = loadAdminApi()

  await api.bindRuleLegalClauses('tok', 7, [3, 1])
  assert.equal(calls[0].url, '/api/admin/rules/7/legal-clauses')
  assert.equal(calls[0].method, 'PUT')
  assert.equal(calls[0].token, 'tok')
  assert.deepEqual(calls[0].data, { clauseIds: [3, 1] })

  // 空数组必须显式发出（服务端 @NotNull 要求 clauseIds 存在），不能省略成 undefined
  await api.bindRuleLegalClauses('tok', 7, [])
  assert.deepEqual(calls[1].data, { clauseIds: [] })
})

test('条款库查询串保留 enabled=false，关键词原样编码', async () => {
  const { api, calls } = loadAdminApi()

  await api.fetchLegalClauses('tok', { page: 2, pageSize: 20, keyword: '', enabled: false })
  assert.equal(calls[0].url, '/api/admin/legal-clauses?page=2&pageSize=20&enabled=false')

  await api.fetchLegalClauses('tok', { keyword: '个人信息 保护法', enabled: true })
  assert.equal(
    calls[1].url,
    '/api/admin/legal-clauses?keyword=%E4%B8%AA%E4%BA%BA%E4%BF%A1%E6%81%AF+%E4%BF%9D%E6%8A%A4%E6%B3%95&enabled=true',
  )
})

test('条款增删改落的都是 /api/admin/legal-clauses，停用走 DELETE 而不是删行', async () => {
  const { api, calls } = loadAdminApi()
  const payload = { lawName: '个人信息保护法', articleNo: '第十三条', content: '原文', remark: null, enabled: true }

  await api.createLegalClause('tok', payload)
  assert.equal(calls[0].url, '/api/admin/legal-clauses')
  assert.equal(calls[0].method, 'POST')
  assert.deepEqual(calls[0].data, payload)

  await api.updateLegalClause('tok', 5, payload)
  assert.equal(calls[1].url, '/api/admin/legal-clauses/5')
  assert.equal(calls[1].method, 'PUT')

  await api.disableLegalClause('tok', 5)
  assert.equal(calls[2].url, '/api/admin/legal-clauses/5')
  assert.equal(calls[2].method, 'DELETE')
})

test('规则响应声明 legalClauses，绑定弹窗只把「启用中的绑定」放进初始选中', () => {
  const adminSource = readFileSync(path.join(root, 'src/api/admin.ts'), 'utf8')
  assert.match(adminSource, /legalClauses: LegalClauseItem\[\]/)

  const dialog = readFileSync(path.join(root, 'src/views/rules/rule-clause-bind-dialog.vue'), 'utf8')
  // 初始选中只能来自 pinnedEnabled：停用条款一旦被提交，服务端 422 会整单拒绝
  assert.match(dialog, /initialSelection\.value = pinnedEnabled\.value\.map\(clause => clause\.id\)/)
  assert.match(dialog, /selected\.value = \[\.\.\.initialSelection\.value\]/)
  assert.doesNotMatch(dialog, /initialSelection\.value = pinned\.value/)
  // 提交的是选中的 id 数组；已停用的绑定必须在界面上说明会被解除
  assert.match(dialog, /bindRuleLegalClauses\(token, rule\.ruleId, selected\.value\)/)
  assert.match(dialog, /pinnedDisabled/)
  assert.match(dialog, /已被解除|会被解除/)
})

test('条款库在路由、侧栏菜单、投资人锁定三处一致存在', () => {
  const router = readFileSync(path.join(root, 'src/router/index.ts'), 'utf8')
  assert.match(router, /path: 'legal-clauses'/)
  assert.match(router, /import\('@\/views\/legal-clauses\/index\.vue'\)/)

  const layout = readFileSync(path.join(root, 'src/layouts/AdminLayout.vue'), 'utf8')
  assert.match(layout, /\{ key: 'legal-clauses', title: '条款库'/)

  // 读接口是 {ADMIN, OPERATOR}：投资人视图必须在本地就锁上，别等 403 弹错
  const viewSource = readFileSync(path.join(root, 'src/stores/view.ts'), 'utf8')
  assert.match(viewSource, /const IR_LOCKED = \[[^\]]*'legal-clauses'/)
})

test('录音详情命中证据里展示适用条款：默认只列条款号，点开才看条文原文', () => {
  const api = readFileSync(path.join(root, 'src/api/admin.ts'), 'utf8')
  // 服务端一直在返（admin 详情与 app 详情同一份组装），前端类型此前漏了
  assert.match(api, /export interface AdminViolation \{[\s\S]*?legalClauses: LegalClauseItem\[\]/)

  const view = readFileSync(path.join(root, 'src/views/recordings/index.vue'), 'utf8')
  // 折叠默认收起：状态是空数组，条文原文只在展开后渲染
  assert.match(view, /const openClauseIds = ref<number\[\]>\(\[\]\)/)
  assert.match(view, /function toggleClause\(clauseId: number\)/)
  assert.match(view, /@click="toggleClause\(clause\.id\)"/)
  assert.match(view, /v-if="openClauseIds\.includes\(clause\.id\)"[\s\S]*?\{\{ clause\.content \}\}/)
  // 条款号口径与 PDF 导出件一致（法规名缺书名号时补上）
  assert.match(view, /function clauseTitle\(clause: LegalClauseItem\)/)
  assert.match(view, /law\.startsWith\('《'\) \? `《\$\{law\}》` : law/)
  // 旧服务端不返该字段时不能把详情页弄崩
  assert.match(view, /function clausesOf\(violation: AdminViolation\) \{\n\s+return violation\.legalClauses \?\? \[\]/)
  // 未绑定要说出来，并指向规则库绑定入口
  assert.match(view, /：未绑定（规则库「法律依据」列可绑定）/)
  // 实时读取口径必须写在命中证据页签里：改绑定会影响历史报告的重读结果
  assert.match(view, /TabsContent value="evidence"[\s\S]{0,400}适用条款按规则当前绑定实时读取/)
})

test('新增/改动的页面 SFC 能编译（模板与 <script setup> 无错）', () => {
  const views = [
    'src/views/legal-clauses/index.vue',
    'src/views/legal-clauses/legal-clause-editor-dialog.vue',
    'src/views/rules/index.vue',
    'src/views/rules/rule-clause-bind-dialog.vue',
    'src/views/recordings/index.vue',
    'src/layouts/AdminLayout.vue',
  ]
  for (const view of views) {
    const { descriptor, errors } = parse(readFileSync(path.join(root, view), 'utf8'))
    assert.equal(errors.length, 0, view)
    const script = compileScript(descriptor, { id: view })
    const compiled = compileTemplate({
      source: descriptor.template.content,
      filename: view,
      id: view,
      compilerOptions: { bindingMetadata: script.bindings },
    })
    assert.equal(compiled.errors.length, 0, view)
  }
})
