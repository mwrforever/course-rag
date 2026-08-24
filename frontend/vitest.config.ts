import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * B 端 Vitest 配置：jsdom 环境 + Vue 插件 + @ 别名 + v8 覆盖率
 *
 * 覆盖率：include 圈定 src 全部已实现文件（Task 15 骨架 + Task 16 核心交付），
 * 全局阈值行/函数/语句 80%；Task 16 四个核心文件（api client / auth store /
 * 路由守卫 / 布局角色过滤）按交付铁律收紧为 lines 100%，
 * 后续任务各自文件据此模式逐步收紧。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      include: ['src/**/*.{ts,vue}'],
      exclude: [
        // 应用入口与类型声明不参与覆盖率
        'src/main.ts',
        'src/env.d.ts',
        // 测试自身不计入
        'src/**/__tests__/**',
      ],
      thresholds: {
        lines: 80,
        functions: 80,
        statements: 80,
        // Task 16 核心文件：行覆盖 100%（任务铁律）
        'src/lib/api.ts': { lines: 100 },
        'src/stores/auth.ts': { lines: 100 },
        'src/router/index.ts': { lines: 100 },
        'src/layouts/AdminLayout.vue': { lines: 100 },
      },
    },
  },
})
