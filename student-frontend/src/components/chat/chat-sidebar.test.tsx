/**
 * 课程助手对话侧栏测试（UI 重构 2026-08-25 新增组件；2026-08-29 弹窗化改版；
 * 2026-09-02 M1：收起态历史浮层 + 统一搜索弹窗，内嵌搜索面板下线）
 *
 * 覆盖：品牌/新建对话入口（button + 新建信号/跳转双路径）、会话历史渲染与激活态、
 * 空态与骨架、折叠切换（宽度类 + localStorage 持久化）、Ctrl+K 快捷键、
 * 改名弹窗（RenameDialog 化）、删除/登出二次确认、用户区、
 * M1 收起态（历史入口 hover 浮层最新 10 条 / 触屏 click 切换 / 搜索弹窗接线）。
 *
 * 说明：jsdom 无 App Router 上下文，next/navigation 用最小 mock；
 * motion 未在侧栏使用（纯 Tailwind 过渡），无需动画 mock。
 * 搜索弹窗（SessionSearchDialog）行为由独立测试文件覆盖，此处只断言侧栏接线。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ChatSidebar } from "./chat-sidebar";
import {
  ChatStreamingProvider,
  useChatNewChatSeq,
  useMarkChatStreaming,
  useUnmarkChatStreaming,
} from "./chat-streaming-context";
import type { SessionItem } from "@/lib/types";

/** 数据层 mock：getSessions 会话列表（骨架/空/正常态）+ 重命名/删除（会话管理用例）
 *  + getActiveRun（2026-09-03 生成中标记自愈核对） */
