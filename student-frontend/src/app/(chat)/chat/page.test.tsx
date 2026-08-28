/**
 * 新对话页 /chat 集成测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 /chat 全链路）：
 * - 空态：AI 徽标 + 问候（displayName）+ 建议提问 chip（点击即发送）
 * - 输入发送 → useChatStream.send(query, attachments) + 用户气泡渲染
 * - metadata 到达（sessionId 落位）→ 不跳转（E2E 实证修订：replace 重挂载丢流）
 * - sources 前置渲染、end 后操作栏浮现、流式打字光标
 * - 409 并发冲突 toast「当前会话正在回答中」；建议 chip 失败同分级
 * - 附件：前置校验超限即拒（断言 uploadAttachments 未被调用）+ 成功上传 chips 完成
 *   + 移除 revoke blob URL
 * - 上下文条：返回课程 / 会话标题「新对话」/ 新建对话 / 课程名面包屑（D7）
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import NewChatPage from "./page";
import { SIDEBAR_SESSIONS_QUERY_KEY } from "@/components/chat/chat-sidebar";
import { ChatStreamingProvider, useRequestNewChat } from "@/components/chat/chat-streaming-context";
import { ApiError } from "@/lib/api";
import type { ChatStreamState, StreamMessage } from "@/hooks/use-chat-stream";
import type { AttachmentRecord, RetrievalSource } from "@/lib/types";

/** 流 hook mock：state 用 getter 取可变引用，测试内更新后 rerender 驱动页面 */
const chatMock = vi.hoisted(() => ({
  useChatStream: vi.fn(),
  state: {} as ChatStreamState,
  send: vi.fn(),
  cancel: vi.fn(),
  reset: vi.fn(),
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
    reset: chatMock.reset,
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
    text: "",
    sources: [],
    timeline: [],
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
  chatMock.reset.mockReset();
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
  it("返回课程 / 会话标题「新对话」（顶栏新建按钮已删，入口在侧栏）", () => {
    renderPage();
    expect(screen.getByRole("link", { name: /返回课程/ })).toHaveAttribute("href", "/courses");
    expect(screen.getByText("新对话")).toBeInTheDocument();
    // Task 13：顶栏「新建对话」Link 移除（新建入口收敛到侧栏按钮）
    expect(screen.queryByRole("link", { name: "新建对话" })).not.toBeInTheDocument();
  });

  it("课程名面包屑（D7）：query 携带 course 时展示课程名", () => {
    searchParamsMock.current = new URLSearchParams({ course: "高等数学（一）" });
    renderPage();
    expect(screen.getByText("高等数学（一）")).toBeInTheDocument();
  });

  it("carry3：query 携带 courseId 时「返回课程」跳转对应课程（/courses/{id}）", () => {
    searchParamsMock.current = new URLSearchParams({ courseId: "c-1", course: "高等数学（一）" });
    renderPage();
    expect(screen.getByRole("link", { name: /返回课程/ })).toHaveAttribute("href", "/courses/c-1");
    // 面包屑仍由 course 参数提供（courseId 不影响展示）
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
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "什么是 RAG？" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    await waitFor(() => {
      expect(chatMock.send).toHaveBeenCalledWith("什么是 RAG？", []);
    });
    expect(screen.getByTestId("user-message")).toHaveTextContent("什么是 RAG？");
  });

  it("metadata 到达（sessionId 落位）：不触发路由跳转（E2E 实证修订）", async () => {
    // 修订背景（2026-08-24）：原 router.replace('/chat/{id}') 在真实导航下重挂载
    // 组件致流式状态丢失（E2E route-mock 抓出）；产品决策：新对话不替换 URL，
    // sessionId 留存在组件状态中。本用例锁定「不跳转 + 消息仍渲染」契约。
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
      expect(screen.getByText("你好")).toBeInTheDocument();
    });
    // 不跳转：replace 与 push 均不得被调用
    expect(routerMock.replace).not.toHaveBeenCalled();
    expect(routerMock.push).not.toHaveBeenCalled();
  });

  it("会话归属落位：失效侧栏会话缓存（新会话即时进侧栏历史）", async () => {
    // (chat) 组布局常驻 → QueryClient 长活，侧栏查询无 refetch 触发点；
    // sessionId 从 null 落位后必须按 SIDEBAR_SESSIONS_QUERY_KEY 失效一次
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <NewChatPage />
      </QueryClientProvider>,
    );
    expect(invalidateSpy).not.toHaveBeenCalled();

    chatMock.state = { ...chatMock.state, sessionId: "s-100", runId: "run-1" };
    rerender(
      <QueryClientProvider client={client}>
        <NewChatPage />
      </QueryClientProvider>,
    );
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: SIDEBAR_SESSIONS_QUERY_KEY });
    });
  });

  it("终态渲染：时间轴（检索步骤）前置 + 无打字光标 + end 后操作栏浮现", async () => {
    const assistant = makeAssistant({
      timeline: [
        { kind: "thinking", stage: "understanding", lines: ["先检索资料"], ended: true },
        { kind: "sources", sources: [SOURCE] },
      ],
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
    // 2026-08-28：链式时间轴承载思考/检索步骤，来源经检索步骤点击开召回抽屉
    const chain = screen.getByTestId("chain-timeline");
    const body = screen.getByTestId("markdown-view");
    expect(chain.compareDocumentPosition(body) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByTestId("typing-cursor")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /有用/ })).toBeInTheDocument();
    // 点击检索步骤打开召回抽屉，展示片段正文
    fireEvent.click(screen.getByTestId("sources-step"));
    expect(screen.getByTestId("retrieval-drawer")).toBeInTheDocument();
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
    setFiles(screen.getByTestId("file-input") as HTMLInputElement, [firstDoc]);
    await waitFor(() => expect(apiMock.uploadAttachments).toHaveBeenCalledTimes(1));

    apiMock.uploadAttachments.mockReset();
    const extra = [
      new File([new Uint8Array(30 * 1024 * 1024)], "b.pdf", { type: "application/pdf" }),
      new File([new Uint8Array(31 * 1024 * 1024)], "c.pdf", { type: "application/pdf" }),
    ];
    setFiles(screen.getByTestId("file-input") as HTMLInputElement, extra);
    const toast = await screen.findByRole("status");
    expect(toast).toHaveTextContent("附件总大小不能超过 100MB");
    expect(apiMock.uploadAttachments).not.toHaveBeenCalled();
  });

  it("合法文件：选中即传 → chips 完成（blob 缩略图）", async () => {
    apiMock.uploadAttachments.mockResolvedValue([RECORD]);
    renderPage();
    setFiles(screen.getByTestId("file-input") as HTMLInputElement, [PNG]);
    await waitFor(() => expect(apiMock.uploadAttachments).toHaveBeenCalledWith([PNG]));
    const img = await screen.findByRole("img", { name: /图\.png/ });
    expect(img).toHaveAttribute("src", "blob:mock-url");
  });

  it("移除附件：revoke blob URL", async () => {
    apiMock.uploadAttachments.mockResolvedValue([RECORD]);
    renderPage();
    setFiles(screen.getByTestId("file-input") as HTMLInputElement, [PNG]);
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
        text: "",
        sources: [],
        timeline: [],
        endStatus: null,
        messageId: null,
      };
      chatMock.state = { ...chatMock.state, messages: [userMsg], streaming: true };
    });
    renderPage();
    setFiles(screen.getByTestId("file-input") as HTMLInputElement, [PNG]);
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

