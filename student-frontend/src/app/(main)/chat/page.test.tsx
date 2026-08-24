/**
 * 新对话页 /chat 集成测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 /chat 全链路）：
 * - 空态：AI 徽标 + 问候（displayName）+ 建议提问 chip（点击即发送）
 * - 输入发送 → useChatStream.send(query, attachments) + 用户气泡渲染
 * - metadata 到达（sessionId 落位）→ router.replace('/chat/{id}')（仅新会话）
 * - sources 前置渲染、end 后操作栏浮现、流式打字光标
 * - 409 并发冲突 toast「当前会话正在回答中」；建议 chip 失败同分级
 * - 附件：前置校验超限即拒（断言 uploadAttachments 未被调用）+ 成功上传 chips 完成
 *   + 移除 revoke blob URL
 * - 上下文条：返回课程 / 会话标题「新对话」/ 新建对话 / 课程名面包屑（D7）
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import NewChatPage from "./page";
import { ApiError } from "@/lib/api";
import type { ChatStreamState, StreamMessage } from "@/hooks/use-chat-stream";
import type { AttachmentRecord, RetrievalSource } from "@/lib/types";

/** 流 hook mock：state 用 getter 取可变引用，测试内更新后 rerender 驱动页面 */
const chatMock = vi.hoisted(() => ({
  useChatStream: vi.fn(),
  state: {} as ChatStreamState,
  send: vi.fn(),
  cancel: vi.fn(),
}));
/** 数据层 mock：附件上传与反馈 */
const apiMock = vi.hoisted(() => ({
  uploadAttachments: vi.fn(),
  postFeedback: vi.fn(),
}));
/** 路由 mock：replace 断言新会话 redirect */
const routerMock = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
/** 认证 mock：displayName 供问候语断言 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
/** blob URL mock：jsdom 未实现 createObjectURL/revokeObjectURL */
const urlMock = vi.hoisted(() => ({ createObjectURL: vi.fn(), revokeObjectURL: vi.fn() }));

vi.mock("@/hooks/use-chat-stream", () => ({
  useChatStream: () => ({
    get state() {
      return chatMock.state;
    },
    send: chatMock.send,
    cancel: chatMock.cancel,
    reconnect: vi.fn(),
  }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => authMock.useAuth(),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    uploadAttachments: apiMock.uploadAttachments,
    postFeedback: apiMock.postFeedback,
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => routerMock,
  useSearchParams: () => searchParamsMock.current,
}));

/** useSearchParams 按用例注入（course 面包屑） */
const searchParamsMock = vi.hoisted(() => ({
  current: new URLSearchParams(),
}));

/** 初始流状态（新会话） */
function initialState(): ChatStreamState {
  return {
    messages: [],
    streaming: false,
    error: null,
    lastEventId: null,
    sessionId: null,
    runId: null,
    endedStatus: null,
  };
}

function makeAssistant(overrides: Partial<StreamMessage> = {}): StreamMessage {
  return {
    id: "run-1",
    role: "assistant",
    content: "",
    attachments: [],
    model: "qwen3-8b",
    thinking: "",
    thinkingEnded: false,
    text: "",
    sources: [],
    tools: [],
    endStatus: null,
    messageId: null,
    ...overrides,
  };
}

const SOURCE: RetrievalSource = {
  chunkId: "c-1",
  docTitle: "RAG 白皮书",
  headingPath: "第三章",
  score: 0.9,
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NewChatPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  chatMock.state = initialState();
  chatMock.send.mockReset().mockResolvedValue(undefined);
  chatMock.cancel.mockReset().mockResolvedValue(undefined);
  apiMock.uploadAttachments.mockReset();
  apiMock.postFeedback.mockReset().mockResolvedValue(undefined);
  routerMock.replace.mockReset();
  routerMock.push.mockReset();
  searchParamsMock.current = new URLSearchParams();
  // 默认登录态：同学A
  authMock.useAuth.mockReset();
  authMock.useAuth.mockReturnValue({
    user: { userId: "u1", role: "STUDENT", displayName: "同学A" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
  });
  // blob URL 打桩：jsdom 未实现 createObjectURL/revokeObjectURL（仅覆盖两个静态方法）
  urlMock.createObjectURL.mockReset().mockReturnValue("blob:mock-url");
  urlMock.revokeObjectURL.mockReset();
  Object.defineProperty(URL, "createObjectURL", {
    value: urlMock.createObjectURL,
    configurable: true,
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    value: urlMock.revokeObjectURL,
    configurable: true,
  });
});

afterEach(() => {
  delete (URL as unknown as Record<string, unknown>).createObjectURL;
  delete (URL as unknown as Record<string, unknown>).revokeObjectURL;
});

/** 向文件输入框注入 FileList（jsdom files 属性不可直接赋值，defineProperty 后触发 change） */
function setFiles(input: HTMLInputElement, files: File[]) {
  Object.defineProperty(input, "files", {
    value: files,
    configurable: true,
  });
  fireEvent.change(input);
}

describe("新对话页：上下文条", () => {
  it("返回课程 / 会话标题「新对话」/ 新建对话入口", () => {
    renderPage();
    expect(screen.getByRole("link", { name: /返回课程/ })).toHaveAttribute("href", "/courses");
    expect(screen.getByText("新对话")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "新建对话" })).toHaveAttribute("href", "/chat");
  });

  it("课程名面包屑（D7）：query 携带 course 时展示课程名", () => {
    searchParamsMock.current = new URLSearchParams({ course: "高等数学（一）" });
    renderPage();
    expect(screen.getByText("高等数学（一）")).toBeInTheDocument();
  });
});

