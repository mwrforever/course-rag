# student-frontend（C 端学生端）

封闭私域「学习空间 × 课程橱窗 × AI 助教」消费级前端，面向学生用户。
技术栈：Next.js 15（App Router）+ TypeScript + Tailwind CSS v4（CSS-first tokens）。

## 常用命令

- `pnpm dev`: 本地开发（5000 端口，`/api/v1` 经 next.config.ts rewrite 同源代理到 8080 后端）
- `pnpm lint`: ESLint + Prettier 检查
- `pnpm typecheck`: TypeScript 类型检查（tsc --noEmit）
- `pnpm test:cov`: Vitest 单测 + v8 覆盖率
- `pnpm build`: 生产构建
