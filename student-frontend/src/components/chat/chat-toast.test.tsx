/**
 * 页面级 toast 测试（Task 12 TDD 先行用例）
 *
 * 轻量 toast（无新依赖，与 auth-context 登录失效 toast 同思路）：
 * 固定底部居中、role=status 供读屏播报；null 不渲染。
 */
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ChatToast } from "./chat-toast";

describe("ChatToast", () => {
  it("message 为 null：不渲染", () => {
    render(<ChatToast message={null} />);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("有消息：role=status 展示文案", () => {
    render(<ChatToast message="当前会话正在回答中" />);
    const toast = screen.getByRole("status");
    expect(toast).toHaveTextContent("当前会话正在回答中");
  });
});
