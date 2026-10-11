const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { spawnSync } = require('node:child_process')
const path = require('node:path')
const process = require('node:process')

const root = path.resolve(__dirname, '..')
const script = path.join(root, 'scripts/build-production.cjs')
const manifestPath = path.join(root, 'src/manifest.json')

function validate(platform, env) {
  return spawnSync(process.execPath, [script, platform, '--validate-only'], {
    cwd: root,
    encoding: 'utf8',
    env: { ...process.env, VITE_BASE_URL: '', DITING_WECHAT_APPID: '', DITING_UNI_APP_ID: '', ...env },
  })
}

test('生产构建拒绝占位接口域名、空微信 appid 和空 Uni appid', () => {
  assert.notEqual(validate('h5', { VITE_BASE_URL: 'https://api.example.com' }).status, 0)
  assert.notEqual(validate('mp-weixin', { VITE_BASE_URL: 'https://api.real.invalid' }).status, 0)
  assert.notEqual(validate('mp-weixin', {
    VITE_BASE_URL: 'https://api.real.invalid',
    DITING_WECHAT_APPID: 'wx0123456789abcdef',
  }).status, 0)
})

test('完整配置格式校验不会写入真实或示例身份到源码文件', () => {
  const original = readFileSync(manifestPath, 'utf8')
  const result = validate('mp-weixin', {
    VITE_BASE_URL: 'https://api.real.invalid',
    DITING_WECHAT_APPID: 'wx0123456789abcdef',
    DITING_UNI_APP_ID: '__UNI__ABCDEF1',
  })
  assert.equal(result.status, 0, result.stderr)
  assert.equal(readFileSync(manifestPath, 'utf8'), original)
  assert.match(result.stdout, /未编译或验收小程序/)
})
