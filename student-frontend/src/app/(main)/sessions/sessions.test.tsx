/**
 * 会话管理页 /sessions 测试（Task 13 TDD 先行用例）
 *
 * 覆盖设计 §1.5.5 + J6/J7/R3：
 * - 时间分组（今天/昨天/本周/更早，按 lastMessageAt ?? createdAt）
 * - 分页 page/size=20 + 「加载更多」；新建对话（createSession → /chat/{id}）
 * - 删除二次确认 Dialog（danger）；409 → toast「会话正在对话中」
 * - 四态：Loading 骨架 / 空态「还没有会话记录」/ Error 横幅+重试 / 正常态
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SessionsPage from "./page";
import { ApiError } from "@/lib/api";
import type { SessionItem } from "@/lib/types";

/** 数据层 mock：会话分页 / 创建 / 删除 */
const apiMock = vi.hoisted(() => ({
  getSessions: vi.fn(),
  createSession: vi.fn(),
  deleteSession: vi.fn(),
}));
/** 路由 mock：新建跳转断言 */
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    getSessions: apiMock.getSessions,
    createSession: apiMock.createSession,
    deleteSession: apiMock.deleteSession,
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => routerMock,
}));

/** 由本地日历时刻生成 ISO 串（保证按本地时区解析后的日历日确定） */
function isoAt(year: number, month: number, day: number, hour = 12): string {
  return new Date(year, month, day, hour).toISOString();
}

/** 会话条目工厂（时间默认今天中午） */
function makeSession(overrides: Partial<SessionItem> = {}): SessionItem {
  const now = new Date();
  return {
    id: "s-1",
    title: "什么是 RAG",
    status: "ACTIVE",
    lastMessageAt: isoAt(now.getFullYear(), now.getMonth(), now.getDate()),
    createdAt: isoAt(now.getFullYear(), now.getMonth(), now.getDate()),
    ...overrides,
  };
}

/** 分页响应工厂 */
function pageOf(
  records: SessionItem[],
  total = records.length,
): {
  records: SessionItem[];
  total: string;
  page: number;
  size: number;
} {
  return { records, total: String(total), page: 1, size: 20 };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionsPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  apiMock.getSessions.mockReset();
  apiMock.createSession.mockReset();
  apiMock.deleteSession.mockReset();
  routerMock.push.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("会话页：四态", () => {
  it("Loading：列表骨架（与最终布局同形灰块）", () => {
    apiMock.getSessions.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId("sessions-skeleton")).toBeInTheDocument();
    expect(screen.getByText("会话")).toBeInTheDocument();
  });

  it("空态：还没有会话记录 + 开始对话（→ /chat）", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([]));
    renderPage();
    expect(await screen.findByText("还没有会话记录")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "开始对话" })).toHaveAttribute("href", "/chat");
  });

  it("Error：横幅 + 重试闭环恢复", async () => {
    apiMock.getSessions
      .mockRejectedValueOnce(new ApiError(503, "服务暂时不可用"))
      .mockResolvedValueOnce(pageOf([makeSession()]));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("什么是 RAG")).toBeInTheDocument();
  });
});

