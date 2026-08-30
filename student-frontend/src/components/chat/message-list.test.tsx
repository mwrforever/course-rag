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
 * - 「已停止生成」后缀由 hook 追加，UI 按 endedStatus 渲染（不重复）
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
  overrides: { streaming?: boolean; blobUrls?: Record<string, string> } = {},
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
      />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  apiMock.postFeedback.mockReset().mockResolvedValue(undefined);
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
  it("CANCELLED：正文尾部渲染「已停止生成」后缀（按 endedStatus，不重复）", () => {
    // hook 契约：CANCELLED 终态已在 text 追加后缀，UI 剥离后按 endedStatus 渲染唯一一份
    const cancelled = makeAssistant({
      text: "回答到这里被中断已停止生成",
      endStatus: "CANCELLED",
      messageId: null,
    });
    renderList([cancelled]);
    const body = screen.getByTestId("markdown-view");
    expect(body).toHaveTextContent("回答到这里被中断");
    expect(body).toHaveTextContent("已停止生成");
    expect(body.textContent!.match(/已停止生成/g)).toHaveLength(1);
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
