import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Vitest 配置：jsdom 环境 + React 插件 + v8 覆盖率；@ 别名与 tsconfig paths 对齐
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    // 全局装配 jest-dom DOM 断言
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
    coverage: {
      provider: "v8",
      reporter: ["text", "html", "lcov"],
      // 覆盖率精确圈定当前已测文件（任务 7 范围：api client + 认证上下文 + 登录页）。
      // 暂不计入的文件与原因：首页占位与布局壳（冒烟测试不计指标）、site-header/middleware
      // （middleware 跑在 edge runtime，无法在 jsdom 单测环境加载 next/server）；
      // 后续任务交付各自测试后逐步放宽 include 至 src 全量。
      include: ["src/lib/api.ts", "src/lib/auth-context.tsx", "src/app/*/login/page.tsx"],
      // 全局行覆盖 80% 兜底；核心文件（api client 与认证，后续所有 C 端任务的地基）行覆盖 100%
      thresholds: {
        lines: 80,
        "src/lib/api.ts": { lines: 100 },
        "src/lib/auth-context.tsx": { lines: 100 },
        "src/app/*/login/page.tsx": { lines: 100 },
      },
    },
  },
});
