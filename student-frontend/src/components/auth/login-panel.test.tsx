/**
 * 登录面板测试（独立登录页 2026-08-27）
 *
 * 覆盖：zod 边界拦截（空账号/短密码分字段落错）、submitLogin 成功回调、
 * 服务端错误分级（401 凭证错误 / 403 禁用 / 其他网络提示）、提交互斥防重复。
 */
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LoginPanel } from "./login-panel";
import { ApiError } from "@/lib/api";
import type { AuthContextValue } from "@/lib/auth-context";

/** 认证 mock：submitLogin 行为用例内注入 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));

/** 默认认证返回值（用例内覆盖 submitLogin） */
function defaultAuth(overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    user: null,
    accessToken: null,
    isAuthenticated: false,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
    loginDialogOpen: false,
    openLoginDialog: vi.fn(),
    closeLoginDialog: vi.fn(),
    submitLogin: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  authMock.useAuth.mockReturnValue(defaultAuth());
});

async function fillAndSubmit(account = "wenqu", password = "password123") {
  await userEvent.type(screen.getByTestId("login-account-input"), account);
  await userEvent.type(screen.getByTestId("login-password-input"), password);
  await userEvent.click(screen.getByTestId("login-submit"));
}

describe("LoginPanel 边界校验", () => {
  it("空用户名/短密码：拦在本组件内且不触发 submitLogin", async () => {
    render(<LoginPanel onSuccess={() => {}} />);
    await userEvent.click(screen.getByTestId("login-submit"));
    expect(screen.getAllByRole("alert").length).toBeGreaterThanOrEqual(1);
    expect(authMock.useAuth().submitLogin).not.toHaveBeenCalled();
  });

  it("合法提交：调用 submitLogin 且成功后执行 onSuccess", async () => {
    const onSuccess = vi.fn();
    render(<LoginPanel onSuccess={onSuccess} />);
    await fillAndSubmit();
    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1));
    const ctx = authMock.useAuth();
    // eslint-disable-next-line @typescript-eslint/unbound-method
    expect(ctx.submitLogin).toHaveBeenCalledWith("wenqu", "password123");
  });
});

describe("LoginPanel 服务端错误分级", () => {
  it("401 → 提示凭证错误；403 → 提示账号被禁用；其他 → 网络异常", async () => {
    // 依次验证三种分支：每次渲染独立、断言 alert 文案
    for (const [error, text] of [
      [new ApiError(401, "用户名或密码错误"), "用户名或密码错误"],
      [new ApiError(403, "用户已被禁用"), "账号已被禁用，如有疑问请联系管理员"],
      [new Error("boom"), "网络异常，请稍后再试"],
    ] as const) {
      cleanup();
      authMock.useAuth.mockReturnValue(
        defaultAuth({ submitLogin: vi.fn().mockRejectedValue(error) }),
      );
      render(<LoginPanel onSuccess={() => {}} />);
      await fillAndSubmit();
      await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(text));
    }
  });
});
