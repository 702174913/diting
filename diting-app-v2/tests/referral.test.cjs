const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const SRC = path.join(__dirname, '../src')

function loadReferral(storage = {}) {
  const filename = path.join(SRC, 'utils/referral.ts')
  const exports = {}
  const uni = {
    getStorageSync: key => storage[key] ?? '',
    setStorageSync: (key, value) => {
      storage[key] = value
    },
    removeStorageSync: (key) => {
      delete storage[key]
    },
  }
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText
  vm.runInNewContext(code, { exports, uni }, { filename })
  return { referral: exports, storage }
}

test('分享进入接住合法 user_id，脏参数不覆盖已有归因', () => {
  const { referral, storage } = loadReferral()
  referral.captureParentUserId({ user_id: '42' })
  assert.equal(storage.diting_parent_user_id, 42)
  assert.equal(referral.getParentUserId(), 42)

  referral.captureParentUserId({ user_id: 'abc' })
  referral.captureParentUserId({ user_id: '0' })
  referral.captureParentUserId({})
  assert.equal(referral.getParentUserId(), 42)

  referral.clearParentUserId()
  assert.equal(referral.getParentUserId(), null)
  assert.equal(storage.diting_parent_user_id, undefined)
})

test('分享路径带上分享者 user_id，没有 user_id 时原样返回', () => {
  const { referral } = loadReferral()
  assert.equal(referral.sharePath('/pages/home/index', 42), '/pages/home/index?user_id=42')
  assert.equal(referral.sharePath('/pages/home/index?id=1', 42), '/pages/home/index?id=1&user_id=42')
  assert.equal(referral.sharePath('/pages/home/index', null), '/pages/home/index')
})

test('分享页在页面源码里直接注册 onShareAppMessage（小程序编译期按源码识别）', () => {
  const pages = [
    'pages/home/index.vue',
    'pages/consent/index.vue',
    'pages/upload/index.vue',
    'pages/history/index.vue',
    'pages/about/index.vue',
    'pages/report/detail.vue',
    'pages/welcome/index.vue',
    'pages/login/index.vue',
    'pages/agreement/index.vue',
    'pages/privacy/index.vue',
  ]
  for (const page of pages) {
    const source = fs.readFileSync(path.join(SRC, page), 'utf8')
    assert.match(source, /onShareAppMessage\(useShareContent\(/, `${page} 未直接注册 onShareAppMessage`)
  }
})
