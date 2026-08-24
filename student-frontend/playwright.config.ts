import { defineConfig, devices } from "@playwright/test";

/**
 * C 端 E2E 配置（TASK.md §2：student-frontend 独立 E2E 工程）
 *
 * 全 route-mock 模式：不依赖真实后端（CI 无 LLM/中间件依赖），
 * webServer 起 dev server，用例自行拦截 /api/v1/** 并 fulfill 预录响应。
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 45_000,
  // 串行执行：route-mock 全量并行时 dev server（Turbopack 首编译 + 29 用例竞争）
  // 会拖垮渲染时序导致瞬态断言超时；单 worker 换取稳定性（CI 时长可接受）
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    // 本机 pnpm 仅可经 corepack 调用（CI 的 pnpm/action-setup 则直接可用）
    command: "corepack pnpm dev",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    // 首次编译 + next/font 自托管字体分片拉取（NotoSansSC）可能较慢，放宽到 5 分钟
    timeout: 300_000,
  },
});
