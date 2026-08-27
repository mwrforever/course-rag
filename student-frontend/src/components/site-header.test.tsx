/**
 * 顶导测试（2026-08-26 新增：登录态切换语义专属覆盖）
 *
 * 覆盖：已登录头像+下拉（身份信息/个人中心/退出登录，且无登录按钮）；
 * 未登录桌面登录按钮（点击触发全局登录弹窗）+ 不渲染头像（登出后不再展示登录样式）；
 * 挂载静默续期窗口骨架占位（防闪变）；未登录移动端游客菜单（公开导航 + 登录入口）；
 * 登出二次确认全流程（确认 → logout + 清缓存跳首页 / 取消不登出）。
 *
 * 依赖 mock：next/navigation（路由激活/跳转）、auth-context（登录态可切换）、
 * react-query QueryClient（useQueryClient 上下文）。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SiteHeader } from "./site-header";
import type { AuthContextValue } from "@/lib/auth-context";

const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
const routerMock = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock("next/navigation", () => ({
  usePathname: () => "/",
  useRouter: () => routerMock,
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));

/** 默认认证返回值：已登录同学A（用例内覆盖） */
function defaultAuth(overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    user: { userId: "u1", role: "STUDENT", displayName: "同学A" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
    loginDialogOpen: false,
    openLoginDialog: vi.fn(),
    closeLoginDialog: vi.fn(),
    submitLogin: vi.fn(),
    ...overrides,
  };
}

/** 渲染容器：QueryClient 包裹（SiteHeader 依赖 useQueryClient） */
function renderHeader() {
  const client = new QueryClient();
  return render(
    <QueryClientProvider client={client}>
      <SiteHeader />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  authMock.useAuth.mockReturnValue(defaultAuth());
});

describe("SiteHeader：登录态切换", () => {
  it("已登录：头像首字母 + 下拉含身份信息/个人中心/退出登录，无登录按钮", () => {
    renderHeader();
    expect(screen.getByTestId("header-avatar")).toHaveTextContent("同");
    fireEvent.click(screen.getByRole("button", { name: "用户菜单" }));
    expect(screen.getByTestId("user-menu")).toBeInTheDocument();
    expect(screen.getByText("同学A")).toBeInTheDocument();
    // jsdom 无媒体查询：移动/桌面「个人中心」入口并存，按出现断言
    expect(screen.getAllByRole("link", { name: "个人中心" }).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByRole("button", { name: "退出登录" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "登录" })).toBeNull();
  });

  it("未登录：桌面「登录」文字链跳独立登录页，「注册」按钮携带 tab=register 参数", () => {
    authMock.useAuth.mockReturnValue(defaultAuth({ user: null, isAuthenticated: false }));
    renderHeader();
    // 独立登录页回归（2026-08-27）：导航入口为路由跳转而非全局弹窗
    expect(screen.getByTestId("login-link")).toHaveAttribute("href", "/login");
    expect(screen.getByTestId("register-link")).toHaveAttribute("href", "/login?tab=register");
  });

  it("未登录：不渲染头像（登出后导航栏不再展示登录样式）", () => {
    authMock.useAuth.mockReturnValue(defaultAuth({ user: null, isAuthenticated: false }));
    renderHeader();
    expect(screen.queryByTestId("header-avatar")).toBeNull();
  });

  it("挂载静默续期窗口：骨架占位不渲染认证入口（防闪变）", () => {
    authMock.useAuth.mockReturnValue(
      defaultAuth({ user: null, isAuthenticated: false, isLoading: true }),
    );
    renderHeader();
    expect(screen.queryByTestId("login-link")).toBeNull();
    expect(screen.queryByTestId("register-link")).toBeNull();
  });

  it("未登录：主导航三项直出（单层导航，2026-08-27）+ 登录注册入口", () => {
    authMock.useAuth.mockReturnValue(defaultAuth({ user: null, isAuthenticated: false }));
    renderHeader();
    // 单层主栏直出导航（无汉堡抽屉）：三项关联路由 + 激活语义
    const nav = screen.getByTestId("main-nav");
    expect(within(nav).getByRole("link", { name: "首页" })).toHaveAttribute("href", "/");
    expect(within(nav).getByRole("link", { name: "课程助手" })).toHaveAttribute("href", "/chat");
    expect(within(nav).getByRole("link", { name: "课程中心" })).toHaveAttribute("href", "/courses");
    // 未登录认证区：登录 + 注册
    expect(screen.getByTestId("login-link")).toHaveAttribute("href", "/login");
    expect(screen.getByTestId("register-link")).toHaveAttribute("href", "/login?tab=register");
  });

  it("已登录：主导航仍直出（认证区换头像下拉，个人中心在下拉中）", () => {
    renderHeader();
    const nav = screen.getByTestId("main-nav");
    expect(within(nav).getAllByRole("link").length).toBe(3);
    expect(screen.queryByTestId("login-link")).not.toBeInTheDocument();
  });
});

describe("SiteHeader：登出二次确认", () => {
  it("确认退出：调 logout 并跳转首页", async () => {
    const logout = vi.fn().mockResolvedValue(undefined);
    authMock.useAuth.mockReturnValue(defaultAuth({ logout }));
    renderHeader();
    fireEvent.click(screen.getByRole("button", { name: "用户菜单" }));
    fireEvent.click(screen.getByRole("button", { name: "退出登录" }));
    await screen.findByRole("dialog", { name: "退出登录" });
    fireEvent.click(screen.getByRole("button", { name: "退出" }));
    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1));
    expect(routerMock.push).toHaveBeenCalledWith("/");
  });

  it("取消：不触发登出且弹窗关闭", async () => {
    const logout = vi.fn();
    authMock.useAuth.mockReturnValue(defaultAuth({ logout }));
    renderHeader();
    fireEvent.click(screen.getByRole("button", { name: "用户菜单" }));
    fireEvent.click(screen.getByRole("button", { name: "退出登录" }));
    await screen.findByRole("dialog", { name: "退出登录" });
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(logout).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });
});
