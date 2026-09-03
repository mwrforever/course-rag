/**
 * 消息流测试（2026-08-28 时间线改版：链式时间轴挂链 + 答案块；
 * 2026-08-30 对齐设计稿：无 stage/query_plan 步骤、工具步骤开结果抽屉）
 *
 * 覆盖（设计 §1.5.4 消息流 + §1.6 动效）：
 * - 用户消息：右对齐 bubble 气泡（rounded-br 形状锁例外）+ 附件缩略 chips
 * - AI 消息：模型徽标 → 链式时间轴（思考/检索/工具挂链）→
 *   答案块（左渐变竖线）→ 操作栏的组合顺序；来源步骤点击开召回抽屉、
 *   工具步骤点击开工具结果抽屉
 * - 流式空窗三点脉冲（时间轴与正文皆空）
 * - 流式打字光标（streaming 时存在）
 * - 「停止」提示（2026-09-03 图 4 拍板）：CANCELLED 终态正文不拼后缀，操作栏之后
 *   渲染整块底部小字（stopped-hint）；反馈入口不隐藏
 * - 智能吸底滚动判定纯函数（仅底部 80px 内跟随）
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({
  postFeedback: vi.fn(),
  getChunkContext: vi.fn(),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  // 保留真实模块其余导出（召回抽屉懒加载 + ApiError instanceof 判断用），仅替换业务调用为可控 mock
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    postFeedback: apiMock.postFeedback,
    getChunkContext: apiMock.getChunkContext,
  };
});

const clipboardMock = vi.hoisted(() => ({ copyToClipboard: vi.fn() }));
vi.mock("@/lib/clipboard", () => clipboardMock);

import { copyToClipboard } from "@/lib/clipboard";
import { MessageList, shouldStickToBottom } from "./message-list";
import type { StreamMessage } from "@/hooks/use-chat-stream";
import type { RetrievalSource, TimelineNode } from "@/lib/types";

const onNotify = vi.fn();

/** 构造用户消息（附件可覆盖） */
function makeUser(overrides: Partial<StreamMessage> = {}): StreamMessage {
  return {
    id: "local-1",
    role: "user",
    content: "什么是 RAG？",
    attachments: [],
    model: null,
    text: "",
    sources: [],
    timeline: [],
    endStatus: null,
    messageId: null,
    ...overrides,
  };
}

/** 构造 AI 消息（timeline/sources/终态可覆盖） */
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

/** 标准时间轴样本：思考 → 检索 → 工具（2026-08-30：无阶段/查询计划节点） */
const TIMELINE: TimelineNode[] = [
  { kind: "thinking", stage: "understanding", lines: ["先检索。"], ended: true },
  { kind: "sources", sources: [SOURCE] },
  {
    kind: "tool",
    toolCallId: "tc-1",
    toolName: "searchKnowledge",
    input: {},
    status: "success",
    output: {},
  },
];