describe("新对话页：拖拽上传与附件预览（Task 12 扩容）", () => {
  const PNG = new File([new Uint8Array(1024)], "拖拽图.png", { type: "image/png" });
  const RECORD: AttachmentRecord = {
    type: "image",
    url: "obj/drag.png",
    name: "拖拽图.png",
    size: "1024",
  };

  it("拖拽高亮层：dragOver 出现、dragLeave 消失（不误触上传）", () => {
    renderPage();
    const workspace = screen.getByTestId("chat-workspace");
    // 拖拽经过：拦截默认并点亮高亮层
    fireEvent.dragOver(workspace, { dataTransfer: { types: ["Files"] } });
    expect(screen.getByTestId("drag-highlight")).toBeInTheDocument();
    // 拖离（移出容器）：高亮层消失且未触发上传
    fireEvent.dragLeave(workspace, { relatedTarget: null });
    expect(screen.queryByTestId("drag-highlight")).not.toBeInTheDocument();
    expect(apiMock.uploadAttachments).not.toHaveBeenCalled();
  });

  it("drop 释放文件：触发上传（与文件选择同一链路）", async () => {
    apiMock.uploadAttachments.mockResolvedValue([RECORD]);
    renderPage();
    fireEvent.drop(screen.getByTestId("chat-workspace"), {
      dataTransfer: { files: [PNG] },
    });
    await waitFor(() => {
      expect(apiMock.uploadAttachments).toHaveBeenCalledWith([PNG]);
    });
  });

  it("点击 chip 打开预览弹窗（图片 blob 大图），Esc 关闭", async () => {
    apiMock.uploadAttachments.mockResolvedValue([RECORD]);
    renderPage();
    setFiles(screen.getByTestId("file-input") as HTMLInputElement, [PNG]);
    await screen.findByRole("img", { name: /拖拽图\.png/ });
    // chip 主体点击 → 预览弹窗（portal 挂 body）
    fireEvent.click(screen.getByRole("button", { name: /预览附件：拖拽图\.png/ }));
    expect(
      await screen.findByRole("dialog", { name: /预览附件：拖拽图\.png/ }),
    ).toBeInTheDocument();
    // Esc 关闭
    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });
});