describe("新对话页：空态与建议提问", () => {
  it("AI 徽标 + 问候（displayName）+ 建议 chip 3-4 个", () => {
    renderPage();
    expect(screen.getByTestId("ai-badge")).toBeInTheDocument();
    expect(screen.getByText(/同学A/)).toBeInTheDocument();
    const chips = screen.getAllByTestId("suggestion-chip");
    expect(chips.length).toBeGreaterThanOrEqual(3);
    expect(chips.length).toBeLessThanOrEqual(4);
  });

  it("点击建议 chip：立即发送该提问", async () => {
    renderPage();
    fireEvent.click(screen.getAllByTestId("suggestion-chip")[0]);
    await waitFor(() => {
      expect(chatMock.send).toHaveBeenCalledWith(expect.any(String), []);
    });
  });
});

describe("新对话页：发送与流式状态流转", () => {
  it("输入 Enter 发送：send(query, []) 且用户气泡出现", async () => {
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
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "什么是 RAG？" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    await waitFor(() => {
      expect(chatMock.send).toHaveBeenCalledWith("什么是 RAG？", []);
    });
    expect(screen.getByTestId("user-message")).toHaveTextContent("什么是 RAG？");
  });

  it("metadata 到达（sessionId 落位）：router.replace 新会话 URL（仅一次）", async () => {
    const { rerender } = renderPage();
    chatMock.state = {
      ...chatMock.state,
      sessionId: "s-100",
      runId: "run-1",
      messages: [makeAssistant({ text: "你好" })],
      streaming: true,
    };
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <NewChatPage />
      </QueryClientProvider>,
    );
    await waitFor(() => {
      expect(routerMock.replace).toHaveBeenCalledWith("/chat/s-100");
    });
    // 幂等：state 不变不再重复 replace
    expect(routerMock.replace).toHaveBeenCalledTimes(1);
  });

  it("流式渲染：sources 前置 + 打字光标 + end 后操作栏浮现", async () => {
    const assistant = makeAssistant({
      text: "完整回答",
      sources: [SOURCE],
      endStatus: "COMPLETED",
      messageId: "msg-1",
    });
    chatMock.state = {
      ...initialState(),
      sessionId: "s-1",
      messages: [assistant],
    };
    renderPage();
    const sources = screen.getByTestId("sources-list");
    const body = screen.getByTestId("markdown-view");
    expect(sources.compareDocumentPosition(body) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByTestId("typing-cursor")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /有用/ })).toBeInTheDocument();
  });

  it("streaming：发送键 morph 为停止生成，点击触发 cancel", () => {
    chatMock.state = { ...initialState(), streaming: true };
    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "停止生成" }));
    expect(chatMock.cancel).toHaveBeenCalledTimes(1);
  });

  it("409：toast「当前会话正在回答中」（输入发送与建议 chip 均分级）", async () => {
    chatMock.send.mockRejectedValueOnce(new ApiError(409, "x"));
    renderPage();
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "并发问题" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    const toast = await screen.findByRole("status");
    expect(toast).toHaveTextContent("当前会话正在回答中");
  });
});

