import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// https://vitejs.dev/config/
export default defineConfig(({ command }) => ({
  // H5 部署在 /h5/ 子路径下（与后台的 /admin/ 区分）。
  // 只在构建时生效：本地 dev:h5 仍在根路径，不影响开发；小程序端不受 base 影响。
  // 与 manifest.json 的 h5.router.base 保持一致。
  base: command === 'build' ? '/h5/' : '/',
  plugins: [uni()],
}))
