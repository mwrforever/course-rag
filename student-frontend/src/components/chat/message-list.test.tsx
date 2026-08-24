/**
 * 消息流测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 消息流 + §1.6 动效）：
 * - 用户消息：右对齐 teal-50 气泡（rounded-br-md 形状锁例外）+ 附件缩略 chips
 * - AI 消息：无气泡整栏 + 思考卡/来源卡/正文/工具卡/操作栏的组合顺序
 *   （来源卡置于正文之前，仅 knowledge_question 有）
 * - 流式打字光标（streaming 时存在）
 * - 「已停止生成」后缀由 hook 追加，UI 按 endedStatus 渲染（不重复）
 * - end 后操作栏浮现（复制/有用/无用）
 * - 智能吸底滚动判定纯函数（仅底部 80px 内跟随）
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ postFeedback: vi.fn() }));
vi.mock("@/lib/api", () => ({ postFeedback: apiMock.postFeedback }));

import { MessageList, shouldStickToBottom } from "./message-list";
import type { StreamMessage } from "@/hooks/use-chat-stream";
import type { RetrievalSource } from "@/lib/types";

const onNotify = vi.fn();

/** 构造用户消息（附件可覆盖） */
function makeUser(overrides: Partial<StreamMessage> = {}): StreamMessage {
  return {
    id: "local-1",
    role: "user",
    content: "什么是 RAG？",
    attachments: [],
    model: null,
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

/** 构造 AI 消息（thinking/sources/tools/终态可覆盖） */
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

function renderList(
  messages: StreamMessage[],
  overrides: { streaming?: boolean; blobUrls?: Record<string, string> } = {},
) {
  return render(
    <MessageList
      messages={messages}
      streaming={overrides.streaming ?? false}
      sessionId="s-1"
      attachmentBlobUrls={overrides.blobUrls ?? {}}
      onNotify={onNotify}
    />,
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
  it("用户气泡：右对齐 + teal-50 底 + 右下角 6px 圆角（形状锁例外）", () => {
    renderList([makeUser()]);
    const bubble = screen.getByTestId("user-message");
    expect(bubble.className).toContain("justify-end");
    const inner = screen.getByTestId("user-bubble");
    expect(inner.className).toContain("bg-brand-light");
    expect(inner.className).toContain("rounded-br-md");
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
  it("来源卡置于正文之前（sources 先于 markdown），工具卡在正文之后", () => {
    const assistant = makeAssistant({
      thinking: "先检索。",
      thinkingEnded: true,
      text: "回答正文内容",
      sources: [SOURCE],
      tools: [
        {
          toolCallId: "tc-1",
          toolName: "searchKnowledge",
          input: {},
          status: "success",
          output: {},
        },
      ],
      endStatus: "COMPLETED",
      messageId: "msg-1",
    });
    renderList([assistant], { streaming: false });
    const sources = screen.getByTestId("sources-list");
    const body = screen.getByTestId("markdown-view");
    const tool = screen.getByTestId("tool-card");
    expect(sources.compareDocumentPosition(body) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(body.compareDocumentPosition(tool) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
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

  it("消息流容器：max-w-[880px] 居中 + 消息间距 space-y-8", () => {
    renderList([
      makeUser(),
      makeAssistant({ text: "回答", endStatus: "COMPLETED", messageId: "m" }),
    ]);
    const container = screen.getByTestId("message-flow");
    expect(container.className).toContain("max-w-[880px]");
    expect(container.className).toContain("space-y-8");
  });
});
