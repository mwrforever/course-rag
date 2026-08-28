/**
 * 知识库召回抽屉测试（2026-08-27 C 端改版；2026-08-28 Task 11 对齐设计稿 chunk 卡）
 *
 * 覆盖：
 * - sources=null 不渲染；非 null 挂载（空数组渲染空态）
 * - chunk 卡：类型徽标（知识库）/ docTitle / headingPath 面包屑 / 相似度百分比 /
 *   content 正文（3 行截断类）+ meter（进场 0% → 延迟后填充目标值）+ 错峰进场延迟
 * - 点击卡片切换展开全文（line-clamp 类切换 + 提示文案 收起/展开全文）
 * - 存量数据无 content：降级占位「（片段内容暂不可用）」
 * - 关闭路径：关闭按钮 / Esc / 遮罩点击（三路径保留）
 * - score 越界钳制（>1 / <0 归一）
 */
import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

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

afterEach(() => {
  vi.useRealTimers();
});

describe("RetrievalDrawer 挂载与列表", () => {
  it("sources=null 不渲染任何节点", () => {
    const { container } = render(<RetrievalDrawer sources={null} onClose={vi.fn()} />);
    expect(container.firstChild).toBeNull();
  });

  it("chunk 卡按序渲染：类型徽标/标题/面包屑/相似度百分比/正文", () => {
    render(<RetrievalDrawer sources={SOURCES} onClose={vi.fn()} />);
    expect(screen.getByTestId("retrieval-drawer")).toBeInTheDocument();
    const items = screen.getAllByTestId("retrieval-source-item");
    expect(items).toHaveLength(2);
    expect(screen.getAllByText("知识库")).toHaveLength(2);
    expect(screen.getByText("RAG 白皮书")).toBeInTheDocument();
    expect(screen.getByText("第三章 > 3.2")).toBeInTheDocument();
    expect(screen.getByText("相似度 87%")).toBeInTheDocument();
    expect(screen.getByText("相似度 42%")).toBeInTheDocument();
    expect(screen.getByText("召回片段正文预览一")).toBeInTheDocument();
    // 默认收起：3 行截断类 + 展开提示；错峰进场延迟随下标递增
    expect(items[0]).not.toHaveClass("chunk-card--exp");
    expect(screen.getAllByText("展开全文")).toHaveLength(2);
    expect(items[0].style.animationDelay).toBe("0ms");
    expect(items[1].style.animationDelay).toBe("85ms");
  });

  it("meter 进场动画：挂载 0%，120ms 后填充到目标百分比（width 1s 过渡由 CSS 承担）", () => {
    vi.useFakeTimers();
    render(<RetrievalDrawer sources={SOURCES} onClose={vi.fn()} />);
    const meters = screen.getAllByTestId("retrieval-source-meter");
    expect(meters[0].style.width).toBe("0%");
    expect(meters[1].style.width).toBe("0%");
    // 推进进场延迟计时器（120ms 置位 + React 提交在 act 内完成）
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(meters[0].style.width).toBe("87%");
    expect(meters[1].style.width).toBe("42%");
    // meter 填充错峰：第二张卡带 85ms 填充延迟
    expect(meters[1].style.transitionDelay).toBe("85ms");
  });

  it("点击卡片切换展开全文（line-clamp 类 + 提示文案）；键盘 Enter 同路径", () => {
    render(<RetrievalDrawer sources={SOURCES} onClose={vi.fn()} />);
    const card = screen.getAllByTestId("retrieval-source-item")[0];
    expect(card).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(card);
    expect(card).toHaveClass("chunk-card--exp");
    expect(card).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("收起")).toBeInTheDocument();
    // 再点收起
    fireEvent.click(card);
    expect(card).not.toHaveClass("chunk-card--exp");
    // 键盘路径：Enter 切换
    fireEvent.keyDown(card, { key: "Enter" });
    expect(card).toHaveClass("chunk-card--exp");
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
    expect(screen.getByText("相似度 100%")).toBeInTheDocument();
    expect(screen.getByText("相似度 0%")).toBeInTheDocument();
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
