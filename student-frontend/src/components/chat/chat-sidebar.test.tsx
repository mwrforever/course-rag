/**
 * 课程助手对话侧栏测试（UI 重构 2026-08-25 新增组件）
 *
 * 覆盖：品牌/新建对话入口、会话历史渲染与激活态、空态与骨架、
 * 折叠切换（宽度类 + localStorage 持久化）、Ctrl+K 快捷键、用户区与退出登录。
 *
 * 说明：jsdom 无 App Router 上下文，next/navigation 用最小 mock；
 * motion 未在侧栏使用（纯 Tailwind 过渡），无需动画 mock。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ChatSidebar } from "./chat-sidebar";
import { ChatStreamingProvider, useSetChatStreaming } from "./chat-streaming-context";
import type { SessionItem } from "@/lib/types";

/** 数据层 mock：getSessions 会话列表（骨架/空/正常态）+ 重命名/删除（会话管理用例） */
const apiMock = vi.hoisted(() => ({
  getSessions: vi.fn(),
  updateSessionTitle: vi.fn(),
  deleteSession: vi.fn(),
}));
/** 认证 mock：displayName / logout 可切 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
/** 导航 mock：pathname 驱动激活态；push 记录跳转（新建对话快捷键/登出/删除激活会话） */
const navMock = vi.hoisted(() => ({
  pathname: "/chat",
  push: vi.fn(),
  clear: vi.fn(),
  invalidateQueries: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    getSessions: apiMock.getSessions,
    updateSessionTitle: apiMock.updateSessionTitle,
    deleteSession: apiMock.deleteSession,
  };
});
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));
vi.mock("@tanstack/react-query", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@tanstack/react-query")>();
  return {
    ...actual,
    // 缓存操作断言：clear（登出清缓存）/ invalidateQueries（保存/删除后失效列表）
    useQueryClient: () => ({
      clear: navMock.clear,
      invalidateQueries: navMock.invalidateQueries,
    }),
  };
});
vi.mock("next/navigation", () => ({
  usePathname: () => navMock.pathname,
  useRouter: () => ({ push: navMock.push }),
}));

/** 构造会话对象 */
function makeSession(overrides: Partial<SessionItem> = {}): SessionItem {
  return {
    id: "s-1",
    title: "什么是 RAG",
    status: "ACTIVE",
    createdAt: new Date(Date.now() - 60_000).toISOString(),
    lastMessageAt: null,
    ...overrides,
  };
}

/** 渲染容器：独立 QueryClient（retry 关闭） */
function renderSidebar(pathname = "/chat") {
  navMock.pathname = pathname;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ChatSidebar />
    </QueryClientProvider>,
  );
}

/** 流式状态探针：把 Provider 的 setStreaming 暴露给用例（模拟 ChatWorkspace 上报） */
function StreamingProbe({ onReady }: { onReady: (set: (streaming: boolean) => void) => void }) {
  const setStreaming = useSetChatStreaming();
  useEffect(() => {
    onReady(setStreaming);
  }, [onReady, setStreaming]);
  return null;
}

beforeEach(() => {
  apiMock.getSessions.mockReset();
  apiMock.updateSessionTitle.mockReset();
  apiMock.deleteSession.mockReset();
  authMock.useAuth.mockReset();
  navMock.push.mockReset();
  navMock.clear.mockReset();
  navMock.invalidateQueries.mockReset();
  navMock.pathname = "/chat";
  window.localStorage.clear();
  authMock.useAuth.mockReturnValue({
    user: { userId: "u1", role: "STUDENT", displayName: "同学A" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ChatSidebar 结构", () => {
  it("展开态：品牌链接触达首页 + 新建对话链接 + Ctrl K 快捷键提示", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    expect(await screen.findByRole("link", { name: /课程助手/ })).toHaveAttribute("href", "/");
    const newChat = screen.getByRole("link", { name: /新建对话/ });
    expect(newChat).toHaveAttribute("href", "/chat");
    expect(screen.getByText("Ctrl K")).toBeInTheDocument();
    // 展开宽度 64（w-64）与「会话历史」分组标题
    expect(screen.getByTestId("chat-sidebar")).toHaveClass("w-64");
    expect(screen.getByText("会话历史")).toBeInTheDocument();
  });

  it("会话历史：渲染条目（链接 /chat/{id}），当前会话激活态高亮", async () => {
    apiMock.getSessions.mockResolvedValue({
      records: [
        makeSession({ id: "s1", title: "会话一" }),
        makeSession({ id: "s2", title: "会话二" }),
      ],
      total: "2",
      page: 1,
      size: 20,
    });
    renderSidebar("/chat/s2");
    const first = await screen.findByRole("link", { name: /会话一/ });
    expect(first).toHaveAttribute("href", "/chat/s1");
    const second = screen.getByRole("link", { name: /会话二/ });
    expect(second).toHaveAttribute("href", "/chat/s2");
    // 激活态高亮位于行容器（data-testid 行级），非内部链接
    const secondRow = second.closest('[data-testid="sidebar-session-item"]') as HTMLElement;
    const firstRow = first.closest('[data-testid="sidebar-session-item"]') as HTMLElement;
    expect(secondRow).toHaveClass("bg-brand-soft");
    expect(firstRow).not.toHaveClass("bg-brand-soft");
  });

  it("会话空态：引导文案", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    expect(await screen.findByText("还没有会话，开始一段对话吧")).toBeInTheDocument();
  });

  it("用户区：显示名 + 渐变头像首字母", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    expect(await screen.findByText("同学A")).toBeInTheDocument();
    expect(screen.getByTestId("sidebar-avatar")).toHaveTextContent("同");
  });

  it("退出登录：二次确认后登出清凭据 → 清查询缓存 → 跳首页", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    // 第一步：点击退出 → 弹确认框（未确认不登出）
    fireEvent.click(await screen.findByRole("button", { name: "退出登录" }));
    expect(await screen.findByRole("dialog", { name: "退出登录" })).toBeInTheDocument();
    expect(authMock.useAuth().logout).not.toHaveBeenCalled();
    // 第二步：确认退出
    fireEvent.click(screen.getByRole("button", { name: "退出" }));
    await waitFor(() => {
      expect(authMock.useAuth().logout).toHaveBeenCalled();
      expect(navMock.clear).toHaveBeenCalled();
      expect(navMock.push).toHaveBeenCalledWith("/");
    });
  });

  it("登出确认框：取消不登出", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    fireEvent.click(await screen.findByRole("button", { name: "退出登录" }));
    await screen.findByRole("dialog", { name: "退出登录" });
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(authMock.useAuth().logout).not.toHaveBeenCalled();
  });
});