function renderList(
  messages: StreamMessage[],
  overrides: {
    streaming?: boolean;
    blobUrls?: Record<string, string>;
    onEdit?: (message: StreamMessage, newText: string, targetRunId: string) => void;
    onRegenerate?: (runId: string) => void;
  } = {},
) {
  // 独立 QueryClient（retry 关闭）：召回抽屉懒加载 useQuery 需要 Provider，用例间不共享缓存
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MessageList
        messages={messages}
        streaming={overrides.streaming ?? false}
        sessionId="s-1"
        attachmentBlobUrls={overrides.blobUrls ?? {}}
        onNotify={onNotify}
        onEdit={overrides.onEdit}
        onRegenerate={overrides.onRegenerate}
      />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  apiMock.postFeedback.mockReset().mockResolvedValue(undefined);
  // M5 复制链默认成功（个别用例自行覆盖实现断言降级分支）
  clipboardMock.copyToClipboard.mockReset().mockResolvedValue(true);
  onNotify.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("shouldStickToBottom 智能吸底滚动判定", () => {
  it("距底小于等于 80px：跟随滚动", () => {
    expect(shouldStickToBottom(920, 1000, 100)).toBe(true);
    expect(shouldStickToBottom(919, 1000, 100)).toBe(true);
  });
  it("距底超过 80px：不跟随（用户上翻阅读中）", () => {
    // 1000 - 818 - 100 = 82px > 80 → 不跟随；停在顶部（距底 900px）同样不跟随
    expect(shouldStickToBottom(818, 1000, 100)).toBe(false);
    expect(shouldStickToBottom(0, 1000, 100)).toBe(false);
  });
});

describe("MessageList 用户消息", () => {
  it("用户气泡：右对齐 + 暖白底 + 右下小圆角（形状锁例外）", () => {
    renderList([makeUser()]);
    const bubble = screen.getByTestId("user-message");
    expect(bubble.className).toContain("justify-end");
    const inner = screen.getByTestId("user-bubble");
    expect(inner.className).toContain("bg-bubble");
    expect(inner.className).toContain("rounded-br-[8px]");
  });

  it("用户消息带附件：图片 blob 缩略图 + 文档图标行", () => {
    const user = makeUser({
      attachments: [
        { type: "image", url: "obj/1.png", name: "截图.png", size: "1024" },
        { type: "document", url: "obj/2.pdf", name: "讲义.pdf", size: "2048" },
      ],
    });
    renderList([user], { blobUrls: { "obj/1.png": "blob:thumb-1" } });
    const thumb = screen.getByRole("img", { name: /截图\.png/ });
    expect(thumb).toHaveAttribute("src", "blob:thumb-1");
    expect(screen.getByText("讲义.pdf")).toBeInTheDocument();
  });
});

describe("MessageList AI 消息组合与顺序", () => {
  it("链式时间轴置于答案块之前；时间轴内含思考/检索/工具步骤（2026-08-30 无查询计划步骤）", () => {
    const assistant = makeAssistant({
      timeline: TIMELINE,
      sources: [SOURCE],
      text: "回答正文内容",
      endStatus: "COMPLETED",
      messageId: "msg-1",
    });
    renderList([assistant], { streaming: false });
    const chain = screen.getByTestId("chain-timeline");
    const body = screen.getByTestId("markdown-view");
    expect(chain.compareDocumentPosition(body) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // 时间轴步骤族齐备（工具卡并入时间轴，不再独立成卡）
    expect(screen.getByTestId("thinking-step")).toBeInTheDocument();
    expect(screen.queryByTestId("query-plan-step")).not.toBeInTheDocument();
    expect(screen.getByTestId("sources-step")).toHaveTextContent("已检索");
    expect(screen.getByTestId("tool-step")).toBeInTheDocument();
    // 答案块挂左渐变竖线类
    expect(screen.getByTestId("markdown-view").closest(".chain-answer")).not.toBeNull();
  });

  it("点击检索步骤打开召回抽屉，Esc 关闭（抽屉契约：三关闭路径之一）", () => {
    const assistant = makeAssistant({
      timeline: [{ kind: "sources", sources: [SOURCE] }],
      sources: [SOURCE],
      text: "正文",
      endStatus: "COMPLETED",
      messageId: "msg-1",
    });
    renderList([assistant]);
    expect(screen.queryByTestId("retrieval-drawer")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("sources-step"));
    expect(screen.getByTestId("retrieval-drawer")).toBeInTheDocument();
    expect(screen.getByTestId("retrieval-drawer-list").children).toHaveLength(1);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByTestId("retrieval-drawer")).not.toBeInTheDocument();
  });

  it("点击工具步骤打开工具结果抽屉，Esc 关闭（2026-08-30 工具结果侧栏展示）", () => {
    const assistant = makeAssistant({
      timeline: [
        {
          kind: "tool",
          toolCallId: "tc-1",
          toolName: "listCourses",
          input: { keyword: "Java" },
          status: "success",
          output: { total: 1, courses: [{ title: "Java 进阶", price: "¥199" }] },
        },
      ],
      text: "正文",
      endStatus: "COMPLETED",
      messageId: "msg-1",
    });
    renderList([assistant]);
    expect(screen.queryByTestId("tool-drawer")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("tool-step"));
    expect(screen.getByTestId("tool-drawer")).toBeInTheDocument();
    expect(screen.getByTestId("tool-drawer-list").children).toHaveLength(1);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByTestId("tool-drawer")).not.toBeInTheDocument();
  });

  it("流式空窗：streaming 且时间轴/正文皆空时渲染三点脉冲占位", () => {
    renderList([makeAssistant()], { streaming: true });
    expect(screen.getByTestId("streaming-dots")).toBeInTheDocument();
  });

  it("流式光标：streaming=true 且最后一条为 AI 消息时存在，停止后消失", () => {
    const assistant = makeAssistant({ text: "正在输出" });
    const { rerender } = renderList([assistant], { streaming: true });
    expect(screen.getByTestId("typing-cursor")).toBeInTheDocument();
    rerender(
      <MessageList
        messages={[assistant]}
        streaming={false}
        sessionId="s-1"
        attachmentBlobUrls={{}}
        onNotify={onNotify}
      />,
    );
    expect(screen.queryByTestId("typing-cursor")).not.toBeInTheDocument();
  });
});

