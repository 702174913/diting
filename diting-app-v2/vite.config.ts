import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// https://vitejs.dev/config/
// 新后端对接版：H5 直接部署在根路径（不再挂 /h5/），与 manifest 的 h5.router.base 保持一致。
export default defineConfig(() => ({
  base: '/',
  plugins: [uni()],
}))
