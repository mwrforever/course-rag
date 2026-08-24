/**
 * 历史会话页 /chat/[sessionId] 测试（Task 12 占位链路 + Task 13 历史回显）
 *
 * 覆盖（设计 §1.5.4 + §六.6 R1 历史回显）：
 * - useChatStream 以 URL 参数 sessionId 初始化（历史会话归属，不发 replace URL）
 * - 历史消息拉取 getSessionMessages(sessionId, 1, 200) → historyAdapter 回显：
 *   用户附件 chips 无缩略图（G8 降级图标）、思考卡、来源卡、工具卡、正文、反馈操作栏
 * - 历史加载中 → 骨架；加载失败 → 横幅 + 重试闭环；空历史 → 「继续提问」空态
 * - 输入发送走当前会话：display = 历史 + 新流消息
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SessionChatPage from "./page";
import { ApiError } from "@/lib/api";
import type { ChatStreamState } from "@/hooks/use-chat-stream";
import type { StudentMessage } from "@/lib/types";

/** 流 hook mock：断言以 sessionId 初始化 + 可变 state */
const chatMock = vi.hoisted(() => ({
  useChatStream: vi.fn(),
  state: {} as ChatStreamState,
  send: vi.fn(),
  cancel: vi.fn(),
}));
/** 数据层 mock：历史消息接口 */
const apiMock = vi.hoisted(() => ({
  getSessionMessages: vi.fn(),
  postFeedback: vi.fn(),
}));
/** 路由 mock：历史会话不应触发 replace */
const routerMock = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
/** 认证 mock：displayName 供问候语断言 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));

vi.mock("@/hooks/use-chat-stream", () => ({
  useChatStream: (initialSessionId: string | null) => {
    chatMock.useChatStream(initialSessionId);
    return {
      get state() {
        return chatMock.state;
      },
      send: chatMock.send,
      cancel: chatMock.cancel,
      reconnect: vi.fn(),
    };
  },
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => authMock.useAuth(),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    getSessionMessages: apiMock.getSessionMessages,
    postFeedback: apiMock.postFeedback,
  };
});
vi.mock("next/navigation", () => ({
  useParams: () => ({ sessionId: "s-1" }),
  useRouter: () => routerMock,
  useSearchParams: () => new URLSearchParams(),
}));

/** 历史会话初始状态（sessionId 已归属，无 replace 需求） */
function initialState(): ChatStreamState {
  return {
    messages: [],
    streaming: false,
    error: null,
    lastEventId: null,
    sessionId: "s-1",
    runId: null,
    endedStatus: null,
  };
}

/** 历史消息行工厂（默认 ASSISTANT 正文行） */
function makeHistoryRow(
  overrides: Partial<StudentMessage> & { role?: string } = {},
): StudentMessage {
  return {
    id: "hm-1",
    role: "ASSISTANT",
    content: "",
    messageType: null,
    intentType: "knowledge_question",
    runId: "hrun-1",
    seq: 1,
    createdAt: "2026-08-24T10:00:00",
    sources: [],
    attachments: [],
    ...overrides,
  };
}

/** 空分页响应 */
function emptyPage() {
  return { records: [], total: "0", page: 1, size: 200 };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionChatPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  chatMock.useChatStream.mockClear();
  chatMock.state = initialState();
  chatMock.send.mockReset().mockResolvedValue(undefined);
  chatMock.cancel.mockReset().mockResolvedValue(undefined);
  apiMock.getSessionMessages.mockReset().mockResolvedValue(emptyPage());
  apiMock.postFeedback.mockReset().mockResolvedValue(undefined);
  routerMock.replace.mockReset();
  authMock.useAuth.mockReset();
  authMock.useAuth.mockReturnValue({
    user: { userId: "u1", role: "STUDENT", displayName: "同学A" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("历史会话页：初始归属与拉取", () => {
  it("useChatStream 以 URL 参数 sessionId 初始化（历史会话归属）", () => {
    renderPage();
    expect(chatMock.useChatStream).toHaveBeenCalledWith("s-1");
  });

  it("拉取历史消息：getSessionMessages(s-1, 1, 200)", async () => {
    renderPage();
    await waitFor(() => {
      expect(apiMock.getSessionMessages).toHaveBeenCalledWith("s-1", 1, 200);
    });
  });

  it("历史加载中：消息区渲染骨架", () => {
    apiMock.getSessionMessages.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId("chat-skeleton")).toBeInTheDocument();
  });

  it("历史加载失败：横幅 + 重试闭环恢复", async () => {
    apiMock.getSessionMessages
      .mockRejectedValueOnce(new ApiError(503, "服务暂时不可用"))
      .mockResolvedValueOnce({
        records: [makeHistoryRow({ id: "hm-1", content: "恢复后的回答" })],
        total: "1",
        page: 1,
        size: 200,
      });
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByTestId("markdown-view")).toHaveTextContent("恢复后的回答");
  });

  it("上下文条：标题「历史会话」+ 新建对话入口（历史加载期恒在场）", () => {
    renderPage();
    expect(screen.getByText("历史会话")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "新建对话" })).toHaveAttribute("href", "/chat");
  });
});

