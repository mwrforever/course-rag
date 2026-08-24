import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * B 端 Vitest 配置：jsdom 环境 + Vue 插件 + @ 别名 + v8 覆盖率
 *
 * 覆盖率：圈定 src 已实现文件（Task 15 骨架：App/路由/认证 store/视图/tokens 基础件），
 * 阈值行覆盖 80%；后续任务交付各自测试后逐步收紧。
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
      },
    },
  },
})
