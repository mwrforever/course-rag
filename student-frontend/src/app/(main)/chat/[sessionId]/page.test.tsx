/**
 * 历史会话页 /chat/[sessionId] 测试（Task 12 本任务先做占位链路）
 *
 * 覆盖（设计 §1.5.4 + 续会话职责）：
 * - useChatStream 以 URL 参数 sessionId 初始化（历史会话归属，不发 replace URL）
 * - 空态占位「继续提问」（AI 徽标 + 建议 chip），Task 13 接入历史回显
 * - 输入发送携带该会话：send(query, attachments) 与状态渲染
 * - 上下文条标题「历史会话」，新建对话入口在场
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SessionChatPage from "./page";
import type { ChatStreamState, StreamMessage } from "@/hooks/use-chat-stream";

/** 流 hook mock：断言以 sessionId 初始化 + 可变 state */
const chatMock = vi.hoisted(() => ({
  useChatStream: vi.fn(),
  state: {} as ChatStreamState,
  send: vi.fn(),
  cancel: vi.fn(),
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
vi.mock("@/lib/api", () => ({}));
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

beforeEach(() => {
  chatMock.useChatStream.mockClear();
  chatMock.state = initialState();
  chatMock.send.mockReset().mockResolvedValue(undefined);
  chatMock.cancel.mockReset().mockResolvedValue(undefined);
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

describe("历史会话页", () => {
  it("useChatStream 以 URL 参数 sessionId 初始化（历史会话归属）", () => {
    render(<SessionChatPage />);
    expect(chatMock.useChatStream).toHaveBeenCalledWith("s-1");
  });

  it("上下文条：标题「历史会话」+ 新建对话入口", () => {
    render(<SessionChatPage />);
    expect(screen.getByText("历史会话")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "新建对话" })).toHaveAttribute("href", "/chat");
  });

  it("空态占位「继续提问」：AI 徽标 + 建议 chip（Task 13 接入历史回显）", () => {
    render(<SessionChatPage />);
    expect(screen.getByTestId("ai-badge")).toBeInTheDocument();
    expect(screen.getByText("继续提问")).toBeInTheDocument();
    expect(screen.getAllByTestId("suggestion-chip").length).toBeGreaterThanOrEqual(3);
  });

  it("输入发送：send(query, attachments) 走当前会话，不上传新会话 URL（无 replace）", async () => {
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
    render(<SessionChatPage />);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "接着上一个问题问" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    await waitFor(() => {
      expect(chatMock.send).toHaveBeenCalledWith("接着上一个问题问", []);
    });
    expect(screen.getByTestId("user-message")).toHaveTextContent("接着上一个问题问");
    expect(routerMock.replace).not.toHaveBeenCalled();
  });

  it("建议 chip 点击：立即发送（走当前会话）", async () => {
    render(<SessionChatPage />);
    fireEvent.click(screen.getAllByTestId("suggestion-chip")[0]);
    await waitFor(() => {
      expect(chatMock.send).toHaveBeenCalledWith(expect.any(String), []);
    });
  });

  it("流式消息渲染：AI 回答 + 打字光标 + end 后操作栏", () => {
    const assistant: StreamMessage = {
      id: "run-9",
      role: "assistant",
      content: "",
      attachments: [],
      model: "qwen3-8b",
      thinking: "",
      thinkingEnded: false,
      text: "历史续答内容",
      sources: [],
      tools: [],
      endStatus: "COMPLETED",
      messageId: "msg-9",
    };
    chatMock.state = {
      ...initialState(),
      messages: [assistant],
    };
    render(<SessionChatPage />);
    expect(screen.getByTestId("markdown-view")).toHaveTextContent("历史续答内容");
    expect(screen.getByRole("button", { name: /有用/ })).toBeInTheDocument();
  });
});
