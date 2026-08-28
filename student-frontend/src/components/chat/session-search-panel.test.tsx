/**
 * 会话搜索浮层面板测试（Task 13 TDD 先行用例）
 *
 * 覆盖（搜索框 focus 弹出浮层 + keyword 防抖查询 + 跳转）：
 * - 聚焦搜索框 → 浮层打开并发起列表查询（首屏全量第一页）
 * - 输入关键词 → 防抖 300ms 后按 keyword 请求 /sessions
   - 结果列表 max-h-72 滚动容器；空结果 → 空态文案
 * - 点击结果 → 跳转 /chat/{id} 且浮层关闭
 * - Esc / 点击面板外 → 浮层关闭
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionSearchPanel } from "./session-search-panel";
import type { SessionItem } from "@/lib/types";

/** 数据层 mock：getSessions 按 keyword 返回 */
const apiMock = vi.hoisted(() => ({ getSessions: vi.fn() }));
/** 导航 mock：push 记录跳转 */
const navMock = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, getSessions: apiMock.getSessions };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: navMock.push }),
}));

/** 构造会话条目 */
function makeSession(overrides: Partial<SessionItem> = {}): SessionItem {
  return {
    id: "s-1",
    title: "RAG 入门咨询",
    status: "ACTIVE",
    createdAt: "2026-08-24T09:20:00",
    lastMessageAt: null,
    ...overrides,
  };
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionSearchPanel />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  apiMock.getSessions.mockReset();
  navMock.push.mockReset();
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("SessionSearchPanel 打开与查询", () => {
  it("聚焦搜索框：浮层打开并查询全量第一页（无 keyword）", async () => {
    apiMock.getSessions.mockResolvedValue({
      records: [makeSession()],
      total: "1",
      page: 1,
      size: 20,
    });
    renderPanel();
    expect(screen.queryByTestId("session-search-dropdown")).not.toBeInTheDocument();
    fireEvent.focus(screen.getByTestId("sidebar-session-search"));
    expect(screen.getByTestId("session-search-dropdown")).toBeInTheDocument();
    await waitFor(() => {
      expect(apiMock.getSessions).toHaveBeenCalledWith(1, 20, undefined);
    });
    expect(await screen.findByText("RAG 入门咨询")).toBeInTheDocument();
  });

  it("输入关键词：防抖 300ms 后按 keyword 请求", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderPanel();
    fireEvent.focus(screen.getByTestId("sidebar-session-search"));
    fireEvent.change(screen.getByTestId("sidebar-session-search"), {
      target: { value: "索引" },
    });
    // 防抖窗口内不发起
    expect(apiMock.getSessions).not.toHaveBeenCalledWith(1, 20, "索引");
    await vi.advanceTimersByTimeAsync(350);
    await waitFor(() => {
      expect(apiMock.getSessions).toHaveBeenCalledWith(1, 20, "索引");
    });
  });

  it("结果列表：max-h-72 滚动容器承载多条会话", async () => {
    apiMock.getSessions.mockResolvedValue({
      records: [
        makeSession({ id: "s1", title: "会话一" }),
        makeSession({ id: "s2", title: "会话二" }),
      ],
      total: "2",
      page: 1,
      size: 20,
    });
    renderPanel();
    fireEvent.focus(screen.getByTestId("sidebar-session-search"));
    const dropdown = await screen.findByTestId("session-search-dropdown");
    expect(dropdown).toHaveClass("max-h-72");
    expect(dropdown).toHaveClass("overflow-y-auto");
    expect(await screen.findByText("会话一")).toBeInTheDocument();
    expect(screen.getByText("会话二")).toBeInTheDocument();
  });

  it("空结果：空态文案携带关键词", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderPanel();
    fireEvent.focus(screen.getByTestId("sidebar-session-search"));
    fireEvent.change(screen.getByTestId("sidebar-session-search"), {
      target: { value: "不存在的" },
    });
    await vi.advanceTimersByTimeAsync(350);
    expect(await screen.findByTestId("session-search-empty")).toHaveTextContent(
      /没有找到「不存在的」相关会话/,
    );
  });
});

describe("SessionSearchPanel 跳转与关闭", () => {
  it("点击结果：跳转 /chat/{id} 且浮层关闭", async () => {
    apiMock.getSessions.mockResolvedValue({
      records: [makeSession({ id: "s-9", title: "目标会话" })],
      total: "1",
      page: 1,
      size: 20,
    });
    renderPanel();
    fireEvent.focus(screen.getByTestId("sidebar-session-search"));
    fireEvent.click(await screen.findByTestId("session-search-item"));
    expect(navMock.push).toHaveBeenCalledWith("/chat/s-9");
    expect(screen.queryByTestId("session-search-dropdown")).not.toBeInTheDocument();
  });

  it("Esc 键：浮层关闭（输入保留）", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderPanel();
    const input = screen.getByTestId("sidebar-session-search");
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "关键词" } });
    await screen.findByTestId("session-search-dropdown");
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByTestId("session-search-dropdown")).not.toBeInTheDocument();
    expect(input).toHaveValue("关键词");
  });

  it("点击面板外：浮层关闭", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderPanel();
    fireEvent.focus(screen.getByTestId("sidebar-session-search"));
    await screen.findByTestId("session-search-dropdown");
    // 模拟点击面板外部的 DOM 节点（document body 直接派发 mousedown）
    await act(async () => {
      document.body.dispatchEvent(new MouseEvent("mousedown", { bubbles: true }));
    });
    expect(screen.queryByTestId("session-search-dropdown")).not.toBeInTheDocument();
  });
});
