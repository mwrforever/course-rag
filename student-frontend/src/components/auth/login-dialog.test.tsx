/**
 * 全局登录弹窗测试（登录弹窗化 2026-08-26：独立 /login 页下线的表单迁移）
 *
 * 覆盖：打开/关闭状态（AuthProvider 驱动）、zod 前置校验（字段级错误就位）、
 * 提交成功（submitLogin 调用并由 Provider 关闭）、失败分级 Alert（401/403/网络）、
 * Esc/遮罩/按钮关闭、登录中不可关闭。
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LoginDialog } from "./login-dialog";
import { ApiError, NetworkError } from "@/lib/api";

/** 认证 mock：弹窗状态可切换 + submitLogin 结果注入 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, ApiError: actual.ApiError, NetworkError: actual.NetworkError };
});

/** 默认弹窗开启态（用例内覆盖） */
function defaultAuth(overrides: Record<string, unknown> = {}) {
  return {
    loginDialogOpen: true,
    submitLogin: vi.fn(),
    closeLoginDialog: vi.fn(),
    ...overrides,
  };
}

function renderDialog() {
  return render(<LoginDialog />);
}

beforeEach(() => {
  authMock.useAuth.mockReset();
});

describe("LoginDialog 状态机", () => {
  it("关闭态：不渲染（open=false）", () => {
    authMock.useAuth.mockReturnValue(defaultAuth({ loginDialogOpen: false }));
    renderDialog();
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("打开：渲染品牌区 + 表单 + 关闭按钮", () => {
    authMock.useAuth.mockReturnValue(defaultAuth());
    renderDialog();
    expect(screen.getByRole("dialog", { name: "登录课程助手" })).toBeInTheDocument();
    expect(screen.getByText("登录课程助手")).toBeInTheDocument();
    expect(screen.getByLabelText("用户名")).toBeInTheDocument();
    expect(screen.getByLabelText("密码")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "登录" })).toBeInTheDocument();
  });

  it("zod 校验：空用户名/短密码 → 字段错误就地显示且不提交", () => {
    const submitLogin = vi.fn();
    authMock.useAuth.mockReturnValue(defaultAuth({ submitLogin }));
    renderDialog();
    fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "123" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    expect(screen.getByText("请输入用户名")).toBeInTheDocument();
    expect(screen.getByText("密码至少 6 位")).toBeInTheDocument();
    expect(submitLogin).not.toHaveBeenCalled();
  });

  it("提交成功：submitLogin(username, password) 被调用（关闭与后续动作由 Provider 处理）", async () => {
    const submitLogin = vi.fn().mockResolvedValue(undefined);
    authMock.useAuth.mockReturnValue(defaultAuth({ submitLogin }));
    renderDialog();
    fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "stu01" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "pass123" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    await waitFor(() => {
      expect(submitLogin).toHaveBeenCalledWith("stu01", "pass123");
    });
    // 成功无失败 Alert
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("失败分级：401 → 「用户名或密码错误」；403 → 「账号已被禁用」", async () => {
    const submitLogin = vi.fn().mockRejectedValue(new ApiError(401, "x"));
    authMock.useAuth.mockReturnValue(defaultAuth({ submitLogin }));
    renderDialog();
    fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "stu01" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "pass123" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("用户名或密码错误");

    submitLogin.mockRejectedValueOnce(new ApiError(403, "y"));
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("账号已被禁用");
  });

  it("失败分级：网络错误 → 「网络连接失败，请检查网络」", async () => {
    const submitLogin = vi.fn().mockRejectedValue(new NetworkError());
    authMock.useAuth.mockReturnValue(defaultAuth({ submitLogin }));
    renderDialog();
    fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "stu01" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "pass123" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("网络连接失败，请检查网络");
  });

  it("关闭：关闭按钮 / Esc / 遮罩点击均调用 closeLoginDialog", () => {
    const closeLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(defaultAuth({ closeLoginDialog }));
    renderDialog();
    fireEvent.click(screen.getByRole("button", { name: "关闭登录" }));
    expect(closeLoginDialog).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(closeLoginDialog).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByTestId("login-overlay"));
    expect(closeLoginDialog).toHaveBeenCalledTimes(3);
  });

  it("登录中：关闭按钮/Esc/遮罩禁用（防提交中断）", async () => {
    const closeLoginDialog = vi.fn();
    let resolveLogin!: () => void;
    const submitLogin = vi.fn().mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveLogin = resolve;
        }),
    );
    authMock.useAuth.mockReturnValue(defaultAuth({ closeLoginDialog, submitLogin }));
    renderDialog();
    fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "stu01" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "pass123" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    // 提交挂起：登录按钮显示「登录中…」
    expect(await screen.findByRole("button", { name: /登录中/ })).toBeDisabled();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(closeLoginDialog).not.toHaveBeenCalled();
    resolveLogin();
  });
});
