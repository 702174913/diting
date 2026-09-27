/**
 * SFC 绑定检查器（只读校验，不改任何文件）。
 * 目的：抓「模板里用了脚本没定义的东西」这一类缺陷——正是 vue-tsc 之外、单文件编译检查
 * 容易漏掉的类别（compiler-sfc 只报语法错误，不报未定义标识符）。
 *
 * 做法：
 * 1) parse + compileScript + compileTemplate：语法与模板编译错误；
 * 2) 收集脚本里声明的名字（compileScript.bindings + 声明/导入/defineProps 解构的正则兜底）；
 * 3) 收集模板局部名（v-for 别名、v-slot）；
 * 4) 扫描模板绑定位置（{{ }}、v-*、:prop、@event）里的标识符，扣掉字符串/属性访问/已知全局，
 *    剩下的即「疑似未定义」。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { createRequire } from 'node:module'

const projectRoot = process.argv[2]
const roots = process.argv.slice(3)
if (!projectRoot || roots.length === 0) {
  console.error('用法: node sfc-binding-check.mjs <项目根> <待检查目录...>')
  process.exit(2)
}
const require = createRequire(resolve(projectRoot, 'package.json'))
const sfc = require('@vue/compiler-sfc')

const GLOBALS = new Set([
  'Math', 'Number', 'String', 'Boolean', 'Date', 'JSON', 'Object', 'Array', 'Set', 'Map',
  'console', 'undefined', 'null', 'true', 'false', 'NaN', 'Infinity', 'parseInt', 'parseFloat',
  'isNaN', 'encodeURIComponent', 'decodeURIComponent', 'RegExp', 'Error', 'Promise',
  '$event', 'uni', 'wx', 'window', 'document', 'navigator', 'localStorage', 'sessionStorage',
  'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'fetch', 'XMLHttpRequest',
  'in', 'of', 'typeof', 'instanceof', 'new', 'return', 'if', 'else', 'void', 'delete', 'await',
])

const DECL_RE = /\b(?:const|let|var|function|class)\s+([A-Za-z_$][\w$]*)/g
const FUNC_PARAM_RE = /\b(?:function\s*[A-Za-z_$\w]*\s*)\(([^)]*)\)|\(([^)]*)\)\s*=>|([A-Za-z_$][\w$]*)\s*=>/g

function declaredNames(scriptText, bindings) {
  const names = new Set(Object.keys(bindings ?? {}))
  for (const m of scriptText.matchAll(DECL_RE)) {
    names.add(m[1])
  }
  // import a, { b as c } from 'x'
  for (const m of scriptText.matchAll(/\bimport\s+([^'"]+?)\s+from/g)) {
    const clause = m[1]
    for (const part of clause.split(',')) {
      const cleaned = part.replace(/[{}]/g, ' ').trim()
      for (const piece of cleaned.split(/\s+as\s+|\s+/)) {
        const name = piece.trim()
        if (/^[A-Za-z_$][\w$]*$/.test(name)) {
          names.add(name)
        }
      }
    }
  }
  // const { a, b: c = 1 } = defineProps<...>() / withDefaults(...)
  for (const m of scriptText.matchAll(/\{([^{}]*)\}\s*=\s*(?:withDefaults\(|defineProps)/g)) {
    for (const piece of m[1].split(',')) {
      const name = piece.split(':')[0].split('=')[0].trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) {
        names.add(name)
      }
    }
  }
  // 函数参数名（模板里用到 v-for 的 index 等通常来自模板，这里是脚本侧兜底）
  for (const m of scriptText.matchAll(FUNC_PARAM_RE)) {
    const params = (m[1] ?? m[2] ?? m[3] ?? '')
    for (const piece of params.split(',')) {
      const name = piece.split(':')[0].split('=')[0].replace(/[?]/g, '').trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) {
        names.add(name)
      }
    }
  }
  return names
}

function templateLocals(templateText) {
  const names = new Set()
  for (const m of templateText.matchAll(/v-for\s*=\s*"\s*\(?\s*([A-Za-z_$][\w$]*)\s*(?:,\s*([A-Za-z_$][\w$]*))?/g)) {
    names.add(m[1])
    if (m[2]) {
      names.add(m[2])
    }
  }
  for (const m of templateText.matchAll(/(?:v-slot|#)[\w-]*\s*=\s*"\s*\{?([^"}]*)\}?\s*"/g)) {
    for (const piece of m[1].split(',')) {
      const name = piece.split(':').pop().trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) {
        names.add(name)
      }
    }
  }
  return names
}

function expressionsOf(templateText) {
  const list = []
  for (const m of templateText.matchAll(/\{\{([^}]*)\}\}/g)) {
    list.push({ expr: m[1], kind: 'mustache' })
  }
  for (const m of templateText.matchAll(/(?:^|\s)(v-[\w:-]+|:[\w-]+|@[\w-]+)="([^"]*)"/g)) {
    const attr = m[1]
    if (attr === 'v-for') {
      const inPart = m[2].split(/\s+in\s+/).slice(1).join(' in ')
      list.push({ expr: inPart, kind: attr })
      continue
    }
    if (attr.startsWith('v-slot') || attr.startsWith('#')) {
      continue
    }
    list.push({ expr: m[2], kind: attr })
  }
  return list
}

function stripStrings(expr) {
  return expr
    .replace(/'(?:[^'\\]|\\.)*'/g, ' ')
    .replace(/"(?:[^"\\]|\\.)*"/g, ' ')
    .replace(/`(?:[^`\\]|\\.)*`/g, ' ')
}

/** 模板表达式里的箭头函数参数（例如 images.filter(i => ...) 里的 i）也算局部名 */
function arrowParams(expr) {
  const names = new Set()
  for (const m of expr.matchAll(/(?:\(([^()]*)\)|([A-Za-z_$][\w$]*))\s*=>/g)) {
    const params = m[1] ?? m[2] ?? ''
    for (const piece of params.split(',')) {
      const name = piece.split(':')[0].split('=')[0].trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) {
        names.add(name)
      }
    }
  }
  return names
}

