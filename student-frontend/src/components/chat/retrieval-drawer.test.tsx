/**
 * 知识库召回抽屉测试（2026-08-27 C 端改版；2026-08-28 Task 11 对齐设计稿 chunk 卡；
 * 2026-08-30 懒加载改版）
 *
 * 覆盖：
 * - sources=null 不渲染；非 null 挂载（空数组渲染空态）
 * - chunk 卡：类型徽标（知识库）/ docTitle / headingPath 面包屑 / 相似度百分比 /
 *   meter（进场 0% → 延迟后填充目标值）+ 错峰进场延迟
 * - 点击卡片展开 → 按 chunkId 懒加载回查 PG（loading → 全文）；再点收起
 * - 懒加载失败降级：403「未选此课程，无权查看该片段」/ 其他「片段加载失败」
 * - 收起态不渲染正文（内容不再随 SOURCES 一次性下发）
 * - 关闭路径：关闭按钮 / Esc / 遮罩点击（三路径保留）
 * - score 越界钳制（>1 / <0 归一）
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ getChunkContext: vi.fn() }));
vi.mock("@/lib/api", async (importOriginal) => {
  // 保留真实 ApiError 导出（组件 instanceof 判断用），仅替换 getChunkContext 为可控 mock
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, getChunkContext: apiMock.getChunkContext };
});

import { RetrievalDrawer } from "./retrieval-drawer";
import { ApiError } from "@/lib/api";
import type { RetrievalSource } from "@/lib/types";

const SOURCES: RetrievalSource[] = [
  {
    chunkId: "c-1",
    docTitle: "RAG 白皮书",
    headingPath: "第三章 > 3.2",
    score: 0.87,
  },
  {
    chunkId: "c-2",
    docTitle: "高等数学讲义",
    headingPath: "第一章",
    score: 0.42,
  },
];

/** 渲染容器：独立 QueryClient（retry 关闭），用例间不共享缓存 */
function renderDrawer(sources: RetrievalSource[] | null, onClose: () => void = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <RetrievalDrawer sources={sources} onClose={onClose} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  apiMock.getChunkContext.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("RetrievalDrawer 挂载与列表", () => {
  it("sources=null 不渲染任何节点", () => {
    const { container } = renderDrawer(null);
    expect(container.firstChild).toBeNull();
  });

  it("chunk 卡按序渲染：类型徽标/标题/面包屑/相似度百分比（收起态无正文）", () => {
    renderDrawer(SOURCES);
    expect(screen.getByTestId("retrieval-drawer")).toBeInTheDocument();
    const items = screen.getAllByTestId("retrieval-source-item");
    expect(items).toHaveLength(2);
    expect(screen.getAllByText("知识库")).toHaveLength(2);
    expect(screen.getByText("RAG 白皮书")).toBeInTheDocument();
    expect(screen.getByText("第三章 > 3.2")).toBeInTheDocument();
    expect(screen.getByText("相似度 87%")).toBeInTheDocument();
    expect(screen.getByText("相似度 42%")).toBeInTheDocument();
    // 2026-08-30 懒加载：内容不再随 SOURCES 下发，收起态不渲染正文、不发起回查
    expect(screen.queryByTestId("retrieval-source-text")).not.toBeInTheDocument();
    expect(apiMock.getChunkContext).not.toHaveBeenCalled();
    // 默认收起：展开提示；错峰进场延迟随下标递增
    expect(items[0]).not.toHaveClass("chunk-card--exp");
    expect(screen.getAllByText("展开全文")).toHaveLength(2);
    expect(items[0].style.animationDelay).toBe("0ms");
    expect(items[1].style.animationDelay).toBe("85ms");
  });

  it("meter 进场动画：挂载 0%，120ms 后填充到目标百分比（width 1s 过渡由 CSS 承担）", () => {
    vi.useFakeTimers();
    renderDrawer(SOURCES);
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

  it("点击卡片展开：按 chunkId 懒加载回查 PG 拉全文（loading → 全文）；再点收起", async () => {
    apiMock.getChunkContext.mockResolvedValue({
      id: "c-1",
      docId: "d-1",
      kbId: "kb-1",
      content: "召回片段全文（按 id 回查 PG 获取）",
      headingPath: "第三章 > 3.2",
      chunkIndex: 2,
      courseId: null,
      parentChunkId: null,
      prevChunkId: null,
      nextChunkId: null,
      parent: null,
      prev: null,
      next: null,
    });
    renderDrawer(SOURCES);
    const card = screen.getAllByTestId("retrieval-source-item")[0];
    expect(card).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(card);
    expect(card).toHaveClass("chunk-card--exp");
    expect(card).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("收起")).toBeInTheDocument();
    // 懒加载回查：仅展开的卡片按 chunkId 请求
    expect(apiMock.getChunkContext).toHaveBeenCalledTimes(1);
    expect(apiMock.getChunkContext).toHaveBeenCalledWith("c-1");
    // 等待查询完成渲染全文
    await waitFor(() => {
      expect(screen.getByTestId("retrieval-source-text")).toHaveTextContent(
        "召回片段全文（按 id 回查 PG 获取）",
      );
    });
    // 再点收起：不重复请求（staleTime 缓存），正文卸载
    fireEvent.click(card);
    expect(card).not.toHaveClass("chunk-card--exp");
    expect(screen.queryByTestId("retrieval-source-text")).not.toBeInTheDocument();
    expect(apiMock.getChunkContext).toHaveBeenCalledTimes(1);
  });

  it("懒加载失败降级：403 → 「未选此课程，无权查看该片段」；其他错误 → 「片段加载失败」", async () => {
    apiMock.getChunkContext
      .mockRejectedValueOnce(new ApiError(403, "未选此课程，无权查看资料"))
      .mockRejectedValueOnce(new ApiError(500, "内部错误"));
    renderDrawer(SOURCES);
    const cards = screen.getAllByTestId("retrieval-source-item");
    // 卡片一：403（无权查看）
    fireEvent.click(cards[0]);
    await waitFor(() => {
      expect(within(cards[0]).getByTestId("retrieval-source-text")).toHaveTextContent(
        "未选此课程，无权查看该片段",
      );
    });
    // 卡片二：其他错误（加载失败）
    fireEvent.click(cards[1]);
    await waitFor(() => {
      expect(within(cards[1]).getByTestId("retrieval-source-text")).toHaveTextContent(
        "片段加载失败，请重试",
      );
    });
  });

  it("空来源：空态文案（未引用知识库片段）", () => {
    renderDrawer([]);
    expect(screen.getByText("本轮回答未引用知识库片段")).toBeInTheDocument();
  });

  it("score 越界钳制：1.5 → 100%、-0.2 → 0%", () => {
    renderDrawer([
      { chunkId: "c-4", docTitle: "A", headingPath: "", score: 1.5 },
      { chunkId: "c-5", docTitle: "B", headingPath: "", score: -0.2 },
    ]);
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
    renderDrawer(SOURCES, onClose);
    act();
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
