/**
 * 历史会话页 /chat/[sessionId] 测试（Task 12 占位链路 + Task 13 历史回显）
 *
 * 覆盖（设计 §1.5.4 + §六.6 R1 历史回显）：
 * - useChatStream 以 URL 参数 sessionId 初始化（历史会话归属，不发 replace URL）
 * - 历史消息拉取 getSessionMessages(sessionId, 1, 200) → historyAdapter 回显：
 *   用户附件 chips 无缩略图（G8 降级图标）、思考卡、来源卡、工具卡、正文、反馈操作栏
 * - 历史加载中 → 骨架；加载失败 → 横幅 + 重试闭环；空历史 → 「继续提问」空态
 * - M4 历史徽标：CANCELLED/ERROR run 半截回答回显未完成徽标（+ errorMessage tooltip）
 * - 输入发送走当前会话：display = 历史 + 新流消息
 * - 多会话并发续流（2026-09-01 用户拍板）：getActiveRun 命中 → resume 全量回放续流；
 *   无活跃 run / 查询失败 → 纯历史回显不续流
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
  resume: vi.fn(),
  detach: vi.fn(),
}));
/** 数据层 mock：历史消息 + 活跃 run 接口 */
const apiMock = vi.hoisted(() => ({
  getSessionMessages: vi.fn(),
  getActiveRun: vi.fn(),
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
      resume: chatMock.resume,
      detach: chatMock.detach,
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
    getActiveRun: apiMock.getActiveRun,
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
    thinkingStage: null,
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
  chatMock.resume.mockReset().mockResolvedValue(undefined);
  chatMock.detach.mockReset();
  apiMock.getSessionMessages.mockReset().mockResolvedValue(emptyPage());
  apiMock.getActiveRun.mockReset().mockResolvedValue(null);
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

  it("上下文条：标题「历史会话」（顶栏新建按钮已删，新建入口在侧栏；Task 13）", () => {
    renderPage();
    expect(screen.getByText("历史会话")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "新建对话" })).not.toBeInTheDocument();
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
    // AI 消息：时间轴（思考节点默认收起 + 工具步骤 + 检索步骤）+ 正文
    // 思考步骤：历史行 ended=true 呈「思考已完成」；工具步骤并入时间轴（人话标签）
    expect(screen.getByTestId("thinking-step")).toBeInTheDocument();
    expect(screen.getByText("思考已完成")).toBeInTheDocument();
    expect(screen.getByTestId("tool-step")).toHaveTextContent("检索课程知识库");
    expect(screen.getByTestId("sources-step")).toHaveTextContent("已检索");
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

  it("M4 历史徽标：CANCELLED run 半截回答回显「已停止生成」徽标（失败现场保留）", async () => {
    apiMock.getSessionMessages.mockResolvedValue({
      records: [
        makeHistoryRow({
          id: "u-1",
          role: "USER",
          content: "被停止的问题",
          runId: "hrun-cancel",
          seq: 1,
        }),
        makeHistoryRow({
          id: "a-1",
          runId: "hrun-cancel",
          seq: 2,
          content: "半截回答",
          runStatus: "CANCELLED",
        }),
      ],
      total: "2",
      page: 1,
      size: 200,
    });
    renderPage();
    // 半截正文回显 + 未完成徽标在场（历史侧 CANCELLED 补实时「已停止生成」同款文案）
    expect(await screen.findByTestId("markdown-view")).toHaveTextContent("半截回答");
    expect(screen.getByTestId("incomplete-badge-cancelled")).toBeInTheDocument();
    expect(screen.getByTestId("incomplete-badge-cancelled")).toHaveTextContent("已停止生成");
    expect(screen.queryByTestId("incomplete-badge-error")).not.toBeInTheDocument();
  });

  it("M4 历史徽标：ERROR run 回显「生成失败」徽标 + errorMessage tooltip", async () => {
    apiMock.getSessionMessages.mockResolvedValue({
      records: [
        makeHistoryRow({
          id: "u-1",
          role: "USER",
          content: "失败的问题",
          runId: "hrun-err",
          seq: 1,
        }),
        makeHistoryRow({
          id: "a-1",
          runId: "hrun-err",
          seq: 2,
          content: "失败前的半截",
          runStatus: "ERROR",
          errorMessage: "模型调用失败",
        }),
      ],
      total: "2",
      page: 1,
      size: 200,
    });
    renderPage();
    expect(await screen.findByTestId("incomplete-badge-error")).toHaveTextContent("生成失败");
    // 错误文案经 title 属性透出（tooltip）
    expect(screen.getByTestId("incomplete-badge-error")).toHaveAttribute("title", "模型调用失败");
    expect(screen.queryByTestId("incomplete-badge-cancelled")).not.toBeInTheDocument();
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
            text: "",
            sources: [],
            timeline: [],
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

describe("历史会话页：多会话并发续流（2026-09-01 用户拍板）", () => {
  it("有活跃 run：getActiveRun 命中 → resume(runId) 全量回放续流", async () => {
    apiMock.getActiveRun.mockResolvedValue("run-42");
    renderPage();
    await waitFor(() => {
      expect(apiMock.getActiveRun).toHaveBeenCalledWith("s-1");
    });
    // 工作区经 resumeRunId prop 触发续流（restore 进行中回答的实时视图）
    await waitFor(() => {
      expect(chatMock.resume).toHaveBeenCalledWith("run-42");
    });
  });

  it("无活跃 run：不发起续流（纯历史回显，仅拉历史消息）", async () => {
    apiMock.getActiveRun.mockResolvedValue(null);
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("chat-workspace")).toBeInTheDocument();
    });
    // 页面不会因为刷新/重渲染重复调用 resume
    expect(chatMock.resume).not.toHaveBeenCalled();
  });

  it("活跃 run 查询失败：退化为纯历史回显，不阻断页面（404/403/网络错误统一按无活跃处理）", async () => {
    apiMock.getActiveRun.mockRejectedValue(new ApiError(404, "会话不存在"));
    renderPage();
    // 历史仍正常回显（续流失败不阻断页面）
    expect(await screen.findByText("继续提问")).toBeInTheDocument();
    expect(chatMock.resume).not.toHaveBeenCalled();
  });

  it("M6.4 占位：活跃 run 存在且历史为空 → 渲染「正在继续生成…」占位（续流窗口期不像坏了）", async () => {
    // getSessionMessages 默认空页（beforeEach）+ getActiveRun 命中 → 回放尚未送达任何帧，
    // 消息区应为续流占位而非普通「继续提问」空态
    apiMock.getActiveRun.mockResolvedValue("run-42");
    renderPage();
    const placeholder = await screen.findByTestId("resume-placeholder");
    expect(placeholder).toHaveTextContent("正在继续生成");
    // 普通空态的问候与建议 chip 不与占位并存（占位优先于普通空态渲染）
    expect(screen.queryByText("继续提问")).not.toBeInTheDocument();
    expect(screen.queryByTestId("suggestion-chip")).not.toBeInTheDocument();
  });

  it("M6.4 静默降级：active-run 查询失败 → 无占位无报错，纯历史回显", async () => {
    // resume 失败静默降级为「仅历史展示 + 不报错」：查询失败统一 null（api 层契约），
    // 占位不渲染、无 error banner，页面回到普通空态
    apiMock.getActiveRun.mockRejectedValue(new TypeError("网络不可达"));
    renderPage();
    expect(await screen.findByText("继续提问")).toBeInTheDocument();
    expect(screen.queryByTestId("resume-placeholder")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
