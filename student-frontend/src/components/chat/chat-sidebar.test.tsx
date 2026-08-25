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

/** 数据层 mock：getSessions 会话列表（骨架/空/正常态） */
const apiMock = vi.hoisted(() => ({ getSessions: vi.fn() }));
/** 认证 mock：displayName / logout 可切 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
/** 导航 mock：pathname 驱动激活态；push 记录跳转（新建对话快捷键/登出） */
const navMock = vi.hoisted(() => ({ pathname: "/chat", push: vi.fn(), clear: vi.fn() }));

vi.mock("@/lib/api", () => ({ getSessions: apiMock.getSessions }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));
vi.mock("@tanstack/react-query", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@tanstack/react-query")>();
  return {
    ...actual,
    // 清缓存断言：QueryClient.clear 调用记录
    useQueryClient: () => ({ clear: navMock.clear }),
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
  authMock.useAuth.mockReset();
  navMock.push.mockReset();
  navMock.clear.mockReset();
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
    expect(second).toHaveClass("bg-brand-soft");
    expect(first).not.toHaveClass("bg-brand-soft");
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

  it("退出登录：登出清凭据 → 清查询缓存 → 跳登录页", async () => {
    apiMock.getSessions.mockResolvedValue({ records: [], total: "0", page: 1, size: 20 });
    renderSidebar();
    fireEvent.click(await screen.findByRole("button", { name: "退出登录" }));
    await waitFor(() => {
      expect(authMock.useAuth().logout).toHaveBeenCalled();
      expect(navMock.clear).toHaveBeenCalled();
      expect(navMock.push).toHaveBeenCalledWith("/login");
    });
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
    // 折叠态条目：仅图标链接（标题文字隐藏）
    const item = await screen.findByTestId("sidebar-session-item");
    expect(item).toHaveAttribute("href", "/chat/s1");
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
