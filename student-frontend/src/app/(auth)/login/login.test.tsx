/**
 * 登录页测试（任务 7 TDD 先行用例）
 *
 * 覆盖：表单渲染、zod 校验（username 非空/密码 ≥6）、密码眼睛切换、
 * 错误分级 Alert（401/403/网络）、提交 loading 态、成功跳转（redirect 白名单）。
 * useAuth 与 useRouter 以 mock 隔离（页面单元测试），集成链路由 auth-context.test 覆盖。
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, NetworkError } from "@/lib/api";

const { mockPush, mockLogin } = vi.hoisted(() => ({
  mockPush: vi.fn(),
  mockLogin: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush, replace: vi.fn(), back: vi.fn() }),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    user: null,
    accessToken: null,
    isAuthenticated: false,
    isLoading: false,
    login: mockLogin,
    logout: vi.fn(),
  }),
}));

import LoginPage from "./page";

function fillAndSubmit(username: string, password: string) {
  fireEvent.change(screen.getByLabelText("用户名"), { target: { value: username } });
  fireEvent.change(screen.getByLabelText("密码"), { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: "登录" }));
}

beforeEach(() => {
  window.history.pushState({}, "", "/login");
  mockPush.mockClear();
  mockLogin.mockReset();
});

afterEach(() => {
  window.history.pushState({}, "", "/login");
});

describe("登录页渲染", () => {
  it("渲染品牌、标题与完整表单（用户名/密码/登录按钮，无记住我与注册）", () => {
    render(<LoginPage />);
    expect(screen.getByText("课程助手")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "欢迎回来" })).toBeInTheDocument();
    expect(screen.getByLabelText("用户名")).toBeInTheDocument();
    expect(screen.getByLabelText("密码")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "登录" })).toBeEnabled();
    // 无记住我、无注册入口（设计 §1.5.7）
    expect(screen.queryByLabelText(/记住/)).toBeNull();
    expect(screen.queryByText(/注册/)).toBeNull();
    // 页脚引导文案
    expect(screen.getByText("没有账号？请联系课程老师")).toBeInTheDocument();
  });
});

describe("表单校验（zod 前置）", () => {
  it("空提交：就地显示两处字段错误且不发起登录", () => {
    render(<LoginPage />);
    fillAndSubmit("", "");
    expect(screen.getByText("请输入用户名")).toBeInTheDocument();
    expect(screen.getByText("密码至少 6 位")).toBeInTheDocument();
    expect(mockLogin).not.toHaveBeenCalled();
  });

  it("密码过短：仅显示密码错误", () => {
    render(<LoginPage />);
    fillAndSubmit("stu01", "123");
    expect(screen.queryByText("请输入用户名")).toBeNull();
    expect(screen.getByText("密码至少 6 位")).toBeInTheDocument();
    expect(mockLogin).not.toHaveBeenCalled();
  });

  it("用户名前后空白被裁剪后提交", () => {
    render(<LoginPage />);
    mockLogin.mockResolvedValue(undefined);
    fillAndSubmit("  stu01  ", "pass123");
    expect(mockLogin).toHaveBeenCalledWith("stu01", "pass123");
  });
});

describe("密码可见性切换", () => {
  it("眼睛按钮切换 input type 与 aria-label", () => {
    render(<LoginPage />);
    const passwordInput = screen.getByLabelText("密码") as HTMLInputElement;
    expect(passwordInput.type).toBe("password");
    fireEvent.click(screen.getByRole("button", { name: "显示密码" }));
    expect(passwordInput.type).toBe("text");
    expect(screen.getByRole("button", { name: "隐藏密码" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "隐藏密码" }));
    expect(passwordInput.type).toBe("password");
  });
});

describe("错误分级 Alert（顶部）", () => {
  it("401 → 用户名或密码错误", async () => {
    render(<LoginPage />);
    mockLogin.mockRejectedValue(new ApiError(401, "用户名或密码错误"));
    fillAndSubmit("stu01", "wrong-pass");
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("用户名或密码错误");
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("403 → 账号已被禁用", async () => {
    render(<LoginPage />);
    mockLogin.mockRejectedValue(new ApiError(403, "用户已被禁用"));
    fillAndSubmit("stu01", "pass123");
    expect(await screen.findByRole("alert")).toHaveTextContent("账号已被禁用");
  });

  it("网络错误 → 网络连接失败文案（不跳登录）", async () => {
    render(<LoginPage />);
    mockLogin.mockRejectedValue(new NetworkError());
    fillAndSubmit("stu01", "pass123");
    expect(await screen.findByRole("alert")).toHaveTextContent("网络连接失败，请检查网络");
  });

  it("其他错误 → 兜底文案", async () => {
    render(<LoginPage />);
    mockLogin.mockRejectedValue(new ApiError(503, "服务暂时不可用"));
    fillAndSubmit("stu01", "pass123");
    expect(await screen.findByRole("alert")).toHaveTextContent("登录失败，请稍后重试");
  });
});

describe("提交与跳转", () => {
  it("提交中：按钮禁用并显示加载指示；成功后恢复", async () => {
    render(<LoginPage />);
    let resolveLogin: () => void = () => {};
    mockLogin.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveLogin = resolve;
      }),
    );
    fillAndSubmit("stu01", "pass123");
    // pending 期间禁用
    await waitFor(() => expect(screen.getByRole("button", { name: /登录中/ })).toBeDisabled());
    resolveLogin();
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/"));
  });

  it("成功后默认跳首页", async () => {
    render(<LoginPage />);
    mockLogin.mockResolvedValue(undefined);
    fillAndSubmit("stu01", "pass123");
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/"));
    expect(mockPush).toHaveBeenCalledTimes(1);
  });

  it("携带合法 ?redirect= 时回跳目标页", async () => {
    window.history.pushState({}, "", "/login?redirect=/courses");
    render(<LoginPage />);
    mockLogin.mockResolvedValue(undefined);
    fillAndSubmit("stu01", "pass123");
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/courses"));
  });

  it("redirect 为站外绝对地址时拒绝回跳（只允许站内路径）", async () => {
    window.history.pushState({}, "", "/login?redirect=http://evil.example.com");
    render(<LoginPage />);
    mockLogin.mockResolvedValue(undefined);
    fillAndSubmit("stu01", "pass123");
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/"));
  });
});
