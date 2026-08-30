/**
 * AuthGate 受保护页客户端守卫测试（认证刷新链路修复 2026-08-30 新增）
 *
 * 覆盖三态：
 * 1. isLoading（挂载静默续期窗口）：渲染骨架，不渲染业务内容（防闪屏/闪登录页）
 * 2. isAuthenticated：正常渲染 children
 * 3. 续期完成仍未认证（RT 无效 / 提示 cookie 与实际 RT 失配的边缘场景）：
 *    触发全局登录弹窗（弹窗化失败终态，不做整页硬跳转 /login）且不渲染业务内容
 *
 * 另覆盖自定义 fallback（各页传同形骨架保持既有视觉）。
 */
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthGate } from "./auth-gate";

/** 认证 mock：三态由各用例注入 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => authMock.useAuth(),
}));

/** 构造认证上下文值（缺省已登录就绪，字段按用例覆盖） */
function authState(overrides: Record<string, unknown> = {}) {
  return {
    user: { userId: "u-1", role: "STUDENT", displayName: "同学A" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    openLoginDialog: vi.fn(),
    ...overrides,
  };
}

beforeEach(() => {
  authMock.useAuth.mockReset();
  authMock.useAuth.mockReturnValue(authState());
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AuthGate 受保护页守卫", () => {
  it("isLoading（静默续期中）：渲染默认骨架且不渲染业务内容", () => {
    authMock.useAuth.mockReturnValue(
      authState({ user: null, isAuthenticated: false, isLoading: true }),
    );
    render(
      <AuthGate>
        <div data-testid="business-content">业务内容</div>
      </AuthGate>,
    );
    // 骨架可见（续期期间不闪登录页、不闪空白）
    expect(screen.getByTestId("auth-gate-skeleton")).toBeInTheDocument();
    expect(screen.queryByTestId("business-content")).not.toBeInTheDocument();
  });

  it("isLoading 且传入自定义 fallback：渲染调用方骨架（各页同形视觉）", () => {
    authMock.useAuth.mockReturnValue(
      authState({ user: null, isAuthenticated: false, isLoading: true }),
    );
    render(
      <AuthGate fallback={<div data-testid="custom-skeleton">同形骨架</div>}>
        <div data-testid="business-content">业务内容</div>
      </AuthGate>,
    );
    expect(screen.getByTestId("custom-skeleton")).toBeInTheDocument();
    expect(screen.queryByTestId("auth-gate-skeleton")).not.toBeInTheDocument();
    expect(screen.queryByTestId("business-content")).not.toBeInTheDocument();
  });

  it("isAuthenticated：正常渲染 children", () => {
    render(
      <AuthGate>
        <div data-testid="business-content">业务内容</div>
      </AuthGate>,
    );
    expect(screen.getByTestId("business-content")).toBeInTheDocument();
    expect(screen.queryByTestId("auth-gate-skeleton")).not.toBeInTheDocument();
    expect(screen.queryByTestId("custom-skeleton")).not.toBeInTheDocument();
  });

  it("续期完成仍未认证：触发全局登录弹窗（失败终态）且不渲染业务内容", () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(
      authState({ user: null, isAuthenticated: false, isLoading: false, openLoginDialog }),
    );
    render(
      <AuthGate>
        <div data-testid="business-content">业务内容</div>
      </AuthGate>,
    );
    // 弹窗化失败终态：打开登录弹窗（不整页跳 /login）
    expect(openLoginDialog).toHaveBeenCalled();
    // 未认证期间业务内容不渲染（骨架兜底，防未登录态泄漏到受保护页）
    expect(screen.queryByTestId("business-content")).not.toBeInTheDocument();
  });
});