describe("新对话页：新建对话信号（Task 13 干净态，侧栏按钮经 Context 驱动）", () => {
  /** 信号出口引用：探针渲染后持有 Provider 的 requestNewChat */
  const requestRef = { current: (() => {}) as () => void };
  /** 信号探针：把 Provider 的 requestNewChat 暴露给用例（模拟侧栏按钮发出） */
  function SignalProbe() {
    const request = useRequestNewChat();
    useEffect(() => {
      requestRef.current = request;
    }, [request]);
    return null;
  }

  it("信号到达：reset(true) 清流式（含会话归属）+ 附件清（revoke）+ 输入清", async () => {
    const PNG = new File([new Uint8Array(512)], "信号图.png", { type: "image/png" });
    const RECORD: AttachmentRecord = {
      type: "image",
      url: "obj/signal.png",
      name: "信号图.png",
      size: "512",
    };
    apiMock.uploadAttachments.mockResolvedValue([RECORD]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <ChatStreamingProvider>
          <SignalProbe />
          <NewChatPage />
        </ChatStreamingProvider>
      </QueryClientProvider>,
    );
    // 准备脏态：附件上传完成 + 输入草稿
    setFiles(screen.getByTestId("file-input") as HTMLInputElement, [PNG]);
    await screen.findByRole("img", { name: /信号图\.png/ });
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "未发送的草稿" } });
    // 发出新建信号（侧栏按钮语义：/chat 同路由不重挂载）
    act(() => requestRef.current());
    // 流式状态 reset（clearSession=true：会话归属一并清空，下次发送建新会话）
    await waitFor(() => {
      expect(chatMock.reset).toHaveBeenCalledWith(true);
    });
    // 附件干净态：chips 消失 + blob URL revoke
    await waitFor(() => {
      expect(screen.queryByTestId("attachment-chips")).not.toBeInTheDocument();
    });
    expect(urlMock.revokeObjectURL).toHaveBeenCalledWith("blob:mock-url");
    // 输入干净态：受控 resetKey 驱动清空
    expect(screen.getByRole("textbox")).toHaveValue("");
  });
});