describe("ChatSidebar 会话管理（增删改查）", () => {
  /** 分页响应构造（total 为 Long→string） */
  function pageOf(records: SessionItem[], total: number) {
    return { records, total: String(total), page: 1, size: 20 };
  }

  it("查：输入关键词防抖后按 keyword 请求（空时恢复全量）", async () => {
    apiMock.getSessions.mockResolvedValue(
      pageOf([makeSession({ id: "s1", title: "RAG 是什么" })], 1),
    );
    renderSidebar();
    await screen.findByText("RAG 是什么");
    const input = screen.getByTestId("sidebar-session-search");
    fireEvent.change(input, { target: { value: "索引" } });
    // 防抖 300ms 后才发起关键词查询
    await waitFor(() => {
      expect(apiMock.getSessions).toHaveBeenCalledWith(1, 20, "索引");
    });
    // 清除按钮恢复全量列表（keyword 空）
    fireEvent.click(screen.getByRole("button", { name: "清除搜索" }));
    await waitFor(() => {
      expect(apiMock.getSessions).toHaveBeenCalledWith(1, 20, undefined);
    });
  });

  it("查：搜索无结果 → 提示文案", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([], 0));
    renderSidebar();
    const input = screen.getByTestId("sidebar-session-search");
    fireEvent.change(input, { target: { value: "不存在的" } });
    expect(await screen.findByText(/没有找到「不存在的」相关会话/)).toBeInTheDocument();
  });

  it("改：hover 编辑按钮 → 行内输入 → 保存 → PATCH + 失效列表", async () => {
    apiMock.updateSessionTitle.mockResolvedValue(makeSession({ id: "s1", title: "新标题" }));
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "旧标题" })], 1));
    renderSidebar();
    await screen.findByText("旧标题");
    // 打开行内编辑
    fireEvent.click(screen.getByRole("button", { name: /编辑会话标题/ }));
    const input = screen.getByTestId("sidebar-session-edit-input");
    fireEvent.change(input, { target: { value: "新标题" } });
    fireEvent.click(screen.getByRole("button", { name: /保存/ }));
    await waitFor(() => {
      expect(apiMock.updateSessionTitle).toHaveBeenCalledWith("s1", "新标题");
      expect(navMock.invalidateQueries).toHaveBeenCalled();
    });
    // 保存后编辑态关闭
    expect(screen.queryByTestId("sidebar-session-edit-input")).toBeNull();
  });

  it("改：空标题保存直接退出编辑（不发请求）", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "旧标题" })], 1));
    renderSidebar();
    await screen.findByText("旧标题");
    fireEvent.click(screen.getByRole("button", { name: /编辑会话标题/ }));
    fireEvent.change(screen.getByTestId("sidebar-session-edit-input"), {
      target: { value: "   " },
    });
    fireEvent.click(screen.getByRole("button", { name: /保存/ }));
    await waitFor(() => {
      expect(screen.queryByTestId("sidebar-session-edit-input")).toBeNull();
    });
    expect(apiMock.updateSessionTitle).not.toHaveBeenCalled();
  });

  it("删：二次确认 → DELETE + 失效列表；删除当前激活会话后跳 /chat 新对话", async () => {
    apiMock.deleteSession.mockResolvedValue(undefined);
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "会话一" })], 1));
    renderSidebar("/chat/s1");
    await screen.findByText("会话一");
    // 第一步：删除按钮 → 确认框（未确认不删）
    fireEvent.click(screen.getByRole("button", { name: /删除会话/ }));
    expect(await screen.findByRole("dialog", { name: "删除会话" })).toBeInTheDocument();
    expect(apiMock.deleteSession).not.toHaveBeenCalled();
    // 第二步：确认删除（danger 语义）
    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    await waitFor(() => {
      expect(apiMock.deleteSession).toHaveBeenCalledWith("s1");
      expect(navMock.push).toHaveBeenCalledWith("/chat");
    });
  });

  it("删：409（会话正在对话中）→ 提示文案且列表不再刷新", async () => {
    apiMock.deleteSession.mockRejectedValue(new Error("会话正在对话中"));
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "会话一" })], 1));
    renderSidebar("/chat/s1");
    await screen.findByText("会话一");
    fireEvent.click(screen.getByRole("button", { name: /删除会话/ }));
    await screen.findByRole("dialog", { name: "删除会话" });
    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    // 失败 toast（对话正在对话中语气按后端 409 文案分级）
    await waitFor(() => {
      expect(screen.getByText("删除失败，请稍后重试")).toBeInTheDocument();
    });
    expect(navMock.push).not.toHaveBeenCalled();
  });

  it("分页：hasNextPage 时渲染「加载更多」，点击追加下一页", async () => {
    // 第一页满 20 条 + total 21 → 有下一页
    const firstPage = Array.from({ length: 20 }, (_, index) =>
      makeSession({ id: `s${index + 1}`, title: `会话 ${index + 1}` }),
    );
    apiMock.getSessions
      .mockResolvedValueOnce({ records: firstPage, total: "21", page: 1, size: 20 })
      .mockResolvedValueOnce({
        records: [makeSession({ id: "s21", title: "会话 21" })],
        total: "21",
        page: 2,
        size: 20,
      });
    renderSidebar();
    await screen.findByText("会话 1");
    const more = screen.getByTestId("sidebar-load-more");
    fireEvent.click(more);
    await waitFor(() => {
      expect(apiMock.getSessions).toHaveBeenCalledWith(2, 20, undefined);
    });
    expect(await screen.findByText("会话 21")).toBeInTheDocument();
  });
});

