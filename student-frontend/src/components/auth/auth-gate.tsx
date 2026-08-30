"use client";

/**
 * 受保护页客户端守卫（认证刷新链路修复 2026-08-30 新增）
 *
 * 职责：middleware 放行「AT cookie 过期但 RT 有效」的请求后，受保护页在客户端的
 * 三态承接——挂载静默续期窗口（isLoading）渲染骨架不渲染业务内容（不闪登录页）；
 * 续期完成已认证渲染 children；续期完成仍未认证（RT 无效 / 提示 cookie 与实际
 * RT 失配的边缘场景）打开全局登录弹窗兜底（弹窗化失败终态，与 401 回调同款设计，
 * 不做整页硬跳转 /login）。
 *
 * 用法：受保护路由组布局/页面最外层包裹；fallback 可传同形骨架保持各页既有视觉。
 * 注意：课程详情页（/courses/[id]）已有自带守卫（公开信息可浏览 + 资料区登录墙），
 * 不使用本组件全页拦截。
 */
import { useEffect } from "react";
import { useAuth } from "@/lib/auth-context";

/** 默认骨架：页面级灰块脉冲（与各页骨架同风格，设计 §1.7 Loading） */
function AuthGateSkeleton() {
  return (
    <div data-testid="auth-gate-skeleton" aria-busy="true" className="flex-1 px-6 py-10">
      <div className="h-9 w-1/3 animate-pulse rounded-xl bg-surface-2" />
      <div className="mt-6 h-40 animate-pulse rounded-2xl bg-surface-2" />
      <div className="mt-6 h-4 w-2/3 animate-pulse rounded-lg bg-surface-2" />
    </div>
  );
}

/**
 * 受保护页客户端守卫
 *
 * @param children 受保护的业务内容（已认证时渲染）
 * @param fallback 续期窗口/未认证期间的骨架（缺省用通用骨架；各页可传同形骨架）
 */
export function AuthGate({
  children,
  fallback,
}: {
  children: React.ReactNode;
  fallback?: React.ReactNode;
}) {
  const { isLoading, isAuthenticated, openLoginDialog } = useAuth();

  useEffect(() => {
    // 续期完成仍未认证：打开全局登录弹窗（弹窗即失败终态；401 刷新失败回调已开过时此处幂等）
    if (!isLoading && !isAuthenticated) {
      openLoginDialog();
    }
  }, [isLoading, isAuthenticated, openLoginDialog]);

  // 续期窗口与未认证期间都不渲染业务内容（防未登录态泄漏与闪屏）
  if (isLoading || !isAuthenticated) {
    return <>{fallback ?? <AuthGateSkeleton />}</>;
  }
  return <>{children}</>;
}