describe("历史会话页：历史回显渲染", () => {
  it("空历史：空态占位「继续提问」+ 建议 chip", async () => {
    renderPage();
    expect(await screen.findByText("继续提问")).toBeInTheDocument();
    expect(screen.getByTestId("ai-badge")).toBeInTheDocument();
    expect(screen.getAllByTestId("suggestion-chip").length).toBeGreaterThanOrEqual(3);
  });

  it("历史消息按行还原：用户附件 chips 无缩略图（G8）+ AI 思考卡/来源卡/工具卡/正文/操作栏", async () => {
    apiMock.getSessionMessages.mockResolvedValue({
      records: [
        makeHistoryRow({
          id: "u-1",
          role: "USER",
          content: "什么是 RAG？",
          runId: "hrun-1",
          seq: 1,
          attachments: [
            { type: "image", url: "obj/1.png", name: "图.png", size: "1024" },
            { type: "document", url: "obj/2.pdf", name: "讲义.pdf", size: "2048" },
          ],
        }),
        makeHistoryRow({
          id: "t-1",
          runId: "hrun-1",
          seq: 2,
          messageType: "thinking",
          content: "先查资料",
        }),
        makeHistoryRow({
          id: "c-1",
          runId: "hrun-1",
          seq: 3,
          messageType: "TOOL_CALL",
          content: JSON.stringify({
            toolCallId: "t1",
            toolName: "searchKnowledge",
            input: { query: "RAG" },
          }),
        }),
        makeHistoryRow({
          id: "r-1",
          runId: "hrun-1",
          seq: 4,
          messageType: "TOOL_RESULT",
          content: JSON.stringify({ toolCallId: "t1", status: "success", output: "命中 3 条" }),
        }),
        makeHistoryRow({
          id: "a-1",
          runId: "hrun-1",
          seq: 5,
          content: "RAG 是检索增强生成。",
          intentType: "knowledge_question",
          sources: [{ chunkId: "c-9", docTitle: "RAG 白皮书", headingPath: "第三章", score: 0.9 }],
        }),
      ],
      total: "5",
      page: 1,
      size: 200,
    });
    renderPage();
    // 用户消息：正文 + 附件 chips（无 img 缩略图，G8 降级图标 + 文件名）
    expect(await screen.findByTestId("user-message")).toHaveTextContent("什么是 RAG？");
    expect(screen.getByText("图.png")).toBeInTheDocument();
    expect(screen.getByText("讲义.pdf")).toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    // AI 消息：思考卡（已思考折叠）+ 来源卡 + 工具卡（人话标签 + success 摘要）+ 正文
    expect(screen.getByTestId("thinking-card")).toHaveTextContent("先查资料");
    expect(screen.getByTestId("sources-list")).toHaveTextContent("RAG 白皮书");
    expect(screen.getByTestId("tool-card")).toHaveTextContent("检索课程知识库");
    expect(screen.getByTestId("tool-success")).toBeInTheDocument();
    expect(screen.getByTestId("markdown-view")).toHaveTextContent("RAG 是检索增强生成。");
    // 操作栏：历史消息 messageId 透传 → 反馈按钮在场
    expect(screen.getByRole("button", { name: /有用/ })).toBeInTheDocument();
  });

  it("意图透传：历史 intentType=chat（无来源）时反馈请求携带 chat", async () => {
    apiMock.getSessionMessages.mockResolvedValue({
      records: [
        makeHistoryRow({
          id: "a-1",
          runId: "hrun-1",
          seq: 1,
          content: "闲聊回答",
          intentType: "chat",
        }),
      ],
      total: "1",
      page: 1,
      size: 200,
    });
    renderPage();
    await screen.findByTestId("markdown-view");
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    await waitFor(() => {
      expect(apiMock.postFeedback).toHaveBeenCalledWith({
        sessionId: "s-1",
        messageId: "a-1",
        isLiked: true,
        intentType: "chat",
      });
    });
  });

  it("输入发送走当前会话：display = 历史 + 新流消息（新提问追加其后）", async () => {
    apiMock.getSessionMessages.mockResolvedValue({
      records: [makeHistoryRow({ id: "hm-1", runId: "hrun-1", seq: 1, content: "历史回答" })],
      total: "1",
      page: 1,
      size: 200,
    });
    chatMock.send.mockImplementation(async (query: string) => {
      chatMock.state = {
        ...chatMock.state,
        messages: [
          {
            id: "local-1",
            role: "user",
            content: query,
            attachments: [],
            model: null,
            thinking: "",
            thinkingEnded: false,
            text: "",
            sources: [],
            tools: [],
            endStatus: null,
            messageId: null,
          },
        ],
        streaming: true,
      };
    });
    renderPage();
    await screen.findByTestId("markdown-view");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "接着问" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    await waitFor(() => {
      expect(chatMock.send).toHaveBeenCalledWith("接着问", []);
    });
    // 历史与会话新消息同屏（历史在前、新提问在后）
    const flow = screen.getByTestId("message-flow");
    expect(flow).toHaveTextContent("历史回答");
    expect(screen.getByTestId("user-message")).toHaveTextContent("接着问");
    expect(routerMock.replace).not.toHaveBeenCalled();
  });
});