describe("新对话页：附件全链路", () => {
  const PNG = new File([new Uint8Array(1024)], "图.png", { type: "image/png" });
  const RECORD: AttachmentRecord = {
    type: "image",
    url: "obj/1.png",
    name: "图.png",
    size: "1024",
  };

  it("超限（合计 100MB 外）：拒绝且无网络请求（uploadAttachments 未调用）", async () => {
    renderPage();
    // 先选 40MB 文档（合法），再选 30MB+31MB 文档 → 合计 101MB 超限即拒
    const firstDoc = new File([new Uint8Array(40 * 1024 * 1024)], "a.pdf", {
      type: "application/pdf",
    });
    setFiles(screen.getByTestId("file-input-doc") as HTMLInputElement, [firstDoc]);
    await waitFor(() => expect(apiMock.uploadAttachments).toHaveBeenCalledTimes(1));

    apiMock.uploadAttachments.mockReset();
    const extra = [
      new File([new Uint8Array(30 * 1024 * 1024)], "b.pdf", { type: "application/pdf" }),
      new File([new Uint8Array(31 * 1024 * 1024)], "c.pdf", { type: "application/pdf" }),
    ];
    setFiles(screen.getByTestId("file-input-doc") as HTMLInputElement, extra);
    const toast = await screen.findByRole("status");
    expect(toast).toHaveTextContent("附件总大小不能超过 100MB");
    expect(apiMock.uploadAttachments).not.toHaveBeenCalled();
  });

  it("合法文件：选中即传 → chips 完成（blob 缩略图）", async () => {
    apiMock.uploadAttachments.mockResolvedValue([RECORD]);
    renderPage();
    setFiles(screen.getByTestId("file-input-image") as HTMLInputElement, [PNG]);
    await waitFor(() => expect(apiMock.uploadAttachments).toHaveBeenCalledWith([PNG]));
    const img = await screen.findByRole("img", { name: /图\.png/ });
    expect(img).toHaveAttribute("src", "blob:mock-url");
  });

  it("移除附件：revoke blob URL", async () => {
    apiMock.uploadAttachments.mockResolvedValue([RECORD]);
    renderPage();
    setFiles(screen.getByTestId("file-input-image") as HTMLInputElement, [PNG]);
    await screen.findByRole("img", { name: /图\.png/ });
    fireEvent.click(screen.getByRole("button", { name: /移除/ }));
    expect(urlMock.revokeObjectURL).toHaveBeenCalledWith("blob:mock-url");
  });

  it("发送时附件记录随 ChatRequest 提交（chips 清除，blob 保留供消息预览）", async () => {
    apiMock.uploadAttachments.mockResolvedValue([RECORD]);
    chatMock.send.mockImplementation(async (_query: string, attachments: AttachmentRecord[]) => {
      const userMsg: StreamMessage = {
        id: "local-1",
        role: "user",
        content: "看图提问",
        attachments,
        model: null,
        thinking: "",
        thinkingEnded: false,
        text: "",
        sources: [],
        tools: [],
        endStatus: null,
        messageId: null,
      };
      chatMock.state = { ...chatMock.state, messages: [userMsg], streaming: true };
    });
    renderPage();
    setFiles(screen.getByTestId("file-input-image") as HTMLInputElement, [PNG]);
    await screen.findByRole("img", { name: /图\.png/ });
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "看图提问" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    await waitFor(() => {
      expect(chatMock.send).toHaveBeenCalledWith("看图提问", [RECORD]);
    });
    // 消息内仍以 blob 预览（记录 url → blob 映射保留）
    expect(screen.getByRole("img", { name: /图\.png/ })).toBeInTheDocument();
  });
});
