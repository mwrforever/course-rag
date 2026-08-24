/**
 * 来源卡组测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 SourcesList）：
 * - 标题行「参考来源」+ 来源卡（文档图标 + docTitle + headingPath 双行）
 * - score 置信条 0-1 青色填充（宽度 = score×100%，等宽数字展示百分比）
 * - 点击复制来源引用文本（无跳转目标）+ toast
 * - 来源卡组 stagger 滑入（animationDelay 逐卡递增，motion-reduce 降级）
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SourcesList, sourceCopyText } from "./sources-list";
import type { RetrievalSource } from "@/lib/types";

const onNotify = vi.fn();
let clipboardWriteText: ReturnType<typeof vi.fn>;

const SOURCES: RetrievalSource[] = [
  { chunkId: "c-1", docTitle: "RAG 白皮书", headingPath: "第三章 > 3.1 混合检索", score: 0.86 },
  { chunkId: "c-2", docTitle: "课程讲义", headingPath: "第一章", score: 0.35 },
];

function stubClipboard() {
  clipboardWriteText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText: clipboardWriteText },
    configurable: true,
  });
}

function renderSources(items: RetrievalSource[] = SOURCES) {
  return render(<SourcesList sources={items} onNotify={onNotify} />);
}

beforeEach(() => {
  onNotify.mockReset();
  stubClipboard();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("sourceCopyText 复制文本", () => {
  it("组装「标题（面包屑）」引用串", () => {
    expect(sourceCopyText(SOURCES[0])).toBe("RAG 白皮书（第三章 > 3.1 混合检索）");
  });
});

describe("SourcesList 渲染", () => {
  it("标题行 + 每张来源卡的双行信息（docTitle + headingPath）", () => {
    renderSources();
    expect(screen.getByText("参考来源")).toBeInTheDocument();
    expect(screen.getByText("RAG 白皮书")).toBeInTheDocument();
    expect(screen.getByText("第三章 > 3.1 混合检索")).toBeInTheDocument();
    expect(screen.getByText("课程讲义")).toBeInTheDocument();
  });

  it("score 置信条：宽度按 score 百分比青色填充 + 等宽百分比文本", () => {
    renderSources();
    const bars = screen.getAllByTestId("score-bar");
    expect(bars).toHaveLength(2);
    const fills = screen.getAllByTestId("score-fill");
    expect(fills[0]).toHaveStyle({ width: "86%" });
    expect(fills[1]).toHaveStyle({ width: "35%" });
    expect(screen.getByText("86%")).toBeInTheDocument();
    expect(screen.getByText("35%")).toBeInTheDocument();
  });

  it("score 越界钳制：>1 按 100%、<0 按 0%", () => {
    const wild: RetrievalSource[] = [
      { chunkId: "c-3", docTitle: "越界文档", headingPath: "x", score: 1.5 },
      { chunkId: "c-4", docTitle: "负数文档", headingPath: "y", score: -0.2 },
    ];
    renderSources(wild);
    const fills = screen.getAllByTestId("score-fill");
    expect(fills[0]).toHaveStyle({ width: "100%" });
    expect(fills[1]).toHaveStyle({ width: "0%" });
  });

  it("stagger 滑入：逐卡 animationDelay 递增 + reduced-motion 降级类", () => {
    renderSources();
    const cards = screen.getAllByTestId("source-card");
    expect(cards[0].style.animationDelay).toBe("0ms");
    expect(cards[1].style.animationDelay).toBe("60ms");
    cards.forEach((card) => {
      expect(card.className).toContain("motion-reduce:animate-none");
    });
  });

  it("点击来源卡：复制引用文本 + toast「已复制来源」（无跳转目标）", async () => {
    renderSources();
    fireEvent.click(screen.getByText("RAG 白皮书"));
    await vi.waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith("RAG 白皮书（第三章 > 3.1 混合检索）");
    });
    expect(onNotify).toHaveBeenCalledWith("已复制来源");
  });
});
