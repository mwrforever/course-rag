/**
 * 检索/工具步骤测试（2026-08-28 时间线改版：单行内容 + 光带 + 点击分流）
 *
 * 覆盖：运行态 shimmer+跳动点+光带 / 完成态文案+箭头 / 未完成点击 shake /
 * 完成点击回调（开抽屉）/ children 展开切换（工具详情）/ 人话映射与摘要截断
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { MagnifyingGlass, Wrench } from "@phosphor-icons/react";
import { OpStep, summarizeOutput, toolNameLabel } from "./op-step";

describe("工具辅助函数（原 ToolCallCard 能力并入）", () => {
  it("toolNameLabel：已收录映射中文，未收录原样回退", () => {
    expect(toolNameLabel("searchKnowledge")).toBe("检索课程知识库");
    expect(toolNameLabel("queryEnrollment")).toBe("查询报名信息");
    expect(toolNameLabel("customTool")).toBe("customTool");
  });

  it("summarizeOutput：JSON 序列化截断（超 80 字符加省略号）；undefined 兜底文案", () => {
    expect(summarizeOutput({ hits: 2 })).toBe('{"hits":2}');
    // 字符串经 JSON.stringify 带引号（102 字符）后按 80 截断
    const long = "x".repeat(100);
    expect(summarizeOutput(long)).toBe(`${JSON.stringify(long).slice(0, 80)}…`);
    expect(summarizeOutput(undefined)).toBe("（无输出）");
  });
});

describe("OpStep 运行/完成态", () => {
  it("运行态：shimmer 文案 + 三跳动点 + 光带；无完成文案", () => {
    const { container } = render(
      <OpStep
        running
        icon={<MagnifyingGlass weight="bold" />}
        ring="dash"
        loadingText="正在检索相关资料"
        doneContent="已检索 3 篇相关资料"
      />,
    );
    const step = screen.getByTestId("op-step");
    expect(step.className).toContain("chain-step--running");
    expect(container.querySelector(".shimmer-text")).toHaveTextContent("正在检索相关资料");
    expect(container.querySelectorAll(".chain-dots i")).toHaveLength(3);
    expect(container.querySelector(".chain-lightbar")).not.toBeNull();
    expect(screen.queryByTestId("op-step-text")).not.toBeInTheDocument();
  });

  it("完成态：完成文案 + 箭头滑入 + 光带隐藏类；无跳动点", () => {
    const { container } = render(
      <OpStep
        running={false}
        icon={<MagnifyingGlass weight="bold" />}
        ring="dash"
        loadingText="正在检索相关资料"
        doneContent={
          <>
            已检索 <em>3</em> 篇相关资料
          </>
        }
      />,
    );
    const step = screen.getByTestId("op-step");
    expect(step.className).toContain("chain-step--done");
    expect(screen.getByTestId("op-step-text").textContent).toBe("已检索 3 篇相关资料");
    expect(container.querySelector(".chain-op-go")).not.toBeNull();
    expect(container.querySelector(".chain-dots")).toBeNull();
  });
});

describe("OpStep 点击分流", () => {
  it("未完成点击：shake 抖动提示（不触发回调）", () => {
    const onClick = vi.fn();
    render(
      <OpStep
        running
        icon={<Wrench weight="fill" />}
        ring="arc"
        loadingText="工具执行中"
        doneContent="完成"
        onClick={onClick}
      />,
    );
    fireEvent.click(screen.getByTestId("op-step"));
    expect(screen.getByTestId("op-step").className).toContain("chain-step--shake");
    expect(onClick).not.toHaveBeenCalled();
  });

  it("完成点击：触发回调（检索步骤开抽屉语义）", () => {
    const onClick = vi.fn();
    render(
      <OpStep
        running={false}
        icon={<MagnifyingGlass weight="bold" />}
        ring="dash"
        loadingText="正在检索相关资料"
        doneContent="已检索 3 篇相关资料"
        onClick={onClick}
      />,
    );
    fireEvent.click(screen.getByTestId("op-step"));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("完成点击（children 模式）：切换内嵌详情展开（工具完整 JSON）", () => {
    render(
      <OpStep
        running={false}
        icon={<Wrench weight="fill" />}
        ring="arc"
        loadingText="工具执行中"
        doneContent="检索课程知识库 · 完成"
      >
        <pre>{'{"hits": 2}'}</pre>
      </OpStep>,
    );
    expect(screen.queryByTestId("op-step-detail")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("op-step"));
    expect(screen.getByTestId("op-step-detail")).toHaveTextContent('{"hits": 2}');
    fireEvent.click(screen.getByTestId("op-step"));
    expect(screen.queryByTestId("op-step-detail")).not.toBeInTheDocument();
  });

  it("无回调无详情的完成步骤：不可点击（无 role=button）", () => {
    render(
      <OpStep
        running={false}
        icon={<Wrench weight="fill" />}
        ring="arc"
        loadingText="工具执行中"
        doneContent="静态完成文案"
      />,
    );
    expect(screen.getByTestId("op-step")).not.toHaveAttribute("role");
  });
});
