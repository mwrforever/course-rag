/**
 * (main) 主站布局测试：顶导壳（UI 重构 2026-08-25：SiteHeader 引入路由感知
 * 的用户下拉，需 mock next/navigation）+ QueryProvider 挂载
 *
 * (auth) 路由组（登录页）无此壳；本布局负责全站顶导与 react-query 服务端状态上下文。
 */
import { QueryClient, useQueryClient } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import MainLayout from "./layout";

// SiteHeader 依赖 next/navigation（usePathname 激活态 + useRouter 登出跳转）与
// auth-context（useAuth 用户态），jsdom 无 App Router/AuthProvider 上下文，mock 最小实现
vi.mock("next/navigation", () => ({
  usePathname: () => "/",
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    user: { userId: "u1", role: "STUDENT", displayName: "同学A" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
  }),
}));

/** 探针：验证 children 处于 QueryProvider 上下文内 */
function Probe() {
  const client = useQueryClient();
  return <span data-testid="client">{client instanceof QueryClient ? "已挂载" : "未挂载"}</span>;
}

describe("(main) 主站布局", () => {
  it("渲染顶导品牌标识并挂载 QueryProvider", () => {
    render(
      <MainLayout>
        <Probe />
      </MainLayout>,
    );
    // 顶导品牌标识（渐变 Logo）与主导航（「课程助手」一词被 Logo 与导航项共用，按 testid 定位 Logo；
    // 「首页」同时存在于顶导与底栏，按出现断言）
    expect(screen.getByTestId("site-logo")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "首页" }).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByTestId("client")).toHaveTextContent("已挂载");
  });

  it("渲染全站底栏（品牌区 + 版权行）", () => {
    render(
      <MainLayout>
        <Probe />
      </MainLayout>,
    );
    expect(screen.getByTestId("site-footer")).toBeInTheDocument();
    expect(screen.getByText("© 2026 课程助手 · 保留所有权利")).toBeInTheDocument();
  });
});
