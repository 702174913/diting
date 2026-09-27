import { URL, fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

/**
 * 样式层已从 UnoCSS 迁到 Tailwind v4（CSS-first，令牌见 src/style.css）。
 * /api 代理指向本地 diting-server（默认 8080），迁移期间靠真实数据联调。
 */
export default defineConfig({
  // 后台部署在 /admin/ 子路径下（与 H5 的 /h5/ 区分）。
  // base 决定构建产物里 js/css 的引用前缀（默认是 /，放子目录会全部 404），
  // 必须与 nginx 里 location /admin/ 的路径一致。
  base: '/admin/',
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
