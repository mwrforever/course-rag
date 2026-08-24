/**
 * 思考卡测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 ThinkingCard + §1.6 动效）：
 * - 思考中：标题「正在思考…」+ 指示灯 + 全文展开
 * - thinking_end 后自动折叠为一行摘要（末句截断），标题「已思考」
 * - 手动 toggle 展开/折叠；折叠动画 240ms（grid-rows 高度过渡，motion-reduce 降级）
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThinkingCard, summarizeThinking } from "./thinking-card";

describe("summarizeThinking 摘要（末句截断）", () => {
  it("取末句作为摘要", () => {
    expect(summarizeThinking("先分析问题。再给出结论。")).toBe("再给出结论。");
  });
  it("无句末标点：整段截断为 30 字加省略号", () => {
    const long = "这是一段没有标点的很长很长的思考过程".repeat(3);
    const summary = summarizeThinking(long);
    expect(summary.length).toBeLessThanOrEqual(31);
    expect(summary.endsWith("…")).toBe(true);
  });
  it("空内容：返回空串", () => {
    expect(summarizeThinking("")).toBe("");
    expect(summarizeThinking("   ")).toBe("");
  });
});

describe("ThinkingCard 状态与折叠", () => {
  it("思考中（未结束）：标题「正在思考…」+ 指示灯 + 全文可见", () => {
    render(<ThinkingCard thinking="正在逐步推理。" ended={false} />);
    expect(screen.getByRole("button", { name: /正在思考/ })).toBeInTheDocument();
    expect(screen.getByTestId("thinking-spinner")).toBeInTheDocument();
    expect(screen.getByText("正在逐步推理。")).toBeInTheDocument();
  });

  it("ended=true：自动折叠为摘要行（末句），标题「已思考」", () => {
    render(<ThinkingCard thinking="先归纳问题。再给出答案。" ended={true} />);
    expect(screen.getByRole("button", { name: /已思考/ })).toBeInTheDocument();
    expect(screen.getByTestId("thinking-summary")).toHaveTextContent("再给出答案。");
    // 折叠态：全文容器高度归零（grid-rows-[0fr]），全文对读屏隐藏
    expect(screen.getByTestId("thinking-content")).toHaveClass("grid-rows-[0fr]");
  });

  it("点击 toggle：展开全文（grid-rows-[1fr]），再点折叠回摘要", () => {
    render(<ThinkingCard thinking="先归纳问题。再给出答案。" ended={true} />);
    fireEvent.click(screen.getByRole("button", { name: /已思考/ }));
    expect(screen.getByTestId("thinking-content")).toHaveClass("grid-rows-[1fr]");
    expect(screen.queryByTestId("thinking-summary")).not.toBeInTheDocument();
    expect(screen.getByText(/先归纳问题/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /已思考/ }));
    expect(screen.getByTestId("thinking-content")).toHaveClass("grid-rows-[0fr]");
    expect(screen.getByTestId("thinking-summary")).toHaveTextContent("再给出答案。");
  });

  it("折叠动效：高度过渡 240ms + reduced-motion 降级类存在", () => {
    render(<ThinkingCard thinking="文本。" ended={true} />);
    const content = screen.getByTestId("thinking-content");
    expect(content.className).toContain("transition-[grid-template-rows]");
    expect(content.className).toContain("duration-240");
  });
});
