import { fileURLToPath, URL } from 'node:url'

import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

/**
 * B 端（frontend）Vite 构建配置
 *
 * - dev server 固定 5001（与后端 CORS 白名单匹配，设计 §0.3/§3.5；原 5173 因端口冲突迁出）
 * - /api 代理到 Spring Boot 8080（规避 CORS 与 cookie domain 问题，设计 §3.3）
 * - Tailwind v4 走 @tailwindcss/vite 插件（CSS-first，无 config 文件）
 * - 测试配置独立放 vitest.config.ts（Vitest 优先读取该文件）
 */
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5001,
    proxy: {
      // 后端接口统一代理：本地开发免 CORS、cookie domain 与端口白名单问题
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
