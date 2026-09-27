const { readFileSync, writeFileSync } = require('node:fs')
const { spawnSync } = require('node:child_process')
const path = require('node:path')
const process = require('node:process')
const { loadEnv } = require('vite')

const root = path.resolve(__dirname, '..')
const platform = process.argv[2]
if (!['h5', 'mp-weixin'].includes(platform)) {
  throw new Error('仅支持显式 h5 / mp-weixin 生产构建')
}
const env = loadEnv('production', root, 'VITE_')
const base = (process.env.VITE_BASE_URL || env.VITE_BASE_URL || '').trim()
let url
try {
  url = new URL(base)
}
catch {
  throw new Error('生产构建必须设置完整的 VITE_BASE_URL（https://真实业务域名）')
}
if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash
  || !url.hostname || /(?:^|\.)example\.(?:com|org|net)$|localhost|127\.0\.0\.1/i.test(url.hostname)) {
  throw new Error('生产接口地址必须是 HTTPS 真实业务域名，不能使用示例、本地地址或带凭据的 URL')
}
if (platform === 'mp-weixin' && url.pathname !== '/') {
  throw new Error('小程序接口基础地址仅允许源站，不要带路径')
}
const manifestPath = path.join(root, 'src/manifest.json')
const original = readFileSync(manifestPath, 'utf8')
let manifest
try {
  manifest = JSON.parse(original)
}
catch {
  throw new Error('生产构建缺少有效的 src/manifest.json')
}
const wechatAppId = (process.env.DITING_WECHAT_APPID || '').trim()
const uniAppId = (process.env.DITING_UNI_APP_ID || '').trim()
if (platform === 'mp-weixin' && !/^wx[a-f0-9]{16}$/i.test(wechatAppId)) {
  throw new Error('微信小程序生产构建必须设置真实的 DITING_WECHAT_APPID（wx + 16位十六进制）')
}
if (platform === 'mp-weixin' && !/^__UNI__[a-f0-9]{7,}$/i.test(uniAppId)) {
  throw new Error('微信小程序生产构建必须设置真实的 DITING_UNI_APP_ID')
}
if (process.argv.includes('--validate-only')) {
  process.stdout.write('生产构建配置格式校验通过（未联系域名，也未编译或验收小程序）\n')
  process.exit(0)
}
const envForBuild = { ...process.env, VITE_BASE_URL: base }
if (platform === 'mp-weixin') {
  manifest.appid = uniAppId
  manifest['mp-weixin'].appid = wechatAppId
  // 构建工具只从 manifest.json 读取微信 appid；构建结束即恢复源文件。
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 })
}
try {
  const uni = path.join(root, 'node_modules/.bin/uni')
  const args = platform === 'mp-weixin' ? ['build', '-p', 'mp-weixin'] : ['build']
  const result = spawnSync(uni, args, { cwd: root, env: envForBuild, stdio: 'inherit' })
  if (result.error)
    throw result.error
  process.exitCode = result.status ?? 1
}
finally {
  if (platform === 'mp-weixin')
    writeFileSync(manifestPath, original)
}
