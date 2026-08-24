/**
 * ChunkContextDrawer 分片上下文抽屉测试（Task 9 TDD 先行用例）
 *
 * 设计 §1.5.3：480px 右侧滑入，当前分片高亮卡居中，父章节卡在上、
 * prev/next 分片卡在下（时间线式连线）；空关联项（恒 null）不渲染节点；
 * 点击空白/关闭按钮/Esc 关闭。J4 上下文数据经 getChunkContext 拉取。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ChunkContextDrawer } from "./chunk-context-drawer";
import type { ChunkContext, MaterialChunk } from "@/lib/types";

/** 数据层 mock：J4 上下文查询按用例注入（成功/失败/挂起） */
const apiMock = vi.hoisted(() => ({ getChunkContext: vi.fn() }));

vi.mock("@/lib/api", () => ({
  getChunkContext: apiMock.getChunkContext,
}));

/** 完整上下文：父/前/后关联齐备 */
const FULL_CONTEXT: ChunkContext = {
  id: "chunk-2",
  docId: "doc-1",
  kbId: "kb-1",
  content: "当前分片内容全文",
  headingPath: "第一章 > 1.2 实现细节",
  chunkIndex: 2,
  courseId: "c-1",
  parentChunkId: "parent-1",
  prevChunkId: "chunk-1",
  nextChunkId: "chunk-3",
  parent: {
    id: "parent-1",
    content: "父章节聚合内容",
    headingPath: "第一章",
    chunkIndex: 1,
    parentTitle: "第一章 引言",
  },
  prev: {
    id: "chunk-1",
    content: "上一分片内容",
    headingPath: "第一章 > 1.1 基础概念",
    chunkIndex: 1,
    parentTitle: "第一章 引言",
  },
  next: {
    id: "chunk-3",
    content: "下一分片内容",
    headingPath: "第一章 > 1.3 进阶应用",
    chunkIndex: 3,
    parentTitle: "第一章 引言",
  },
};

/** 构造当前资料分片（J2 形态） */
function makeChunk(overrides: Partial<MaterialChunk> = {}): MaterialChunk {
  return {
    id: "chunk-2",
    content: "当前分片内容全文",
    headingPath: "第一章 > 1.2 实现细节",
    chunkIndex: 2,
    parentTitle: "第一章 引言",
    startPage: 3,
    endPage: 5,
    ...overrides,
  };
}

/** 渲染容器：独立 QueryClient（retry 关闭），用例间不共享缓存 */
function renderDrawer(chunk: MaterialChunk | null, onClose: () => void = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ChunkContextDrawer chunk={chunk} onClose={onClose} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  apiMock.getChunkContext.mockReset();
});

describe("ChunkContextDrawer 抽屉开关", () => {
  it("chunk 为 null 时完全不渲染（dialog 不存在）", () => {
    renderDrawer(null);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByText("分片上下文")).toBeNull();
  });

  it("打开抽屉：dialog 语义 + 关闭按钮 aria-label", () => {
    apiMock.getChunkContext.mockResolvedValue(FULL_CONTEXT);
    renderDrawer(makeChunk());
    const dialog = screen.getByRole("dialog", { name: "分片上下文" });
    expect(dialog).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "关闭" })).toBeInTheDocument();
  });

  it("点击关闭按钮触发 onClose", () => {
    apiMock.getChunkContext.mockResolvedValue(FULL_CONTEXT);
    const onClose = vi.fn();
    renderDrawer(makeChunk(), onClose);
    fireEvent.click(screen.getByRole("button", { name: "关闭" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("按 Esc 键触发 onClose", () => {
    apiMock.getChunkContext.mockResolvedValue(FULL_CONTEXT);
    const onClose = vi.fn();
    renderDrawer(makeChunk(), onClose);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("点击遮罩空白触发 onClose", () => {
    apiMock.getChunkContext.mockResolvedValue(FULL_CONTEXT);
    const onClose = vi.fn();
    renderDrawer(makeChunk(), onClose);
    fireEvent.click(screen.getByTestId("drawer-overlay"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe("ChunkContextDrawer 内容渲染", () => {
  it("当前分片高亮卡居中：内容 + 高亮样式 + 面包屑", async () => {
    apiMock.getChunkContext.mockResolvedValue(FULL_CONTEXT);
    renderDrawer(makeChunk());
    const current = await screen.findByTestId("drawer-current-card");
    expect(current).toHaveClass("border-brand/40", "bg-brand-light");
    expect(current).toHaveTextContent("当前分片内容全文");
    expect(current).toHaveTextContent("第一章");
  });

  it("加载后时间线：父章节在上、prev/next 在下、当前居中", async () => {
    apiMock.getChunkContext.mockResolvedValue(FULL_CONTEXT);
    renderDrawer(makeChunk());
    // 父章节卡：parentTitle 承载章节名
    expect(await screen.findByText("父章节")).toBeInTheDocument();
    expect(screen.getByText("上一分片")).toBeInTheDocument();
    expect(screen.getByText("下一分片")).toBeInTheDocument();
    // 关联分片内容与面包屑就位
    expect(screen.getByText("父章节聚合内容")).toBeInTheDocument();
    expect(screen.getByText("上一分片内容")).toBeInTheDocument();
    expect(screen.getByText("下一分片内容")).toBeInTheDocument();
    // 当前分片高亮卡并入时间线（父 → 当前 → prev/next 竖线串联）
    const current = screen.getByTestId("drawer-current-card");
    expect(current.closest('[data-testid="drawer-timeline"]')).not.toBeNull();
  });

  it("关联恒 null 时不渲染节点，展示空关联提示", async () => {
    const empty = { ...FULL_CONTEXT, parent: null, prev: null, next: null };
    apiMock.getChunkContext.mockResolvedValue(empty);
    renderDrawer(makeChunk());
    expect(await screen.findByText("该分片暂无上下文关联")).toBeInTheDocument();
    expect(screen.queryByText("父章节")).toBeNull();
    expect(screen.queryByText("上一分片")).toBeNull();
    expect(screen.queryByText("下一分片")).toBeNull();
    // 当前分片卡依然渲染
    expect(screen.getByTestId("drawer-current-card")).toBeInTheDocument();
  });

  it("加载中显示占位骨架", async () => {
    apiMock.getChunkContext.mockReturnValue(new Promise(() => {}));
    renderDrawer(makeChunk());
    expect(screen.getByTestId("drawer-skeleton")).toBeInTheDocument();
    expect(screen.getByTestId("drawer-current-card")).toBeInTheDocument();
  });

  it("加载失败显示重试，点击后恢复时间线", async () => {
    apiMock.getChunkContext
      .mockRejectedValueOnce(new Error("网络故障"))
      .mockResolvedValueOnce(FULL_CONTEXT);
    renderDrawer(makeChunk());
    expect(await screen.findByText("上下文加载失败，请重试")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("父章节")).toBeInTheDocument();
  });
});
