import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    // 构建产物直接输出到后端静态资源目录：mvn package 后随 fat jar 一起发布，
    // 浏览器与 API 同源（/ 与 /api），生产无需 nginx 反向代理、无跨域。
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    host: true,
    proxy: {
      // 开发态后端在 8080；/api 全走它（生产同源，无此 proxy）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})