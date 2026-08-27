/**
 * 知识库召回抽屉测试（2026-08-27 C 端改版）
 *
 * 覆盖：
 * - sources=null 不渲染；非 null 挂载（空数组渲染空态）
 * - 片段列表项：序号徽标 / docTitle / headingPath / 相关度百分比 / content 正文
 * - 存量数据无 content：降级占位「（片段内容暂不可用）」
 * - 关闭路径：关闭按钮 / Esc / 遮罩点击
 * - score 越界钳制（>1 / <0 归一）
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { RetrievalDrawer } from "./retrieval-drawer";
import type { RetrievalSource } from "@/lib/types";

const SOURCES: RetrievalSource[] = [
  {
    chunkId: "c-1",
    docTitle: "RAG 白皮书",
    headingPath: "第三章 > 3.2",
    score: 0.87,
    content: "召回片段正文预览一",
  },
  {
    chunkId: "c-2",
    docTitle: "高等数学讲义",
    headingPath: "第一章",
    score: 0.42,
    content: "召回片段正文预览二",
  },
];

describe("RetrievalDrawer 挂载与列表", () => {
  it("sources=null 不渲染任何节点", () => {
    const { container } = render(<RetrievalDrawer sources={null} onClose={vi.fn()} />);
    expect(container.firstChild).toBeNull();
  });

  it("片段列表按序渲染：标题/面包屑/百分比/正文", () => {
    render(<RetrievalDrawer sources={SOURCES} onClose={vi.fn()} />);
    expect(screen.getByTestId("retrieval-drawer")).toBeInTheDocument();
    const items = screen.getAllByTestId("retrieval-source-item");
    expect(items).toHaveLength(2);
    expect(screen.getByText("RAG 白皮书")).toBeInTheDocument();
    expect(screen.getByText("第三章 > 3.2")).toBeInTheDocument();
    expect(screen.getByText("87%")).toBeInTheDocument();
    expect(screen.getByText("42%")).toBeInTheDocument();
    expect(screen.getByText("召回片段正文预览一")).toBeInTheDocument();
  });

  it("空来源：空态文案（未引用知识库片段）", () => {
    render(<RetrievalDrawer sources={[]} onClose={vi.fn()} />);
    expect(screen.getByText("本轮回答未引用知识库片段")).toBeInTheDocument();
  });

  it("存量数据无 content 字段：降级占位文案", () => {
    render(
      <RetrievalDrawer
        sources={[{ chunkId: "c-3", docTitle: "旧讲义", headingPath: "二", score: 0.5 }]}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByText("（片段内容暂不可用）")).toBeInTheDocument();
  });

  it("score 越界钳制：1.5 → 100%、-0.2 → 0%", () => {
    render(
      <RetrievalDrawer
        sources={[
          { chunkId: "c-4", docTitle: "A", headingPath: "", score: 1.5, content: "" },
          { chunkId: "c-5", docTitle: "B", headingPath: "", score: -0.2, content: "" },
        ]}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByText("100%")).toBeInTheDocument();
    expect(screen.getByText("0%")).toBeInTheDocument();
  });
});

describe("RetrievalDrawer 关闭路径", () => {
  it.each([
    ["关闭按钮", () => fireEvent.click(screen.getByRole("button", { name: "关闭召回抽屉" }))],
    ["Esc 键", () => fireEvent.keyDown(window, { key: "Escape" })],
    ["遮罩点击", () => fireEvent.click(screen.getByTestId("retrieval-drawer-overlay"))],
  ])("%s 触发 onClose", (_name, act) => {
    const onClose = vi.fn();
    render(<RetrievalDrawer sources={SOURCES} onClose={onClose} />);
    act();
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