const apiMock = vi.hoisted(() => ({
  getSessions: vi.fn(),
  updateSessionTitle: vi.fn(),
  deleteSession: vi.fn(),
  getActiveRun: vi.fn(),
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
    getActiveRun: apiMock.getActiveRun,
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

/** 流式标记探针：把 Provider 的 mark/unmark 暴露给用例（模拟 ChatWorkspace 上报，2026-09-03 集合化） */
function StreamingProbe({
  onReady,
}: {
  onReady: (api: {
    mark: (sessionId: string) => void;
    unmark: (sessionId: string) => void;
  }) => void;
}) {
  const mark = useMarkChatStreaming();
  const unmark = useUnmarkChatStreaming();
  useEffect(() => {
    onReady({ mark, unmark });
  }, [onReady, mark, unmark]);
  return null;
}

/** 新建对话信号计数探针：观察 Provider 的 newChatSeq 自增（信号已发出的可观察证据） */
function NewChatSeqProbe({ onChange }: { onChange: (seq: number) => void }) {
  const seq = useChatNewChatSeq();
  useEffect(() => {
    onChange(seq);
  }, [onChange, seq]);
  return null;
}

beforeEach(() => {
  apiMock.getSessions.mockReset();
  apiMock.updateSessionTitle.mockReset();
  apiMock.deleteSession.mockReset();
  // 默认核实命中活跃 run（标记保留）；自愈用例按需覆盖为 null
  apiMock.getActiveRun.mockReset().mockResolvedValue("run-live");
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
    openLoginDialog: vi.fn(),
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ChatSidebar 结构", () => {
  it("展开态：品牌链接触达首页 + 新建对话按钮（弹窗化改版：button 非 Link）+ Ctrl K 提示", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    expect(await screen.findByRole("link", { name: /课程助手/ })).toHaveAttribute("href", "/");
    // 新建对话改 button（/chat 同路由经信号 reset，非 /chat 跳转；行为见下方信号用例）
    expect(screen.getByRole("button", { name: /新建对话/ })).toBeInTheDocument();
    expect(screen.getByText("Ctrl K")).toBeInTheDocument();
    // 展开宽度 64（w-64）与「会话历史」分组标题（搜索浮层化后标题恒定）
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

  it("未登录：用户区显示登录入口（点击触发全局登录弹窗），不渲染头像", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue({
      user: null,
      accessToken: null,
      isAuthenticated: false,
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
      openLoginDialog,
    });
    renderSidebar();
    fireEvent.click(await screen.findByTestId("sidebar-login"));
    expect(openLoginDialog).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId("sidebar-avatar")).toBeNull();
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

  it("查：列表查询恒全量（keyword 搜索职责移交搜索浮层面板）", async () => {
    apiMock.getSessions.mockResolvedValue(
      pageOf([makeSession({ id: "s1", title: "RAG 是什么" })], 1),
    );
    renderSidebar();
    await screen.findByText("RAG 是什么");
    // 侧栏主列表不再按 keyword 过滤（两参调用；搜索浮层独立查询）
    expect(apiMock.getSessions).toHaveBeenCalledWith(1, 20);
  });

  it("改：编辑按钮 → 改名弹窗预填旧标题 → 修改保存 → PATCH + 失效列表", async () => {
    apiMock.updateSessionTitle.mockResolvedValue(makeSession({ id: "s1", title: "新标题" }));
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "旧标题" })], 1));
    renderSidebar();
    await screen.findByText("旧标题");
    // 打开改名弹窗（预填旧标题）
    fireEvent.click(screen.getByRole("button", { name: /编辑会话标题/ }));
    const input = (await screen.findByRole("textbox", { name: "会话标题" })) as HTMLInputElement;
    expect(input.value).toBe("旧标题");
    fireEvent.change(input, { target: { value: "新标题" } });
    fireEvent.click(screen.getByRole("button", { name: /保存/ }));
    await waitFor(() => {
      expect(apiMock.updateSessionTitle).toHaveBeenCalledWith("s1", "新标题");
      expect(navMock.invalidateQueries).toHaveBeenCalled();
    });
    // 保存成功后弹窗关闭
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "重命名会话" })).not.toBeInTheDocument();
    });
  });

  it("改：空标题 → 弹窗校验错误不提交（不发 PATCH）", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "旧标题" })], 1));
    renderSidebar();
    await screen.findByText("旧标题");
    fireEvent.click(screen.getByRole("button", { name: /编辑会话标题/ }));
    const input = await screen.findByRole("textbox", { name: "会话标题" });
    fireEvent.change(input, { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: /保存/ }));
    expect(await screen.findByText("标题不能为空")).toBeInTheDocument();
    expect(apiMock.updateSessionTitle).not.toHaveBeenCalled();
    // 弹窗保留（用户修正后可重试）
    expect(screen.getByRole("dialog", { name: "重命名会话" })).toBeInTheDocument();
  });

  it("改：PATCH 失败 → toast 提示且弹窗保留可重试", async () => {
    apiMock.updateSessionTitle.mockRejectedValue(new Error("保存失败"));
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "旧标题" })], 1));
    renderSidebar();
    await screen.findByText("旧标题");
    fireEvent.click(screen.getByRole("button", { name: /编辑会话标题/ }));
    const input = await screen.findByRole("textbox", { name: "会话标题" });
    fireEvent.change(input, { target: { value: "新标题" } });
    fireEvent.click(screen.getByRole("button", { name: /保存/ }));
    await waitFor(() => {
      expect(screen.getByText("保存失败，请稍后重试")).toBeInTheDocument();
    });
    expect(screen.getByRole("dialog", { name: "重命名会话" })).toBeInTheDocument();
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
      expect(apiMock.getSessions).toHaveBeenCalledWith(2, 20);
    });
    expect(await screen.findByText("会话 21")).toBeInTheDocument();
  });
});

