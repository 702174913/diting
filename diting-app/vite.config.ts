import { defineConfig } from 'vite'
import type { Plugin } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// https://vitejs.dev/config/
export default defineConfig(() => ({
  // H5 部署在 /h5/ 子路径下（与后台的 /admin/ 区分）。
  //
  // dev 也必须用 /h5/，不能是 '/'：uni 的 manifest 插件里 runtime 的 __uniConfig.router.base
  // 是 manifest.json 的 h5.router.base 说了算（合并顺序里它优先级最高，dev 的 base 覆盖不掉）。
  // 两边不一致时，运行时会把每个绝对资源路径经 getRealPath() 补成 /h5/xxx，
  // 而 dev server 起在 / 上，/h5/xxx 只落到 SPA 回退（返回 index.html），
  // <image> 图标和 tabbar 图标全部加载失败（页面看着就是「图标没了」）。
  // 保持一致后本地 http://localhost:5174/h5/ 与线上同构；小程序端不受 base 影响。
  base: '/h5/',
  plugins: [uni(), devRootRedirect()],
}))

/** dev 期把根路径重定向到 /h5/，直接开 http://localhost:5174/ 也能进应用。 */
function devRootRedirect(): Plugin {
  return {
    name: 'diting-dev-root-redirect',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        if (req.url === '/' || req.url === '') {
          req.url = '/h5/'
        }
        next()
      })
    },
  }
}
