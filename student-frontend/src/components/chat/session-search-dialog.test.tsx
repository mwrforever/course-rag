/**
 * 会话搜索弹窗测试（M1 TDD 先行用例：统一弹窗取代内嵌搜索面板）
 *
 * 覆盖（spec M1.2 弹窗交互契约）：
 * - 输入 300ms 防抖后查询；滚动接近底部 200ms 节流触发 fetchNextPage 分页
 * - 关键字变化重置分页（页码归 1）；空结果空态文案；加载更多失败重试
 * - 点击结果跳转 /chat/{id} 并关闭弹窗
 *
 * 说明：jsdom 无 App Router 上下文，next/navigation 用最小 mock；
 * 滚动几何（scrollHeight/clientHeight/scrollTop）jsdom 恒 0，经
 * Object.defineProperty 手工定义以驱动「接近底部」判定。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionSearchDialog } from "./session-search-dialog";
import type { SessionItem } from "@/lib/types";

/** 数据层 mock：getSessions（按用例配置内存分页或失败序列） */
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

/** 构造会话条目（createdAt 默认 1 分钟前，相对时间断言用） */
function makeSession(overrides: Partial<SessionItem> = {}): SessionItem {
  return {
    id: "s-1",
    title: "RAG 入门咨询",
    status: "ACTIVE",
    createdAt: new Date(Date.now() - 60_000).toISOString(),
    lastMessageAt: null,
    ...overrides,
  };
}

/** 会话池：22 条「检索」+ 14 条「其它」（36 总量，分页/过滤断言可区分） */
function buildPool(): SessionItem[] {
  return Array.from({ length: 36 }, (_, index) =>
    makeSession({
      id: index < 22 ? `r${index + 1}` : `o${index - 21}`,
      title: index < 22 ? `检索会话 ${index + 1}` : `其它会话 ${index - 21}`,
    }),
  );
}

/** 内存分页 mock：按 keyword 过滤 + page/size 切片（模拟服务端 J6 语义） */
function mockSessionsInMemory(pool: SessionItem[]) {
  apiMock.getSessions.mockImplementation((page: number, size: number, keyword?: string) => {
    const matched = keyword ? pool.filter((session) => session.title.includes(keyword)) : pool;
    const start = (page - 1) * size;
    return Promise.resolve({
      records: matched.slice(start, start + size),
      total: String(matched.length),
      page,
      size,
    });
  });
}

/** 渲染弹窗（open 态；onClose 记录关闭调用） */
function renderDialog(onClose = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionSearchDialog open onClose={onClose} />
    </QueryClientProvider>,
  );
}

/** 手工定义滚动几何并派发 scroll 事件（jsdom 无布局，接近底部需手工构造） */
function scrollNearBottom() {
  const list = screen.getByTestId("session-search-results");
  Object.defineProperty(list, "scrollHeight", { configurable: true, value: 2000 });
  Object.defineProperty(list, "clientHeight", { configurable: true, value: 800 });
  Object.defineProperty(list, "scrollTop", { configurable: true, value: 1150 });
  fireEvent.scroll(list);
}

beforeEach(() => {
  apiMock.getSessions.mockReset();
  navMock.push.mockReset();
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("SessionSearchDialog 查询与分页", () => {
  it("关键字 300ms 防抖后查询，滚动接近底部 200ms 节流触发 fetchNextPage 分页", async () => {
    mockSessionsInMemory(buildPool());
    renderDialog();
    fireEvent.change(screen.getByTestId("session-search-input"), {
      target: { value: "检索" },
    });
    // 防抖窗口内不发起 keyword 查询
    expect(apiMock.getSessions).not.toHaveBeenCalledWith(1, 20, "检索");
    await vi.advanceTimersByTimeAsync(350);
    // 防抖后第一页 20 条（「检索」命中 22）
    expect(await screen.findAllByTestId("search-result-item")).toHaveLength(20);
    // 滚动接近底部：节流 200ms 内不追加
    scrollNearBottom();
    expect(screen.getAllByTestId("search-result-item")).toHaveLength(20);
    await vi.advanceTimersByTimeAsync(250);
    // 节流到期后触发下一页（page=2，keyword 随查询携带）→ 22 条全量
    await waitFor(() => {
      expect(apiMock.getSessions).toHaveBeenCalledWith(2, 20, "检索");
    });
    await waitFor(() => {
      expect(screen.getAllByTestId("search-result-item")).toHaveLength(22);
    });
  });

  it("关键字变化重置分页（页码归 1）；空结果展示空态文案", async () => {
    mockSessionsInMemory(buildPool());
    renderDialog();
    const input = screen.getByTestId("session-search-input");
    // 首个关键字翻到第二页（2×20 页缓存中）
    fireEvent.change(input, { target: { value: "检索" } });
    await vi.advanceTimersByTimeAsync(350);
    await screen.findAllByTestId("search-result-item");
    scrollNearBottom();
    await vi.advanceTimersByTimeAsync(250);
    await waitFor(() => {
      expect(screen.getAllByTestId("search-result-item")).toHaveLength(22);
    });
    // 换新关键字：分页重置，getSessions 重新从 page=1 起
    fireEvent.change(input, { target: { value: "其它" } });
    await vi.advanceTimersByTimeAsync(350);
    await waitFor(() => {
      expect(apiMock.getSessions).toHaveBeenLastCalledWith(1, 20, "其它");
    });
    // 「其它」命中 14：单页渲染且无加载更多
    expect(screen.getAllByTestId("search-result-item")).toHaveLength(14);
    // 空结果：空态文案携带关键词
    fireEvent.change(input, { target: { value: "不存在的" } });
    await vi.advanceTimersByTimeAsync(350);
    expect(await screen.findByTestId("session-search-empty")).toHaveTextContent(
      /没有找到「不存在的」相关会话/,
    );
  });

  it("加载更多失败：重试入口可见且既有结果保留，点击重发下一页请求", async () => {
    const pool = buildPool();
    // 基础实现内存分页；下一页失败在首页渲染后入队（Once 队列先于基础实现消费，
    // 若挂载即入队会被首个空关键字查询消费掉）
    mockSessionsInMemory(pool);
    renderDialog();
    fireEvent.change(screen.getByTestId("session-search-input"), {
      target: { value: "检索" },
    });
    await vi.advanceTimersByTimeAsync(350);
    await screen.findAllByTestId("search-result-item");
    apiMock.getSessions.mockRejectedValueOnce(new Error("网络错误"));
    scrollNearBottom();
    await vi.advanceTimersByTimeAsync(250);
    // fetchNextPage 失败：重试入口出现，既有 20 条保留
    const retry = await screen.findByTestId("search-retry-more");
    expect(screen.getAllByTestId("search-result-item")).toHaveLength(20);
    fireEvent.click(retry);
    // 重试成功：第二页追加至 22
    await waitFor(() => {
      expect(screen.getAllByTestId("search-result-item")).toHaveLength(22);
    });
  });
});

describe("SessionSearchDialog 跳转与关闭", () => {
  it("点击结果：跳转 /chat/{id} 并关闭弹窗", async () => {
    mockSessionsInMemory(buildPool());
    const onClose = vi.fn();
    renderDialog(onClose);
    fireEvent.change(screen.getByTestId("session-search-input"), {
      target: { value: "其它" },
    });
    await vi.advanceTimersByTimeAsync(350);
    const items = await screen.findAllByTestId("search-result-item");
    fireEvent.click(items[0]);
    // 首条「其它会话 1」→ /chat/o1，弹窗关闭
    expect(navMock.push).toHaveBeenCalledWith("/chat/o1");
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