describe("ChatSidebar 生成中标记（2026-09-03 集合化：切走/新建不清标记 + 核实自愈）", () => {
  /** 分页响应构造（total 为 Long→string） */
  function pageOf(records: SessionItem[], total: number) {
    return { records, total: String(total), page: 1, size: 20 };
  }

  /** Provider + 探针 + 侧栏渲染（生成中标记用例共用） */
  function renderWithProbe() {
    let api: { mark: (sessionId: string) => void; unmark: (sessionId: string) => void };
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(
      <QueryClientProvider client={client}>
        <ChatStreamingProvider>
          <StreamingProbe
            onReady={(handlers) => {
              api = handlers;
            }}
          />
          <ChatSidebar />
        </ChatStreamingProvider>
      </QueryClientProvider>,
    );
    return {
      view,
      mark: (id: string) => act(() => api!.mark(id)),
      unmark: (id: string) => act(() => api!.unmark(id)),
    };
  }

  it("标记集合命中会话行渲染脉冲点；多会话并发可并存（集合语义）", async () => {
    apiMock.getSessions.mockResolvedValue(
      pageOf(
        [makeSession({ id: "s1", title: "会话一" }), makeSession({ id: "s2", title: "会话二" })],
        2,
      ),
    );
    const { mark } = renderWithProbe();
    const first = await screen.findByRole("link", { name: /会话一/ });
    expect(
      first
        .closest('[data-testid="sidebar-session-item"]')!
        .querySelector('[data-testid="session-generating-dot"]'),
    ).toBeNull();

    // 标记 s1（模拟工作区 streaming 上报）：仅 s1 行出现脉冲点
    mark("s1");
    const rowOne = screen
      .getByRole("link", { name: /会话一/ })
      .closest('[data-testid="sidebar-session-item"]') as HTMLElement;
    const rowTwo = screen
      .getByRole("link", { name: /会话二/ })
      .closest('[data-testid="sidebar-session-item"]') as HTMLElement;
    expect(rowOne.querySelector('[data-testid="session-generating-dot"]')).not.toBeNull();
    expect(rowTwo.querySelector('[data-testid="session-generating-dot"]')).toBeNull();

    // 再标记 s2（多会话并发）：两行同时挂脉冲点
    mark("s2");
    expect(rowTwo.querySelector('[data-testid="session-generating-dot"]')).not.toBeNull();
  });

  it("自愈：标记后核实无活跃 run（active-run 返回 null）→ 清标记、脉冲点消失", async () => {
    // 标记出现即核实一次（30s 周期外的即时核对路径）：run 已结束 → 标记不滞留
    apiMock.getActiveRun.mockResolvedValue(null);
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "会话一" })], 1));
    const { mark } = renderWithProbe();
    await screen.findByRole("link", { name: /会话一/ });
    mark("s1");
    await waitFor(() => {
      expect(apiMock.getActiveRun).toHaveBeenCalledWith("s1");
    });
    await waitFor(() => {
      const row = screen
        .getByRole("link", { name: /会话一/ })
        .closest('[data-testid="sidebar-session-item"]') as HTMLElement;
      expect(row.querySelector('[data-testid="session-generating-dot"]')).toBeNull();
    });
  });

  it("自愈：核实命中活跃 run → 标记保留；核实请求失败不清标记（下轮兜底）", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "会话一" })], 1));
    const { mark } = renderWithProbe();
    await screen.findByRole("link", { name: /会话一/ });
    // 命中活跃 run（beforeEach 默认 run-live）：标记保留
    mark("s1");
    await waitFor(() => {
      expect(apiMock.getActiveRun).toHaveBeenCalledWith("s1");
    });
    let row = screen
      .getByRole("link", { name: /会话一/ })
      .closest('[data-testid="sidebar-session-item"]') as HTMLElement;
    expect(row.querySelector('[data-testid="session-generating-dot"]')).not.toBeNull();

    // 核实请求失败（网络异常）：标记不清（保留下轮核对）
    apiMock.getActiveRun.mockRejectedValue(new Error("网络抖动"));
    row = screen
      .getByRole("link", { name: /会话一/ })
      .closest('[data-testid="sidebar-session-item"]') as HTMLElement;
    expect(row.querySelector('[data-testid="session-generating-dot"]')).not.toBeNull();
  });

  it("清除标记（本地终态上报）：脉冲点即时消失（工作区 end 后路径）", async () => {
    apiMock.getSessions.mockResolvedValue(pageOf([makeSession({ id: "s1", title: "会话一" })], 1));
    const { mark, unmark } = renderWithProbe();
    await screen.findByRole("link", { name: /会话一/ });
    mark("s1");
    unmark("s1");
    const row = screen
      .getByRole("link", { name: /会话一/ })
      .closest('[data-testid="sidebar-session-item"]') as HTMLElement;
    expect(row.querySelector('[data-testid="session-generating-dot"]')).toBeNull();
  });
});

