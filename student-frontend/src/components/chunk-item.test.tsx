/**
 * ChunkItem 资料分片卡测试（Task 9 TDD 先行用例）
 *
 * 设计 §1.5.3：headingPath 面包屑（stone-500 小字）+ 内容 3 行截断
 * + 页码区间 badge（startPage-endPage，等宽字体）+ [查看上下文]。
 * 另测 splitHeadingPath 兼容 " > " 与 "/" 两种分隔风格（ETL 组装与 VO 注释并存）。
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ChunkBreadcrumb, ChunkItem, splitHeadingPath } from "./chunk-item";
import type { MaterialChunk } from "@/lib/types";

/** 构造资料分片（J2 字段形态可覆盖） */
function makeChunk(overrides: Partial<MaterialChunk> = {}): MaterialChunk {
  return {
    id: "chunk-1",
    content: "分片正文：算法复杂度分析是评估程序效率的核心方法。",
    headingPath: "第一章 > 1.1 复杂度分析",
    chunkIndex: 1,
    parentTitle: "第一章 引言",
    startPage: 3,
    endPage: 5,
    ...overrides,
  };
}

describe("splitHeadingPath 面包屑拆分", () => {
  it("兼容「 > 」与「 / 」两种分隔风格并去除空段", () => {
    expect(splitHeadingPath("第一章 > 1.1 小节")).toEqual(["第一章", "1.1 小节"]);
    expect(splitHeadingPath("第一章/1.1 小节")).toEqual(["第一章", "1.1 小节"]);
    expect(splitHeadingPath(" 第一章 > 1.1 > 细节  ")).toEqual(["第一章", "1.1", "细节"]);
    expect(splitHeadingPath("单一标题")).toEqual(["单一标题"]);
  });
});

describe("ChunkBreadcrumb 面包屑", () => {
  it("拆分渲染各段并以分隔符连接", () => {
    render(<ChunkBreadcrumb path="第一章 > 1.1 复杂度分析" />);
    const breadcrumb = screen.getByTestId("chunk-breadcrumb");
    expect(breadcrumb).toHaveTextContent("第一章");
    expect(breadcrumb).toHaveTextContent("1.1 复杂度分析");
  });

  it("path 为 null 或空时不渲染", () => {
    const { rerender } = render(<ChunkBreadcrumb path={null} />);
    expect(screen.queryByTestId("chunk-breadcrumb")).toBeNull();
    rerender(<ChunkBreadcrumb path="   " />);
    expect(screen.queryByTestId("chunk-breadcrumb")).toBeNull();
  });
});

describe("ChunkItem 资料分片卡", () => {
  it("渲染面包屑、内容（3 行截断）与页码区间 badge", () => {
    render(<ChunkItem chunk={makeChunk()} onViewContext={vi.fn()} />);
    expect(screen.getByTestId("chunk-breadcrumb")).toHaveTextContent("第一章");
    expect(screen.getByText(/算法复杂度分析/)).toHaveClass("line-clamp-3");
    // 页码区间 badge：等宽字体承载数字
    const badge = screen.getByText("第 3-5 页");
    expect(badge).toHaveClass("font-mono");
  });

  it("页码同页时显示单页，无页码（null）不渲染 badge", () => {
    const { rerender } = render(
      <ChunkItem chunk={makeChunk({ endPage: 3 })} onViewContext={vi.fn()} />,
    );
    expect(screen.getByText("第 3 页")).toBeInTheDocument();
    rerender(
      <ChunkItem chunk={makeChunk({ startPage: null, endPage: null })} onViewContext={vi.fn()} />,
    );
    expect(screen.queryByText(/第 .* 页/)).toBeNull();
  });

  it("headingPath 为 null 时省略面包屑行", () => {
    render(<ChunkItem chunk={makeChunk({ headingPath: null })} onViewContext={vi.fn()} />);
    expect(screen.queryByTestId("chunk-breadcrumb")).toBeNull();
  });

  it("点击查看上下文回调携带当前分片对象", () => {
    const onViewContext = vi.fn();
    const chunk = makeChunk();
    render(<ChunkItem chunk={chunk} onViewContext={onViewContext} />);
    fireEvent.click(screen.getByRole("button", { name: "查看上下文" }));
    expect(onViewContext).toHaveBeenCalledWith(chunk);
  });
});
