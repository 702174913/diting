const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function loadUtil(file) {
  const source = readFileSync(path.join(__dirname, '../src/utils', file), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  } }).outputText
  const exports = {}
  vm.runInNewContext(code, { exports }, { filename: file })
  return exports
}

const DETAIL_PAGE = path.join(__dirname, '../src/pages/report/detail.vue')

/** 去掉注释再断言：注释里提到 `uni.downloadFile` / `getResponseHeader` 是在解释“为什么不这么写” */
function stripComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

test('导出文件名口径与服务端 artifactFileName 一致：报告编号-v版本.扩展名', () => {
  const { exportArtifactName } = loadUtil('exportArtifact.ts')
  assert.equal(exportArtifactName('DT-2026-001046', 2, 'zip'), 'DT-2026-001046-v2.zip')
  assert.equal(exportArtifactName('DT-2026-001046', 2, 'pdf'), 'DT-2026-001046-v2.pdf')
  // 文件名不得含用户敏感信息（开发文档 §9）：这里只有报告编号与版本
  assert.doesNotMatch(exportArtifactName('DT-2026-001046', 1, 'pdf'), /姓名|手机|身份证/)
})

test('H5 导出不走 uni.downloadFile：它读 content-disposition 会被 CORS 拦、文件名也会丢', () => {
  // 条件编译标记本身是注释，所以要从原文里切块，再在块内去注释断言代码
  const raw = readFileSync(DETAIL_PAGE, 'utf8')
  const code = stripComments(raw)
  const h5Block = stripComments(raw.match(/\/\/ #ifdef H5[\s\S]*?\/\/ #endif/)?.[0] ?? '')

  assert.ok(h5Block, 'detail.vue 应有 H5 条件编译块')
  assert.match(h5Block, /saveInBrowser\(/)
  assert.doesNotMatch(h5Block, /uni\.downloadFile\s*\(/)

  // 全文件不得读响应头：OSS 未通过 CORS 暴露 content-disposition，读它只会拿到 null 加一条浏览器报错
  assert.doesNotMatch(code, /getResponseHeader/)

  // 不变量：每一个 uni.downloadFile 调用都必须落在非 H5 分支里（H5 编译产物会把它整块删掉）
  const nonH5 = [...raw.matchAll(/\/\/ #ifndef H5[\s\S]*?\/\/ #endif/g)]
    .map(m => stripComments(m[0])).join('\n')
  const calls = (code.match(/uni\.downloadFile\s*\(/g) ?? []).length
  const callsInNonH5 = (nonH5.match(/uni\.downloadFile\s*\(/g) ?? []).length
  assert.ok(calls > 0, '小程序端仍应用 uni.downloadFile 取临时文件')
  assert.equal(callsInNonH5, calls, '有 uni.downloadFile 调用落在非 H5 分支之外')
})

test('保存/下载只用 loading 反馈：生成与下载各一次 showLoading，页面不展示进度字段', () => {
  const raw = readFileSync(DETAIL_PAGE, 'utf8')
  const code = stripComments(raw)

  assert.match(code, /uni\.showLoading\(\{ title: '正在生成…' \}\)/)
  assert.match(code, /uni\.showLoading\(\{ title: '正在下载…', mask: true \}\)/)

  // 每个终态都要关：下载回调 + 提前返回 + catch；残留的遮罩会把页面锁死
  const hides = (code.match(/uni\.hideLoading\(\)/g) ?? []).length
  const downloads = (code.match(/uni\.downloadFile\s*\(/g) ?? []).length
  assert.ok(hides >= downloads + 2, `hideLoading 数量应覆盖下载回调与提前返回，实得 ${hides}`)

  // 页面不再额外占一行进度文字（用户要求：直接展示 loading）
  assert.doesNotMatch(raw, /exportProgress/)
  assert.doesNotMatch(raw, /v-if="exportProgress"/)
})

test('H5 另存：fetch 字节流 → Blob 同源下载；兑底才把地址交给浏览器', () => {
  const code = stripComments(readFileSync(DETAIL_PAGE, 'utf8'))
  const primary = code.match(/async function saveInBrowser[\s\S]*?\n\}/)?.[0] ?? ''
  const fallback = code.match(/function saveViaUrl[\s\S]*?\n\}/)?.[0] ?? ''

  assert.match(primary, /await fetch\(url\)/)
  assert.match(primary, /URL\.createObjectURL\(await res\.blob\(\)\)/)
  assert.match(primary, /URL\.revokeObjectURL/)
  assert.match(primary, /saveViaUrl\(objectUrl, fileName\)/)

  assert.match(fallback, /createElement\('a'\)/)
  assert.match(fallback, /anchor\.download = fileName/)
  assert.match(fallback, /anchor\.rel = 'noopener'/)
  assert.match(fallback, /anchor\.remove\(\)/)
})