describe("ChatSidebar 折叠与快捷键", () => {
  it("折叠切换：收起为 w-16 图标态，偏好写回 localStorage，展开恢复", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    await screen.findByRole("button", { name: /新建对话/ });
    fireEvent.click(screen.getByRole("button", { name: "收起侧栏" }));
    expect(screen.getByTestId("chat-sidebar")).toHaveClass("w-16");
    expect(window.localStorage.getItem("cc.chat-sidebar.collapsed")).toBe("1");
    // 折叠态品牌区隐藏，仅展开按钮
    expect(screen.queryByRole("link", { name: /课程助手/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "展开侧栏" }));
    expect(screen.getByTestId("chat-sidebar")).toHaveClass("w-64");
    expect(window.localStorage.getItem("cc.chat-sidebar.collapsed")).toBe("0");
  });

  it("折叠偏好持久化：localStorage=1 时初始即折叠（列表区收敛为历史入口）", async () => {
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
    // M1 收起态：会话列表区整体替换为单个历史入口（逐会话图标行与标题不再渲染）
    expect(screen.getByTestId("collapsed-history-entry")).toBeInTheDocument();
    expect(screen.queryAllByTestId("sidebar-session-item")).toHaveLength(0);
    expect(screen.queryByText("会话一")).not.toBeInTheDocument();
  });

  it("Ctrl+K（cmd+K）快捷键：非 /chat 路由跳转 /chat；/chat 路由发新建信号（不重挂载）", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    // 路径一：历史会话页 → 跳转新对话
    const first = renderSidebar("/chat/s1");
    await screen.findByRole("button", { name: /新建对话/ });
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    await waitFor(() => {
      expect(navMock.push).toHaveBeenCalledWith("/chat");
    });
    first.unmount();

    // 路径二：已在 /chat → 经 Context 信号驱动工作区 reset（同路由不重挂载）
    navMock.pathname = "/chat";
    const seqs: number[] = [];
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <ChatStreamingProvider>
          <NewChatSeqProbe onChange={(seq) => seqs.push(seq)} />
          <ChatSidebar />
        </ChatStreamingProvider>
      </QueryClientProvider>,
    );
    await screen.findByRole("button", { name: /新建对话/ });
    navMock.push.mockClear();
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    // 信号计数自增（信号已发出）且不发 push（/chat 同路由不重挂载）
    await waitFor(() => {
      expect(seqs.at(-1)).toBeGreaterThan(0);
    });
    expect(navMock.push).not.toHaveBeenCalled();
  });

  it("新建对话按钮：/chat 同路由点击 → 发出新建信号（不走 push）", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    const seqs: number[] = [];
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <ChatStreamingProvider>
          <NewChatSeqProbe onChange={(seq) => seqs.push(seq)} />
          <ChatSidebar />
        </ChatStreamingProvider>
      </QueryClientProvider>,
    );
    await screen.findByRole("button", { name: /新建对话/ });
    fireEvent.click(screen.getByRole("button", { name: /新建对话/ }));
    // 信号计数自增（按钮经 Context 通知工作区 reset），且不发生路由跳转
    await waitFor(() => {
      expect(seqs.at(-1)).toBeGreaterThan(0);
    });
    expect(navMock.push).not.toHaveBeenCalled();
  });

  it("新建对话按钮：非 /chat 路由点击 → router.push('/chat')", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar("/chat/s1");
    await screen.findByRole("button", { name: /新建对话/ });
    fireEvent.click(screen.getByRole("button", { name: /新建对话/ }));
    await waitFor(() => {
      expect(navMock.push).toHaveBeenCalledWith("/chat");
    });
  });

  it("多会话并发（2026-09-01 用户拍板）：流式生成中新建对话仍可用（按钮不禁用 + Ctrl+K 发信号）", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    // 探针：模拟工作区经 Context 标记某会话生成中 + 观察新建信号计数
    let markStreaming!: (sessionId: string) => void;
    const seqs: number[] = [];
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <ChatStreamingProvider>
          <StreamingProbe
            onReady={({ mark }) => {
              markStreaming = mark;
            }}
          />
          <NewChatSeqProbe onChange={(seq) => seqs.push(seq)} />
          <ChatSidebar />
        </ChatStreamingProvider>
      </QueryClientProvider>,
    );
    const newChat = await screen.findByRole("button", { name: /新建对话/ });

    // 流式生成中（另一会话正在回答）：不再被全局守卫禁用——可同时开启多会话问答
    act(() => markStreaming("s-other"));
    expect(newChat).toBeEnabled();
    expect(newChat).not.toHaveAttribute("title", /回答生成中|结束后再新建/);

    // 按钮点击：/chat 同路由发新建信号（seq 自增；不 push）
    fireEvent.click(newChat);
    await waitFor(() => {
      expect(seqs.at(-1)).toBeGreaterThan(0);
    });
    expect(navMock.push).not.toHaveBeenCalled();

    // Ctrl+K：流式生成中同样发信号（不忽略）
    const seqBefore = seqs.at(-1) ?? 0;
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    await waitFor(() => {
      expect(seqs.at(-1)).toBeGreaterThan(seqBefore);
    });
    expect(navMock.push).not.toHaveBeenCalled();
  });

  it("加载骨架：会话查询挂起时灰条骨架可见", async () => {
    apiMock.getSessions.mockReturnValue(new Promise(() => {}));
    renderSidebar();
    expect(screen.getByTestId("sessions-skeleton")).toBeInTheDocument();
  });
});