describe("MessageList 终态与操作栏", () => {
  it("CANCELLED：正文保持原样（无后缀拼接），操作栏下方整块底部渲染小字提示（图 4）", () => {
    // 2026-09-03 停止态拍板：正文不再拼「已停止生成」；提示为操作栏之后的小字
    //（非标签/徽章样式）；操作栏（复制/反馈/重新生成）照常浮现不被隐藏
    const cancelled = makeAssistant({
      text: "回答到这里被中断",
      endStatus: "CANCELLED",
      messageId: "msg-cancelled",
    });
    renderList([cancelled]);
    const body = screen.getByTestId("markdown-view");
    expect(body).toHaveTextContent("回答到这里被中断");
    // 正文不含提示文案（提示独立于正文渲染）
    expect(body.textContent).not.toContain("已停止");
    // 小字提示存在且位于操作栏（feedback-bar）之后（DOM 顺序断言）
    const hint = screen.getByTestId("stopped-hint");
    expect(hint).toHaveTextContent("这条消息已停止");
    expect(hint.className).toContain("text-xs");
    const bar = screen.getByTestId("feedback-bar");
    expect(hint.compareDocumentPosition(bar) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy();
    // 反馈入口保留（CANCELLED 携 id）：有用/无用照常渲染
    expect(screen.getByRole("button", { name: /有用/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /无用/ })).toBeInTheDocument();
    // 旧徽标形态下线
    expect(screen.queryByTestId("incomplete-badge-cancelled")).not.toBeInTheDocument();
  });

  it("CANCELLED 无 messageId（降级窗口）：操作栏仅复制、小字提示仍渲染", () => {
    const cancelled = makeAssistant({
      text: "半截",
      endStatus: "CANCELLED",
      messageId: null,
    });
    renderList([cancelled]);
    expect(screen.getByTestId("stopped-hint")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /有用/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /复制/ })).toBeInTheDocument();
  });

  it("COMPLETED：end 后操作栏浮现（复制 + 有用 + 无用），反馈请求携带 messageId", async () => {
    const assistant = makeAssistant({
      text: "完整回答",
      endStatus: "COMPLETED",
      messageId: "msg-9",
    });
    renderList([assistant]);
    expect(screen.getByRole("button", { name: /复制/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    await vi.waitFor(() => {
      expect(apiMock.postFeedback).toHaveBeenCalledWith(
        expect.objectContaining({ sessionId: "s-1", messageId: "msg-9", isLiked: true }),
      );
    });
  });

  it("未终态：操作栏不浮现", () => {
    renderList([makeAssistant({ text: "进行中" })]);
    expect(screen.queryByRole("button", { name: /有用/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /复制/ })).not.toBeInTheDocument();
  });

  it("消息流容器：max-w-[840px] 居中 + 消息间距 space-y-8", () => {
    renderList([
      makeUser(),
      makeAssistant({ text: "回答", endStatus: "COMPLETED", messageId: "m" }),
    ]);
    const container = screen.getByTestId("message-flow");
    expect(container.className).toContain("max-w-[840px]");
    expect(container.className).toContain("space-y-8");
  });
});

it("AI 消息渲染 model 徽标（metadata.model 透出，E2E 实证修订后的元信息承载）", () => {
  const { container } = renderList([makeAssistant({ model: "qwen3.8-max" })]);
  const badge = container.querySelector('[data-testid="model-badge"]');
  expect(badge).not.toBeNull();
  expect(badge?.textContent).toBe("qwen3.8-max");
});

it("model 为空时不渲染徽标（降级回放无 metadata 场景）", () => {
  const { container } = renderList([makeAssistant({ model: null })]);
  expect(container.querySelector('[data-testid="model-badge"]')).toBeNull();
});

describe("MessageList M5 消息级操作（编辑/重新生成）", () => {
  it("用户消息操作区：复制图标恒可用，点击走降级复制链并 toast", async () => {
    const copyMock = vi.fn().mockResolvedValue(true);
    vi.mocked(copyToClipboard).mockImplementation(copyMock);
    renderList([makeUser()]);
    fireEvent.click(screen.getByTestId("user-copy-button"));
    await vi.waitFor(() => {
      expect(copyMock).toHaveBeenCalledWith("什么是 RAG？");
      expect(onNotify).toHaveBeenCalledWith("已复制");
    });
  });

  it("编辑图标可用条件（M5.1）：最后一条用户消息且非生成中可用；生成中置灰；中间历史消息置灰", () => {
    const onEdit = vi.fn();
    const first = makeUser({ id: "u-1", content: "第一问" });
    const firstAnswer = makeAssistant({ id: "run-1", text: "第一答", endStatus: "COMPLETED" });
    const second = makeUser({ id: "u-2", content: "第二问" });
    const secondAnswer = makeAssistant({ id: "run-2", text: "第二答", endStatus: "COMPLETED" });
    // 非生成中：最后一条用户消息（第二问）可编辑，中间（第一问）置灰
    const { rerender } = renderList([first, firstAnswer, second, secondAnswer], { onEdit });
    const editButtons = screen.getAllByTestId("user-edit-button") as HTMLButtonElement[];
    expect(editButtons).toHaveLength(2);
    expect(editButtons[0].disabled).toBe(true);
    expect(editButtons[1].disabled).toBe(false);

    // 生成中：最后一条用户消息的编辑入口也置灰（编辑须等回答终态）
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    rerender(
      <QueryClientProvider client={client}>
        <MessageList
          messages={[first, firstAnswer, second, secondAnswer]}
          streaming={true}
          sessionId="s-1"
          attachmentBlobUrls={{}}
          onNotify={onNotify}
          onEdit={onEdit}
        />
      </QueryClientProvider>,
    );
    const streamingButtons = screen.getAllByTestId("user-edit-button") as HTMLButtonElement[];
    expect(streamingButtons[1].disabled).toBe(true);
  });

  it("编辑流：点击编辑原位替换为编辑框，提交携带配对回答 runId，取消恢复气泡", () => {
    const onEdit = vi.fn();
    const user = makeUser({ id: "u-1", content: "原问题" });
    const answer = makeAssistant({
      id: "run-1",
      text: "回答",
      endStatus: "COMPLETED",
      messageId: "m1",
    });
    renderList([user, answer], { onEdit });

    fireEvent.click(screen.getByTestId("user-edit-button"));
    expect(screen.getByTestId("message-edit-box")).toBeInTheDocument();

    // 修改文本后提交：onEdit 上抛（被编辑消息、新文本、配对 AI 回答 runId=replay 目标）
    fireEvent.change(screen.getByTestId("message-edit-input"), { target: { value: "改后的问题" } });
    fireEvent.click(screen.getByTestId("message-edit-submit"));
    expect(onEdit).toHaveBeenCalledWith(
      expect.objectContaining({ id: "u-1" }),
      "改后的问题",
      "run-1",
    );
    // 提交后编辑框退出，恢复气泡渲染
    expect(screen.queryByTestId("message-edit-box")).not.toBeInTheDocument();
    expect(screen.getByTestId("user-bubble")).toBeInTheDocument();

    // 取消路径：重新进入编辑后取消恢复原文
    fireEvent.click(screen.getByTestId("user-edit-button"));
    fireEvent.click(screen.getByTestId("message-edit-cancel"));
    expect(screen.queryByTestId("message-edit-box")).not.toBeInTheDocument();
    expect(screen.getByTestId("user-bubble")).toBeInTheDocument();
  });

  it("重新生成图标可用条件（D5）：仅最后一条已终态回答可用，中间终态回答置灰；未终态不渲染", () => {
    const onRegenerate = vi.fn();
    const user1 = makeUser({ id: "u-1" });
    const midAnswer = makeAssistant({
      id: "run-1",
      text: "第一答",
      endStatus: "COMPLETED",
      messageId: "m1",
    });
    const user2 = makeUser({ id: "u-2" });
    const lastAnswer = makeAssistant({
      id: "run-2",
      text: "第二答",
      endStatus: "COMPLETED",
      messageId: "m2",
    });
    renderList([user1, midAnswer, user2, lastAnswer], { onRegenerate });

    const buttons = screen.getAllByTestId("regenerate-button") as HTMLButtonElement[];
    expect(buttons).toHaveLength(2);
    // D5：中间回答置灰（回滚会连带吞掉其后内容），仅最后一条可用
    expect(buttons[0].disabled).toBe(true);
    expect(buttons[1].disabled).toBe(false);

    // 点击可用入口：上抛被重生成回答的 runId
    fireEvent.click(buttons[1]);
    expect(onRegenerate).toHaveBeenCalledWith("run-2");
  });

  it("未终态回答不渲染重新生成入口（生成中/无 endStatus）", () => {
    const onRegenerate = vi.fn();
    renderList([makeUser(), makeAssistant({ text: "进行中" })], { onRegenerate });
    expect(screen.queryByTestId("regenerate-button")).not.toBeInTheDocument();
  });

  it("onEdit/onRegenerate 未提供（旧调用方）：M5 图标入口不渲染（复制除外）", () => {
    renderList([makeUser(), makeAssistant({ text: "答", endStatus: "COMPLETED", messageId: "m" })]);
    expect(screen.queryByTestId("regenerate-button")).not.toBeInTheDocument();
    // 编辑按钮按 M5 形态恒渲染但置灰（无处理器时不可进入编辑态）
    const editButton = screen.getByTestId("user-edit-button") as HTMLButtonElement;
    expect(editButton.disabled).toBe(true);
  });
});