describe("ChatSidebar 折叠与快捷键", () => {
  it("折叠切换：收起为 w-16 图标态，偏好写回 localStorage，展开恢复", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    await screen.findByRole("link", { name: /新建对话/ });
    fireEvent.click(screen.getByRole("button", { name: "收起侧栏" }));
    expect(screen.getByTestId("chat-sidebar")).toHaveClass("w-16");
    expect(window.localStorage.getItem("cc.chat-sidebar.collapsed")).toBe("1");
    // 折叠态品牌区隐藏，仅展开按钮
    expect(screen.queryByRole("link", { name: /课程助手/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "展开侧栏" }));
    expect(screen.getByTestId("chat-sidebar")).toHaveClass("w-64");
    expect(window.localStorage.getItem("cc.chat-sidebar.collapsed")).toBe("0");
  });

  it("折叠偏好持久化：localStorage=1 时初始即折叠（会话条目仅图标）", async () => {
    window.localStorage.setItem("cc.chat-sidebar.collapsed", "1");
    apiMock.getSessions.mockResolvedValue({
      records: [makeSession({ id: "s1", title: "会话一" })],
      total: "1",
      page: 1,
      size: 20,
    });
    renderSidebar("/chat/s1");
    await waitFor(() => {
      expect(screen.getByTestId("chat-sidebar")).toHaveClass("w-16");
    });
    // 折叠态条目：仅图标链接（标题文字隐藏；data-testid 位于行容器，链接经 querySelector 取）
    const item = await screen.findByTestId("sidebar-session-item");
    const link = item.querySelector("a");
    expect(link).toHaveAttribute("href", "/chat/s1");
    expect(screen.queryByText("会话一")).not.toBeInTheDocument();
  });

  it("Ctrl+K（cmd+K）快捷键：拦截默认并跳转新建对话", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    await waitFor(() => {
      expect(navMock.push).toHaveBeenCalledWith("/chat");
    });
  });

  it("流式进行中：Ctrl+K 守卫不跳转、新建对话置灰提示，结束后恢复", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    // 探针：模拟工作区经 Context 上报流式状态
    let setStreaming!: (streaming: boolean) => void;
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <ChatStreamingProvider>
          <StreamingProbe onReady={(set) => (setStreaming = set)} />
          <ChatSidebar />
        </ChatStreamingProvider>
      </QueryClientProvider>,
    );
    await screen.findByRole("link", { name: /新建对话/ });

    act(() => setStreaming(true));
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    expect(navMock.push).not.toHaveBeenCalled();
    expect(screen.getByRole("link", { name: /新建对话/ })).toHaveAttribute(
      "title",
      "正在生成回答，结束后再新建对话",
    );

    // 流结束（或工作区卸载复位）：快捷键恢复可用
    act(() => setStreaming(false));
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    expect(navMock.push).toHaveBeenCalledWith("/chat");
  });

  it("加载骨架：会话查询挂起时灰条骨架可见", async () => {
    apiMock.getSessions.mockReturnValue(new Promise(() => {}));
    renderSidebar();
    expect(screen.getByTestId("sessions-skeleton")).toBeInTheDocument();
  });
});