describe("ChatSidebar 收起态历史浮层与搜索弹窗（M1）", () => {
  it("收起态：会话列表区替换为单个历史入口图标，hover 弹出最新 10 条会话浮层", async () => {
    window.localStorage.setItem("cc.chat-sidebar.collapsed", "1");
    // 浮层数据源 getSessions(1, 10)：按 size 区分浮层（10 条）与主列表请求
    apiMock.getSessions.mockImplementation((page: number, size: number) => {
      const count = size === 10 ? 10 : 12;
      return Promise.resolve({
        records: Array.from({ length: count }, (_, index) =>
          makeSession({ id: `p${index}`, title: `历史会话 ${index}` }),
        ),
        total: String(count),
        page,
        size,
      });
    });
    renderSidebar("/chat/p3");
    await waitFor(() => {
      expect(screen.getByTestId("chat-sidebar")).toHaveClass("w-16");
    });
    // 会话列表区整体下线：不再渲染逐会话图标行
    expect(screen.queryAllByTestId("sidebar-session-item")).toHaveLength(0);
    const entry = screen.getByTestId("collapsed-history-entry");
    expect(entry).toHaveAttribute("aria-label", "历史会话");
    // hover 弹出浮层：最新 10 条（标题 + 相对时间）
    fireEvent.mouseEnter(entry);
    const items = await screen.findAllByTestId("popover-session-item");
    expect(items).toHaveLength(10);
    expect(items[0]).toHaveTextContent("历史会话 0");
    // makeSession createdAt = 1 分钟前 → 相对时间渲染
    expect(items[0]).toHaveTextContent("1 分钟前");
    // 激活态高亮：当前会话 p3 行命中 bg-brand-soft
    const activeItem = items.find((el) => el.getAttribute("href") === "/chat/p3");
    expect(activeItem).toHaveClass("bg-brand-soft");
    // 点击条目：跳转目标由 href 承载（jsdom 下 next/link 不真导航），浮层关闭
    expect(items[0]).toHaveAttribute("href", "/chat/p0");
    fireEvent.click(items[0]);
    expect(screen.queryByTestId("collapsed-history-popover")).not.toBeInTheDocument();
  });

  it("收起态触屏退化：click 切换浮层（无 hover 依赖）", async () => {
    window.localStorage.setItem("cc.chat-sidebar.collapsed", "1");
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 10 });
    renderSidebar();
    const entry = await screen.findByTestId("collapsed-history-entry");
    fireEvent.click(entry);
    expect(screen.getByTestId("collapsed-history-popover")).toBeInTheDocument();
    fireEvent.click(entry);
    expect(screen.queryByTestId("collapsed-history-popover")).not.toBeInTheDocument();
  });

  it("收起态浮层搜索入口：打开统一搜索弹窗并收起浮层，遮罩点击关闭", async () => {
    window.localStorage.setItem("cc.chat-sidebar.collapsed", "1");
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 10 });
    renderSidebar();
    fireEvent.click(await screen.findByTestId("collapsed-history-entry"));
    // 浮层内搜索入口：打开统一弹窗且浮层收起
    fireEvent.click(screen.getByRole("button", { name: "搜索会话" }));
    expect(screen.queryByTestId("collapsed-history-popover")).not.toBeInTheDocument();
    expect(await screen.findByTestId("session-search-dialog")).toBeInTheDocument();
    // 遮罩点击关闭
    fireEvent.click(screen.getByRole("button", { name: "关闭搜索" }));
    await waitFor(() => {
      expect(screen.queryByTestId("session-search-dialog")).not.toBeInTheDocument();
    });
  });

  it("展开态：搜索按钮打开统一搜索弹窗（内嵌搜索面板下线）", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    // 内嵌面板已下线：不渲染旧面板根节点，改为搜索入口按钮
    const searchEntry = await screen.findByRole("button", { name: "搜索会话" });
    expect(screen.queryByTestId("session-search-panel")).toBeNull();
    fireEvent.click(searchEntry);
    expect(await screen.findByTestId("session-search-dialog")).toBeInTheDocument();
  });
});
