/**
 * 思考步骤测试（2026-08-28 时间线改版：头部行原位 + mask 收起展开 + 逐行 reveal）
 *
 * 覆盖：默认收起（aria-expanded=false）/ 点击展开切换 / 运行态 shimmer「思考中」与
 * 末行 now 呼吸 / 完成态「思考已完成」绿勾步骤类 / 空白行过滤
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ThinkingStep, visibleThinkingLines } from "./thinking-step";
import type { TimelineThinkingNode } from "@/lib/types";

/** 思考节点工厂 */
function makeNode(over?: Partial<TimelineThinkingNode>): TimelineThinkingNode {
  return {
    kind: "thinking",
    stage: "generating",
    lines: ["第一步分析问题", "第二步检索资料"],
    ended: false,
    ...over,
  };
}

describe("visibleThinkingLines 行过滤", () => {
  it("裁剪行尾空白并过滤空行（流式残留占位行不渲染）", () => {
    expect(visibleThinkingLines(["第一行  ", "", "第二行", "   "])).toEqual(["第一行", "第二行"]);
  });
});

describe("ThinkingStep 收起展开", () => {
  it("挂载即收起（aria-expanded=false）；点击头部展开、再点收起", () => {
    render(<ThinkingStep node={makeNode()} running />);
    const toggle = screen.getByTestId("thinking-toggle");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    // 收起态内容体在 DOM（max-height/mask 由 CSS 驱动），行已渲染
    expect(screen.getByTestId("thinking-body")).toHaveTextContent("第一步分析问题");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
  });

  it("多卡同文档：内容体 id 唯一且各按钮 aria-controls 关联自己的内容体（BUG-21）", () => {
    render(
      <>
        <ThinkingStep node={makeNode()} running />
        <ThinkingStep node={makeNode({ stage: "retrieving" })} running={false} />
      </>,
    );
    const bodies = screen.getAllByTestId("thinking-body");
    const toggles = screen.getAllByTestId("thinking-toggle");
    expect(bodies).toHaveLength(2);
    // 两张卡的内容体 id 互不相同（HTML id 唯一性）
    expect(bodies[0].id).not.toBe(bodies[1].id);
    for (const [toggle, body] of toggles.map((t, i) => [t, bodies[i]] as const)) {
      // aria-controls 同源引用本卡内容体 id（读屏展开关联正确）
      expect(toggle).toHaveAttribute("aria-controls", body.id);
      expect(document.getElementById(toggle.getAttribute("aria-controls") ?? "")).toBe(body);
    }
  });
});

describe("ThinkingStep 运行/完成态", () => {
  it("运行态：shimmer「思考中」+ 步骤 running 类 + 末行 now 呼吸标记", () => {
    const { container } = render(<ThinkingStep node={makeNode()} running />);
    const step = screen.getByTestId("thinking-step");
    expect(step.className).toContain("chain-step--running");
    expect(screen.getByTestId("thinking-status").textContent).toBe("思考中");
    expect(container.querySelector(".shimmer-text")).not.toBeNull();
    // 末行（最新思考行）带 now 标记（进行中菱形 bullet 呼吸），首行不带
    const lines = container.querySelectorAll(".chain-tl");
    expect(lines).toHaveLength(2);
    expect(lines[0].className).not.toContain("chain-tl--now");
    expect(lines[1].className).toContain("chain-tl--now");
  });

  it("完成态（ended=true）：步骤 done+green 类 +「思考已完成」+ 无 now 行", () => {
    const { container } = render(<ThinkingStep node={makeNode({ ended: true })} running={false} />);
    const step = screen.getByTestId("thinking-step");
    expect(step.className).toContain("chain-step--done");
    expect(step.className).toContain("chain-step--green");
    expect(screen.getByTestId("thinking-status").textContent).toBe("思考已完成");
    expect(container.querySelector(".chain-tl--now")).toBeNull();
  });

  it("消息非流式但思考未收 ended（异常时序防御）：按完成态呈现", () => {
    render(<ThinkingStep node={makeNode({ ended: false })} running={false} />);
    expect(screen.getByTestId("thinking-step").className).toContain("chain-step--done");
  });
});