describe("会话页：时间分组与列表渲染", () => {
  it("今天/昨天/更早分组渲染；会话标题 + 状态徽章（进行中/已结束）", async () => {
    const now = new Date();
    apiMock.getSessions.mockResolvedValue(
      pageOf([
        makeSession({ id: "s-today", title: "今天的会话", status: "ACTIVE" }),
        makeSession({
          id: "s-yesterday",
          title: "昨天的会话",
          status: "CLOSED",
          lastMessageAt: isoAt(now.getFullYear(), now.getMonth(), now.getDate() - 1),
          createdAt: isoAt(now.getFullYear(), now.getMonth(), now.getDate() - 1),
        }),
        makeSession({
          id: "s-earlier",
          title: "更早的会话",
          status: "CLOSED",
          lastMessageAt: isoAt(now.getFullYear(), now.getMonth(), now.getDate() - 10),
          createdAt: isoAt(now.getFullYear(), now.getMonth(), now.getDate() - 10),
        }),
      ]),
    );
    renderPage();
    expect(await screen.findByText("今天的会话")).toBeInTheDocument();
    // 分组标题：今天与昨天、更早在场；本周（今日为周一等场景）可能为空组不渲染
    expect(screen.getByText("今天")).toBeInTheDocument();
    expect(screen.getByText("昨天")).toBeInTheDocument();
    expect(screen.getByText("更早")).toBeInTheDocument();
    // 状态徽章：ACTIVE → 进行中；CLOSED → 已结束
    expect(screen.getByText("进行中")).toBeInTheDocument();
    expect(screen.getAllByText("已结束")).toHaveLength(2);
    // 列表项为跳转链接 /chat/{id}
    expect(screen.getByRole("link", { name: /今天的会话/ })).toHaveAttribute(
      "href",
      "/chat/s-today",
    );
  });

  it("本周分组：周一至今（非今昨）的会话落入本周", async () => {
    const now = new Date();
    // 本周一（getDay() 0=周日，距周一 = (getDay()+6)%7）
    const monday = new Date(
      now.getFullYear(),
      now.getMonth(),
      now.getDate() - ((now.getDay() + 6) % 7),
    );
    // 仅当今天与本周一相隔 ≥2 天时（周三及以后）才存在「本周但非今昨」的样例；
    // 周一/周二时本周组为空组（昨天优先），跳过渲染断言（分组语义由 time.test 覆盖）。
    // 整数天差用 floor（round 在周二下午会把 1.6 天误判为 2，导致用例误跑，2026-08-25 实证）
    const dayDiff = Math.floor((now.getTime() - monday.getTime()) / 86_400_000);
    if (dayDiff < 2) {
      return;
    }
    apiMock.getSessions.mockResolvedValue(
      pageOf([
        makeSession({
          id: "s-week",
          title: "本周的会话",
          lastMessageAt: isoAt(monday.getFullYear(), monday.getMonth(), monday.getDate()),
          createdAt: isoAt(monday.getFullYear(), monday.getMonth(), monday.getDate()),
        }),
      ]),
    );
    renderPage();
    expect(await screen.findByText("本周的会话")).toBeInTheDocument();
    expect(screen.getByText("本周")).toBeInTheDocument();
  });

  it("分组时间取 lastMessageAt ?? createdAt（lastMessageAt 为 null 时按创建时间）", async () => {
    const now = new Date();
    apiMock.getSessions.mockResolvedValue(
      pageOf([
        makeSession({
          id: "s-new",
          title: "新建未聊",
          lastMessageAt: null,
          createdAt: isoAt(now.getFullYear(), now.getMonth(), now.getDate()),
        }),
      ]),
    );
    renderPage();
    expect(await screen.findByText("新建未聊")).toBeInTheDocument();
    expect(screen.getByText("今天")).toBeInTheDocument();
    // 列表项附带相对时间（time 元素文案非空）
    const time = screen.getAllByRole("link", { name: /新建未聊/ })[0].querySelector("time");
    expect(time).not.toBeNull();
    expect(time?.textContent?.length ?? 0).toBeGreaterThan(0);
  });
});

describe("会话页：分页加载更多", () => {
  it("每页 20 条：加载更多逐页追加，无更多后按钮消失", async () => {
    const all = Array.from({ length: 45 }, (_, index) =>
      makeSession({ id: `s-${index}`, title: `会话 ${index}` }),
    );
    apiMock.getSessions.mockImplementation((page: number) => {
      const start = (page - 1) * 20;
      return Promise.resolve(pageOf(all.slice(start, start + 20), all.length));
    });
    renderPage();
    // 第一页 20 条
    expect(await screen.findAllByTestId("session-row")).toHaveLength(20);
    // 加载更多：逐页追加（waitFor 等待断言成立，避免 fetch 在途时旧条数命中）
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    await waitFor(() => {
      expect(screen.getAllByTestId("session-row")).toHaveLength(40);
    });
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    await waitFor(() => {
      expect(screen.getAllByTestId("session-row")).toHaveLength(45);
    });
    expect(screen.queryByRole("button", { name: "加载更多" })).not.toBeInTheDocument();
  });
});

