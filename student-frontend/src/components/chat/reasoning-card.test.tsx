/**
 * 推理过程卡测试（2026-08-27 C 端改版：阶段进度 + 思考流 + 知识片段入口）
 *
 * 覆盖：
 * - 默认收起（用户拍板）：挂载即折叠态，头部点击切换展开
 * - 生成中：spinner + 当前阶段 label（如「知识库查询中」）；结束后静态「已深度思考」
 * - 展开后：阶段清单（完成项打勾/进行中 spinner）+ 思考逐行渲染
 * - 收起态：最新思考行预览（截断）
 * - 知识片段 pill：有来源且提供回调时渲染，点击转发
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ReasoningCard, splitThinkingLines } from "./reasoning-card";
import type { ChatStage } from "@/lib/types";

const STAGES: ChatStage[] = [
  { stage: "understanding", label: "正在理解你的问题" },
  { stage: "retrieving", label: "知识库查询中" },
];

describe("splitThinkingLines 思考行拆分", () => {
  it("按换行拆分并过滤空行（保留行尾空白裁剪）", () => {
    expect(splitThinkingLines("第一行\n\n第二行  \n第三行")).toEqual([
      "第一行",
      "第二行",
      "第三行",
    ]);
  });
  it("空文本返回空数组", () => {
    expect(splitThinkingLines("")).toEqual([]);
    expect(splitThinkingLines("\n\n")).toEqual([]);
  });
});

describe("ReasoningCard 默认收起与展开", () => {
  it("挂载即折叠（aria-expanded=false），内容区不显示思考全文", () => {
    render(
      <ReasoningCard
        stages={STAGES}
        thinking={"思考第一行\n思考第二行"}
        thinkingEnded={false}
        active
        sources={[]}
      />,
    );
    expect(screen.getByTestId("reasoning-toggle")).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByTestId("reasoning-content")).toBeInTheDocument();
    // 收起态渲染末行预览而非全文列表
    expect(screen.getByTestId("reasoning-preview")).toHaveTextContent("思考第二行");
  });

  it("头部点击展开：阶段清单 + 思考逐行渲染；再次点击收起", () => {
    render(
      <ReasoningCard
        stages={STAGES}
        thinking={"思考第一行\n思考第二行"}
        thinkingEnded={false}
        active
        sources={[]}
      />,
    );
    fireEvent.click(screen.getByTestId("reasoning-toggle"));
    expect(screen.getByTestId("reasoning-toggle")).toHaveAttribute("aria-expanded", "true");
    // 阶段清单两项（末项进行中）——头部 label 同文重复，用清单内计数断言
    const list = screen.getByLabelText("回答进度");
    expect(list.querySelectorAll("li")).toHaveLength(2);
    expect(within(list).getByText("正在理解你的问题")).toBeInTheDocument();
    expect(within(list).getByText("知识库查询中")).toBeInTheDocument();
    // 思考逐行：两行分别渲染
    expect(screen.getByText("思考第一行")).toBeInTheDocument();
    expect(screen.getByText("思考第二行")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("reasoning-toggle"));
    expect(screen.getByTestId("reasoning-toggle")).toHaveAttribute("aria-expanded", "false");
  });
});

describe("ReasoningCard 阶段状态与片段入口", () => {
  it("生成中：spinner + 当前阶段 label（末条阶段即进行中）", () => {
    render(<ReasoningCard stages={STAGES} thinking="" thinkingEnded={false} active sources={[]} />);
    expect(screen.getByTestId("reasoning-spinner")).toBeInTheDocument();
    expect(screen.getByTestId("reasoning-label")).toHaveTextContent("知识库查询中");
  });

  it("无阶段事件的流式空窗：label 兜底「正在准备…」", () => {
    render(<ReasoningCard stages={[]} thinking="" thinkingEnded={false} active sources={[]} />);
    expect(screen.getByTestId("reasoning-label")).toHaveTextContent("正在准备…");
  });

  it("结束后：静态徽点 +「已深度思考」，无 spinner", () => {
    render(
      <ReasoningCard stages={STAGES} thinking="想完了" thinkingEnded active={false} sources={[]} />,
    );
    expect(screen.queryByTestId("reasoning-spinner")).not.toBeInTheDocument();
    expect(screen.getByTestId("reasoning-label")).toHaveTextContent("已深度思考");
  });

  it("有来源时渲染「N 个知识片段」pill 并转发点击；无来源/无回调不渲染", () => {
    const onOpen = vi.fn();
    const sources = [
      { chunkId: "c1", docTitle: "讲义", headingPath: "一", score: 0.9, content: "片段" },
    ];
    const { rerender } = render(
      <ReasoningCard
        stages={[]}
        thinking=""
        thinkingEnded
        active
        sources={sources}
        onOpenSources={onOpen}
      />,
    );
    const pill = screen.getByTestId("reasoning-sources-pill");
    expect(pill).toHaveTextContent("1 个知识片段");
    fireEvent.click(pill);
    expect(onOpen).toHaveBeenCalledTimes(1);
    rerender(
      <ReasoningCard
        stages={[]}
        thinking=""
        thinkingEnded
        active={false}
        sources={[]}
        onOpenSources={onOpen}
      />,
    );
    expect(screen.queryByTestId("reasoning-sources-pill")).not.toBeInTheDocument();
  });
});
