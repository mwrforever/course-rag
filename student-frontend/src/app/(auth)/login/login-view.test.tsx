/**
 * 登录页视图测试（2026-08-30 登录态保持修复）
 *
 * 覆盖：
 * - 静默续期已恢复登录态（AuthProvider 用 localStorage RT 续期成功）→ 自动回跳 ?next
 *   （middleware 仅查 AT cookie、RT 服务端不可见——AT 过期后被踢到 /login 的用户无感续期）
 * - 未登录（无 RT / 续期失败）→ 不自动跳转，停留在登录页
 * - 续期加载中（isLoading）→ 不跳转（防续期完成前误跳）
 */
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const routerMock = { push: vi.fn(), replace: vi.fn() };
const searchParamsMock = { current: new URLSearchParams() };
const authMock = vi.hoisted(() => ({
  isAuthenticated: false,
  isLoading: false,
}));
vi.mock("next/navigation", () => ({
  useRouter: () => routerMock,
  useSearchParams: () => searchParamsMock.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    user: authMock.isAuthenticated ? { id: "1", displayName: "u" } : null,
    isAuthenticated: authMock.isAuthenticated,
    isLoading: authMock.isLoading,
    login: vi.fn(),
    logout: vi.fn(),
    openLoginDialog: vi.fn(),
    closeLoginDialog: vi.fn(),
  }),
}));
// 登录面板/注册面板子组件保持原样渲染（不 mock，避免登录表单交互干扰自动续期断言）
import { LoginView } from "./login-view";

beforeEach(() => {
  routerMock.push.mockReset();
  routerMock.replace.mockReset();
  searchParamsMock.current = new URLSearchParams();
  authMock.isAuthenticated = false;
  authMock.isLoading = false;
});

describe("LoginView 自动静默续期回跳（2026-08-30 登录态保持修复）", () => {
  it("续期完成且已登录：自动回跳 ?next 目标（无感续期，不再强制手动登录）", () => {
    authMock.isAuthenticated = true;
    authMock.isLoading = false;
    searchParamsMock.current = new URLSearchParams({ next: "/chat" });
    render(<LoginView />);
    expect(routerMock.replace).toHaveBeenCalledWith("/chat");
  });

  it("无 next 参数时回跳首页", () => {
    authMock.isAuthenticated = true;
    authMock.isLoading = false;
    render(<LoginView />);
    expect(routerMock.replace).toHaveBeenCalledWith("/");
  });

  it("next 为非法值（外部 URL）收敛回首页（防开放重定向，与登录成功同契约）", () => {
    authMock.isAuthenticated = true;
    authMock.isLoading = false;
    searchParamsMock.current = new URLSearchParams({ next: "https://evil.example" });
    render(<LoginView />);
    expect(routerMock.replace).toHaveBeenCalledWith("/");
  });

  it("未登录（无 RT/续期失败）：不自动跳转，登录页正常渲染", () => {
    authMock.isAuthenticated = false;
    authMock.isLoading = false;
    render(<LoginView />);
    expect(routerMock.replace).not.toHaveBeenCalled();
    // 登录表单仍在（欢迎回来）
    expect(screen.getByText(/欢迎回来/)).toBeInTheDocument();
  });

  it("续期进行中（isLoading）：不跳转（防续期完成前误跳，等待续期结果）", () => {
    authMock.isAuthenticated = false;
    authMock.isLoading = true;
    render(<LoginView />);
    expect(routerMock.replace).not.toHaveBeenCalled();
  });
});
