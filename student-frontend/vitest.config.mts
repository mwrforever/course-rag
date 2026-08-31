import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Vitest 配置：jsdom 环境 + React 插件 + v8 覆盖率；@ 别名与 tsconfig paths 对齐
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      // react-medium-image-zoom 样式仅浏览器构建生效；vitest 内 vite:css 会加载项目
      // PostCSS 配置（@tailwindcss/postcss 在 node 测试上下文不可实例化直接抛错），
      // 任何 .css 导入（含空桩）都会失败——别名到空 .ts 模块（命中先于扩展名解析，
      // 不进 css 管道），Zoom 组件行为不受影响
      "react-medium-image-zoom/dist/styles.css": fileURLToPath(
        new URL("./src/test/zoom-styles-stub.ts", import.meta.url),
      ),
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
      // + Task 10 SSE 解析器 + 登录弹窗化/会话管理 2026-08-26 组件 + 2026-08-30
      // 认证刷新链路修复的 middleware 矩阵与 AuthGate 守卫）。
      // 暂不计入的文件与原因：site-header（导航激活态随导航任务落地后补测）；
      // middleware 的 next/server 依赖标准 Web Request/Response，jsdom 可直接构造
      // （2026-08-30 实证），已纳入圈定。
      // 后续任务交付各自测试后逐步放宽 include 至 src 全量。
      include: [
        "src/middleware.ts",
        "src/lib/api.ts",
        "src/lib/auth-context.tsx",
        "src/lib/query-provider.tsx",
        "src/lib/sse-parser.ts",
        "src/lib/history-adapter.ts",
        "src/hooks/use-chat-stream.ts",
        "src/app/*/page.tsx",
        "src/app/*/layout.tsx",
        "src/app/*/courses/page.tsx",
        "src/app/*/courses/*/page.tsx",
        "src/app/*/chat/page.tsx",
        "src/app/*/chat/*/page.tsx",
        "src/app/*/chat/chat-workspace.tsx",
        "src/app/*/profile/page.tsx",
        "src/app/*/my-courses/page.tsx",
        "src/components/ai-badge.tsx",
        "src/components/course-card.tsx",
        "src/components/empty-state.tsx",
        "src/components/section-error.tsx",
        "src/components/chat/*.tsx",
        "src/components/auth/*.tsx",
        "src/components/motion/*.{ts,tsx}",
        "src/components/home/*.tsx",
        "src/components/scroll-progress.tsx",
        "src/lib/auth-schemas.ts",
        "src/lib/password-strength.ts",
        "src/app/*/login/**/*.ts*",
        "src/components/confirm-dialog.tsx",
        "src/hooks/use-debounced-value.ts",
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
      },
    },
  },
});
