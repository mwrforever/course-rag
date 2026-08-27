/**
 * 注册面板测试（邮箱验证码两段式 2026-08-27）
 *
 * 覆盖：发码前置邮箱校验（非法不发请求）；发码成功启动倒计时并显示提示条；
 * 发码失败透传后端文案；注册全表单校验（缺条款 / 错码格式分别落错）；
 * registerAndLogin 成功回调；服务端失败透传 message。
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RegisterPanel } from "./register-panel";

/** api mock：三个函数行为用例内覆盖 */
const apiMock = vi.hoisted(() => ({
  sendRegisterCode: vi.fn().mockResolvedValue(undefined),
  registerAndLogin: vi.fn().mockResolvedValue({}),
  ApiError: class extends Error {
    code: number;
    constructor(code: number, message: string) {
      super(message);
      this.code = code;
    }
  },
}));

vi.mock("@/lib/api", async () => ({
  ...(await vi.importActual<typeof import("@/lib/api")>("@/lib/api")),
  sendRegisterCode: apiMock.sendRegisterCode,
  registerAndLogin: apiMock.registerAndLogin,
}));

beforeEach(() => {
  apiMock.sendRegisterCode.mockReset().mockResolvedValue(undefined);
  apiMock.registerAndLogin.mockReset().mockResolvedValue({});
});

/** 填写完整合法注册表单（条款勾选由用例决定） */
async function fillValidForm({ withTerms = true }: { withTerms?: boolean } = {}) {
  await userEvent.type(screen.getByTestId("reg-nickname-input"), "同学B");
  await userEvent.type(screen.getByTestId("reg-email-input"), "b@example.com");
  await userEvent.type(screen.getByTestId("reg-code-input"), "654321");
  await userEvent.type(screen.getByTestId("reg-password-input"), "Password-88");
  if (withTerms) {
    fireEvent.click(screen.getByTestId("terms-checkbox"));
  }
  await userEvent.click(screen.getByTestId("register-submit"));
}

describe("发送验证码", () => {
  it("非法邮箱点击发码：拦截在 zod 层且不调用接口", async () => {
    render(<RegisterPanel onSuccess={() => {}} />);
    await userEvent.type(screen.getByTestId("reg-email-input"), "not-an-email");
    await userEvent.click(screen.getByTestId("send-code-button"));
    expect(apiMock.sendRegisterCode).not.toHaveBeenCalled();
  });

  it("合法邮箱发码成功：接口按归一化小写调用 + 提示条出现", async () => {
    render(<RegisterPanel onSuccess={() => {}} />);
    await userEvent.type(screen.getByTestId("reg-email-input"), " B@Example.COM ");
    await userEvent.click(screen.getByTestId("send-code-button"));
    await waitFor(() => expect(apiMock.sendRegisterCode).toHaveBeenCalledWith("b@example.com"));
    expect(await screen.findByTestId("register-server-ok")).toHaveTextContent("15 分钟内有效");
  });

  it("发码失败：503/409 后端中文 message 直接展示", async () => {
    apiMock.sendRegisterCode.mockRejectedValue(
      new apiMock.ApiError(503, "验证码邮件发送失败，请稍后重试"),
    );
    render(<RegisterPanel onSuccess={() => {}} />);
    await userEvent.type(screen.getByTestId("reg-email-input"), "b@example.com");
    await userEvent.click(screen.getByTestId("send-code-button"));
    await waitFor(() =>
      expect(screen.getByTestId("register-server-error")).toHaveTextContent("发送失败"),
    );
  });
});

describe("提交注册", () => {
  it("未勾选条款：拦截展示条款提示且不调注册接口", async () => {
    render(<RegisterPanel onSuccess={() => {}} />);
    await fillValidForm({ withTerms: false });
    await waitFor(() => expect(screen.getAllByRole("alert").length).toBeGreaterThanOrEqual(1));
    expect(apiMock.registerAndLogin).not.toHaveBeenCalled();
  });

  it("验证码格式错误：拦在本面板（不触达后端）", async () => {
    render(<RegisterPanel onSuccess={() => {}} />);
    await userEvent.type(screen.getByTestId("reg-email-input"), "b@example.com");
    await userEvent.type(screen.getByTestId("reg-code-input"), "12a456");
    await userEvent.type(screen.getByTestId("reg-password-input"), "Password-88");
    fireEvent.click(screen.getByTestId("terms-checkbox"));
    await userEvent.click(screen.getByTestId("register-submit"));
    expect(screen.getAllByRole("alert").length).toBeGreaterThanOrEqual(1);
    expect(apiMock.registerAndLogin).not.toHaveBeenCalled();
  });

  it("合法载荷成功：昵称空串映射 undefined 并回调 onSuccess", async () => {
    const onSuccess = vi.fn();
    render(<RegisterPanel onSuccess={onSuccess} />);
    await userEvent.type(screen.getByTestId("reg-email-input"), "b@example.com");
    await userEvent.type(screen.getByTestId("reg-code-input"), "654321");
    await userEvent.type(screen.getByTestId("reg-password-input"), "Password-88");
    fireEvent.click(screen.getByTestId("terms-checkbox"));
    await userEvent.click(screen.getByTestId("register-submit"));
    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1));
    // eslint-disable-next-line @typescript-eslint/unbound-method
    expect(apiMock.registerAndLogin).toHaveBeenCalledWith({
      email: "b@example.com",
      code: "654321",
      password: "Password-88",
      nickname: undefined,
    });
  });

  it("后端拒绝（400 验证码错误）：message 原文透传至反馈条", async () => {
    // 与组件同源的 ApiError 类：mock 模块保留真实导出，instanceof 分支才会命中
    const { ApiError: RealApiError } = await import("@/lib/api");
    apiMock.registerAndLogin.mockRejectedValue(new RealApiError(400, "验证码错误"));
    render(<RegisterPanel onSuccess={() => {}} />);
    await fillValidForm();
    await waitFor(() =>
      expect(screen.getByTestId("register-server-error")).toHaveTextContent("验证码错误"),
    );
  });
});
