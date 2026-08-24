/**
 * EmptyState 空态组件测试（Task 8 TDD 先行用例）
 *
 * 覆盖：主文案渲染；行动入口（文案 + 跳转目标）；未提供行动入口时不渲染按钮；
 * AI 徽标展示开关（设计 §1.7 Empty 规范：徽标 + 一句话 + 一个行动入口）。
 */
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EmptyState } from "./empty-state";

describe("EmptyState 空态", () => {
  it("渲染主文案与行动入口（AI 徽标默认展示）", () => {
    render(
      <EmptyState
        title="还没有加入课程，请联系老师开通"
        actionLabel="先和 AI 助教聊聊"
        actionHref="/chat"
      />,
    );
    expect(screen.getByText("还没有加入课程，请联系老师开通")).toBeInTheDocument();
    const action = screen.getByRole("link", { name: "先和 AI 助教聊聊" });
    expect(action).toHaveAttribute("href", "/chat");
    expect(screen.getByTestId("ai-badge")).toBeInTheDocument();
  });

  it("未提供行动入口时不渲染按钮", () => {
    render(<EmptyState title="空态文案" />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("关闭 AI 徽标展示（showAiBadge=false）", () => {
    render(<EmptyState title="空态文案" showAiBadge={false} />);
    expect(screen.queryByTestId("ai-badge")).not.toBeInTheDocument();
  });
});