describe("会话页：新建对话", () => {
  it("点击新建对话：createSession 后跳转 /chat/{id}", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([]));
    apiMock.createSession.mockResolvedValue(
      makeSession({ id: "s-new", title: "新对话", lastMessageAt: null }),
    );
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "新建对话" }));
    await waitFor(() => {
      expect(apiMock.createSession).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(routerMock.push).toHaveBeenCalledWith("/chat/s-new");
    });
  });

  it("新建失败：toast「创建会话失败，请稍后重试」且不跳转", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([]));
    apiMock.createSession.mockRejectedValue(new ApiError(503, "服务暂时不可用"));
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "新建对话" }));
    expect(await screen.findByRole("status")).toHaveTextContent("创建会话失败，请稍后重试");
    expect(routerMock.push).not.toHaveBeenCalled();
  });
});

describe("会话页：删除二次确认与 409", () => {
  it("点击删除：弹出确认 Dialog（含会话标题），取消关闭且不调用接口", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s-9", title: "待删会话" })]));
    renderPage();
    await screen.findByText("待删会话");
    fireEvent.click(screen.getByRole("button", { name: /删除/ }));
    const dialog = screen.getByRole("dialog", { name: "删除会话" });
    expect(dialog).toHaveTextContent("待删会话");
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(apiMock.deleteSession).not.toHaveBeenCalled();
  });

  it("Esc 关闭确认框（键盘可访问性）", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s-9", title: "待删会话" })]));
    renderPage();
    await screen.findByText("待删会话");
    fireEvent.click(screen.getByRole("button", { name: /删除/ }));
    expect(screen.getByRole("dialog", { name: "删除会话" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(apiMock.deleteSession).not.toHaveBeenCalled();
  });

  it("点击遮罩关闭确认框", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s-9", title: "待删会话" })]));
    renderPage();
    await screen.findByText("待删会话");
    fireEvent.click(screen.getByRole("button", { name: /删除/ }));
    fireEvent.click(screen.getByTestId("confirm-overlay"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("确认删除：调用 deleteSession(id)，toast「会话已删除」且列表刷新", async () => {
    apiMock.getSessions
      .mockResolvedValueOnce(pageOf([makeSession({ id: "s-9", title: "待删会话" })]))
      .mockResolvedValue(pageOf([]));
    apiMock.deleteSession.mockResolvedValue(undefined);
    renderPage();
    await screen.findByText("待删会话");
    fireEvent.click(screen.getByRole("button", { name: /删除/ }));
    fireEvent.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() => {
      expect(apiMock.deleteSession).toHaveBeenCalledWith("s-9");
    });
    expect(await screen.findByRole("status")).toHaveTextContent("会话已删除");
    // 删除成功后列表刷新（invalidate → 重新拉取第一页）
    await waitFor(() => {
      expect(apiMock.getSessions).toHaveBeenCalledTimes(2);
    });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("删除 409（会话正在对话中）：toast 提示且不刷新列表", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s-9", title: "活跃会话" })]));
    apiMock.deleteSession.mockRejectedValue(new ApiError(409, "会话正在对话中，请稍后删除"));
    renderPage();
    await screen.findByText("活跃会话");
    fireEvent.click(screen.getByRole("button", { name: /删除/ }));
    fireEvent.click(screen.getByRole("button", { name: "确认删除" }));
    expect(await screen.findByRole("status")).toHaveTextContent("会话正在对话中");
    await waitFor(() => {
      expect(apiMock.getSessions).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("删除其它失败：toast「删除失败，请稍后重试」", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s-9", title: "待删会话" })]));
    apiMock.deleteSession.mockRejectedValue(new ApiError(500, "内部错误"));
    renderPage();
    await screen.findByText("待删会话");
    fireEvent.click(screen.getByRole("button", { name: /删除/ }));
    fireEvent.click(screen.getByRole("button", { name: "确认删除" }));
    expect(await screen.findByRole("status")).toHaveTextContent("删除失败，请稍后重试");
  });
});
