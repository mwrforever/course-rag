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
      // 覆盖率圈定已实现文件（Task 7 三文件 + Task 8 首页与组件 + Task 9 课程列表/工作台
      // + Task 10 SSE 解析器）。
      // 暂不计入的文件与原因：site-header/middleware（middleware 跑在 edge runtime，
      // 无法在 jsdom 单测环境加载 next/server；site-header 导航激活态随导航任务落地后补测）；
      // 后续任务交付各自测试后逐步放宽 include 至 src 全量。
      include: [
        "src/lib/api.ts",
        "src/lib/auth-context.tsx",
        "src/lib/query-provider.tsx",
        "src/lib/sse-parser.ts",
        "src/lib/time.ts",
        "src/lib/history-adapter.ts",
        "src/hooks/use-chat-stream.ts",
        "src/app/*/login/page.tsx",
        "src/app/*/page.tsx",
        "src/app/*/layout.tsx",
        "src/app/*/courses/page.tsx",
        "src/app/*/courses/*/page.tsx",
        "src/app/*/chat/page.tsx",
        "src/app/*/chat/*/page.tsx",
        "src/app/*/chat/chat-workspace.tsx",
        "src/app/*/sessions/page.tsx",
        "src/app/*/profile/page.tsx",
        "src/components/ai-badge.tsx",
        "src/components/course-card.tsx",
        "src/components/empty-state.tsx",
        "src/components/section-error.tsx",
        "src/components/chunk-item.tsx",
        "src/components/chunk-context-drawer.tsx",
        "src/components/chat/*.tsx",
      ],
      // 全局行覆盖 80% 兜底；核心文件（api client 与认证、SSE 解析、对话状态机、
      // 历史回显适配器，后续所有 C 端任务的地基）行覆盖 100%
      thresholds: {
        lines: 80,
        "src/lib/api.ts": { lines: 100 },
        "src/lib/auth-context.tsx": { lines: 100 },
        "src/lib/sse-parser.ts": { lines: 100 },
        "src/lib/history-adapter.ts": { lines: 100 },
        "src/hooks/use-chat-stream.ts": { lines: 100 },
        "src/app/*/login/page.tsx": { lines: 100 },
      },
    },
  },
});