function suspicious(expr, known) {
  const cleaned = stripStrings(expr)
  const found = []
  for (const m of cleaned.matchAll(/[A-Za-z_$][\w$]*/g)) {
    const name = m[0]
    const start = m.index ?? 0
    const before = cleaned.slice(Math.max(0, start - 3), start)
    const after = cleaned.slice(start + name.length, start + name.length + 1)
    if (/[.?\]]$/.test(before)) {
      continue
    }
    if (/^\s*:/.test(after)) {
      continue
    }
    if (GLOBALS.has(name) || known.has(name)) {
      continue
    }
    found.push(name)
  }
  return found
}

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    const stat = statSync(full)
    if (stat.isDirectory()) {
      walk(full, out)
    }
    else if (entry.endsWith('.vue')) {
      out.push(full)
    }
  }
  return out
}

let files = 0
let problems = 0
const files_ = []
for (const root of roots) {
  files_.push(...walk(root))
}

for (const file of files_.sort()) {
  files++
  const source = readFileSync(file, 'utf8')
  const rel = relative(process.cwd(), file)
  const { descriptor, errors } = sfc.parse(source, { filename: file })
  for (const err of errors ?? []) {
    console.log(`[编译错误] ${rel}: ${err.message}`)
    problems++
  }
  let bindings = {}
  if (descriptor.scriptSetup) {
    try {
      const compiled = sfc.compileScript(descriptor, { id: 'check' })
      bindings = compiled.bindings ?? {}
    }
    catch (e) {
      console.log(`[script 编译失败] ${rel}: ${e.message}`)
      problems++
    }
  }
  if (descriptor.template) {
    const res = sfc.compileTemplate({
      source: descriptor.template.content,
      filename: file,
      id: 'check',
      compilerOptions: { bindingMetadata: bindings },
    })
    for (const err of res.errors ?? []) {
      const message = typeof err === 'string' ? err : err.message
      console.log(`[模板编译错误] ${rel}: ${message}`)
      problems++
    }
    const known = declaredNames(descriptor.scriptSetup?.content ?? descriptor.script?.content ?? '', bindings)
    for (const name of templateLocals(descriptor.template.content)) {
      known.add(name)
    }
    const seen = new Set()
    for (const { expr, kind } of expressionsOf(descriptor.template.content)) {
      const local = new Set(known)
      for (const name of arrowParams(expr)) {
        local.add(name)
      }
      for (const name of suspicious(expr, local)) {
        const key = `${name}|${kind}`
        if (seen.has(key)) {
          continue
        }
        seen.add(key)
        console.log(`[疑似未定义] ${rel}: 模板 ${kind} 里用到 "${name}"，脚本中未找到声明`)
        problems++
      }
    }
  }
}

console.log(`\n检查文件数: ${files}；问题数: ${problems}`)
process.exit(problems === 0 ? 0 : 1)
