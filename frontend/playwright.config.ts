import { defineConfig } from '@playwright/test'
import { devices } from '@playwright/test'

/**
 * B 端 E2E 配置（TASK.md §2：frontend 独立 E2E 工程）
 *
 * 全 route-mock 模式（与 student-frontend 一致）：不依赖真实后端，
 * webServer 起 vite dev（5173），用例自行拦截 /api/** 并 fulfill 预录响应。
 * 串行执行保证时序稳定（Turbopack/Vite 首编译 + 用例竞争）。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  fullyParallel: false,
  workers: 1,
  retries: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // 与 C 端同款 corepack 前缀（本机 pnpm 仅 corepack 可用；CI 直用 pnpm）
    command: 'corepack pnpm dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 300_000,
  },
})
