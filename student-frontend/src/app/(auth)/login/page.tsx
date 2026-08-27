import { Suspense } from "react";
import { LoginView } from "./login-view";

/**
 * 登录页（设计稿二独立页面；2026-08-27 恢复独立路由）
 *
 * Suspense 包裹：客户端组件读取 useSearchParams（tab=register 预选），
 * Next 15 静态渲染要求显式边界防 CSR bailout。
 */
export default function LoginPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-bg" aria-busy="true" />}>
      <LoginView />
    </Suspense>
  );
}

/** SEO 元数据（中文化） */
export const metadata = {
  title: "登录 · 问渠学堂",
};
