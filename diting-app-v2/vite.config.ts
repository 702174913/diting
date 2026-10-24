import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// https://vitejs.dev/config/
// H5 部署在 /h5/ 子路径，与 manifest 的 h5.router.base 保持一致。
export default defineConfig(() => ({
  base: '/h5/',
  plugins: [uni()],
}))
