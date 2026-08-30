/**
 * 链式时间轴容器测试（2026-08-28 时间线改版：节点种类映射 + 运行态推导 + 交互；
 * 2026-08-30 对齐设计稿：移除 stage/query_plan 节点，工具步骤改为点击开结果抽屉）
 *
 * 覆盖：四类节点到步骤组件的映射与顺序 / isNodeRunning 末节点运行态推导 /
 * sources 步骤点击开召回抽屉 / tool 步骤完成态点击开工具结果抽屉
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ChainTimeline, isNodeRunning } from "./chain-timeline";
import type { RetrievalSource, TimelineNode } from "@/lib/types";

const SOURCE: RetrievalSource = {
  chunkId: "c-1",
  docTitle: "RAG 白皮书",
  headingPath: "第三章",
  score: 0.9,
};

/** 全类型时间轴样本（到达序；2026-08-30：无 stage/queryPlan 节点） */
const FULL_TIMELINE: TimelineNode[] = [
  { kind: "thinking", stage: "understanding", lines: ["理解问题"], ended: true },
  { kind: "sources", sources: [SOURCE] },
  { kind: "thinking", stage: "generating", lines: ["组织回答"], ended: true },
  {
    kind: "tool",
    toolCallId: "t-1",
    toolName: "searchKnowledge",
    input: null,
    status: "success",
    output: { hits: 2 },
  },
];

describe("isNodeRunning 运行态推导", () => {
  const think: TimelineNode = { kind: "thinking", stage: "generating", lines: [], ended: false };
  const tool: TimelineNode = {
    kind: "tool",
    toolCallId: "t",
    toolName: "n",
    input: null,
    status: "pending",
    output: null,
  };

  it("非流式（历史消息）或非末节点恒为完成态", () => {
    expect(isNodeRunning(think, true, false)).toBe(false);
    expect(isNodeRunning(think, false, true)).toBe(false);
  });

  it("末节点 + 流式：thinking 看 ended、tool 看 pending、sources 进行中", () => {
    expect(isNodeRunning({ ...think, ended: true }, true, true)).toBe(false);
    expect(isNodeRunning(think, true, true)).toBe(true);
    expect(isNodeRunning(tool, true, true)).toBe(true);
    expect(isNodeRunning({ ...tool, status: "success" }, true, true)).toBe(false);
    expect(isNodeRunning({ kind: "sources", sources: [] }, true, true)).toBe(true);
  });
});

describe("ChainTimeline 节点映射与顺序", () => {
  it("四类节点按到达序渲染为对应步骤组件（无 stage/queryPlan 步骤）", () => {
    const { container } = render(
      <ChainTimeline
        timeline={FULL_TIMELINE}
        active={false}
        onOpenSources={() => {}}
        onOpenTool={() => {}}
      />,
    );
    // 步骤顺序 = 节点顺序（chain 下 4 个步骤）
    const steps = container.querySelectorAll(".chain-step");
    expect(steps).toHaveLength(4);
    // 各类步骤均在：思考×2 / 来源（已检索 1 篇）/ 工具（sources/tool 用专用 testid）
    expect(screen.getAllByTestId("thinking-step")).toHaveLength(2);
    expect(screen.queryByTestId("query-plan-step")).not.toBeInTheDocument();
    expect(screen.queryByTestId("op-step")).not.toBeInTheDocument();
    expect(screen.getByTestId("sources-step")).toHaveTextContent("已检索");
    expect(screen.getByTestId("tool-step")).toBeInTheDocument();
    // 历史消息（active=false）：全部完成态，无运行类
    expect(container.querySelector(".chain-step--running")).toBeNull();
  });

  it("流式末节点呈现运行态（tool pending：shimmer + 跳动点）", () => {
    const timeline: TimelineNode[] = [
      ...FULL_TIMELINE.slice(0, 3),
      {
        kind: "tool",
        toolCallId: "t-2",
        toolName: "searchKnowledge",
        input: null,
        status: "pending",
        output: null,
      },
    ];
    const { container } = render(
      <ChainTimeline timeline={timeline} active onOpenSources={() => {}} onOpenTool={() => {}} />,
    );
    const steps = container.querySelectorAll(".chain-step");
    // 末位工具节点 running，其余全部完成
    expect(steps[steps.length - 1].className).toContain("chain-step--running");
    expect(container.querySelectorAll(".chain-step--running")).toHaveLength(1);
    expect(container.querySelector(".shimmer-text")).toHaveTextContent("检索课程知识库 执行中");
  });
});

describe("ChainTimeline 交互", () => {
  it("sources 步骤点击触发 onOpenSources（开召回抽屉）", () => {
    const onOpenSources = vi.fn();
    render(
      <ChainTimeline
        timeline={FULL_TIMELINE}
        active={false}
        onOpenSources={onOpenSources}
        onOpenTool={() => {}}
      />,
    );
    // 检索步骤（已检索 1 篇相关资料）点击开抽屉
    fireEvent.click(screen.getByTestId("sources-step"));
    expect(onOpenSources).toHaveBeenCalledTimes(1);
  });

  it("tool 步骤完成态点击触发 onOpenTool（开工具结果抽屉，2026-08-30 替代内嵌 JSON）", () => {
    const onOpenTool = vi.fn();
    render(
      <ChainTimeline
        timeline={FULL_TIMELINE}
        active={false}
        onOpenSources={() => {}}
        onOpenTool={onOpenTool}
      />,
    );
    fireEvent.click(screen.getByTestId("tool-step"));
    expect(onOpenTool).toHaveBeenCalledTimes(1);
    expect(onOpenTool).toHaveBeenCalledWith(
      expect.objectContaining({ kind: "tool", toolCallId: "t-1", status: "success" }),
    );
  });
});
