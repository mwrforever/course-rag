/**
 * 消息流渲染性能契约测试（PERF-05）
 *
 * 目标：流式 delta 期间（reducer 只换末条消息对象身份、timeline/sources 引用不变），
 * 末条 AI 消息内部的 ChainTimeline 子树不重渲染——时间轴步骤组件渲染计数不增长；
 * 正文 delta 照常渲染（markdown 内容更新）；时间轴事件到达（timeline 引用变化）时
 * 步骤恢复渲染（memo 不陈旧）。
 *
 * 手法：以渲染计数桩替换 ThinkingStep / OpStep（ChainTimeline 本体保持真实实现，
 * 其 memo 即被测对象），独立成文件避免模块级 mock 污染行为测试。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

/** 步骤组件渲染计数（vi.mock 工厂被提升，须经 vi.hoisted 共享引用） */
const counters = vi.hoisted(() => ({ thinking: 0, op: 0 }));

vi.mock("./thinking-step", () => ({
  ThinkingStep: function ThinkingStep() {
    counters.thinking += 1;
    return <div data-testid="thinking-step" />;
  },
}));

vi.mock("./op-step", async (importOriginal) => {
  // 保留真实纯函数导出（chain-timeline 内使用），仅替换组件为计数桩
  const actual = await importOriginal<typeof import("./op-step")>();
  return {
    ...actual,
    OpStep: function OpStep(props: { testId?: string }) {
      counters.op += 1;
      return <div data-testid={props.testId ?? "op-step"} />;
    },
  };
});

import { MessageList } from "./message-list";
import type { StreamMessage } from "@/hooks/use-chat-stream";
import type { RetrievalSource, TimelineToolNode, TimelineNode } from "@/lib/types";

const SOURCE: RetrievalSource = {
  chunkId: "c-1",
  docTitle: "RAG 白皮书",
  headingPath: "第三章",
  score: 0.9,
};

/** 标准时间轴样本：思考 + 检索 + 工具（思考在前，检验 delta 期间全部步骤均不重渲染） */
const TIMELINE: TimelineNode[] = [
  { kind: "thinking", stage: "understanding", lines: ["先检索。"], ended: true },
  { kind: "sources", sources: [SOURCE] },
  {
    kind: "tool",
    toolCallId: "tc-1",
    toolName: "searchKnowledge",
    input: {},
    status: "pending",
    output: null,
  },
];

/** 构造流式中的 AI 消息（delta 事件只追加 text） */
function makeAssistant(overrides: Partial<StreamMessage> = {}): StreamMessage {
  return {
    id: "run-1",
    role: "assistant",
    content: "",
    attachments: [],
    model: "qwen3-8b",
    text: "回答第一段",
    sources: [SOURCE],
    timeline: TIMELINE,
    endStatus: null,
    messageId: null,
    ...overrides,
  };
}

function renderList(messages: StreamMessage[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MessageList
        messages={messages}
        streaming
        sessionId="s-1"
        attachmentBlobUrls={{}}
        onNotify={() => {}}
      />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  counters.thinking = 0;
  counters.op = 0;
});

describe("PERF-05：流式 delta 期间时间轴子树不重渲染", () => {
  it("delta 只追加正文（timeline/sources 引用不变）：步骤渲染计数零增长，正文照常更新", () => {
    const message = makeAssistant();
    const { rerender } = renderList([message]);
    // 基线：思考 1 次 + 检索/工具各 1 次（共 2 次 OpStep）
    expect(counters.thinking).toBe(1);
    expect(counters.op).toBe(2);

    // 模拟连续两个 delta chunk：reducer 语义为 { ...msg, text: msg.text + delta }，
    // timeline 与 sources 引用保持不变（仅消息对象身份变化）
    const afterDelta1: StreamMessage = { ...message, text: message.text + "，继续" };
    const afterDelta2: StreamMessage = { ...afterDelta1, text: afterDelta1.text + "输出" };
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    rerender(
      <QueryClientProvider client={client}>
        <MessageList
          messages={[afterDelta2]}
          streaming
          sessionId="s-1"
          attachmentBlobUrls={{}}
          onNotify={() => {}}
        />
      </QueryClientProvider>,
    );

    // 时间轴步骤不重渲染（ChainTimeline memo 生效：props 全稳定）
    expect(counters.thinking).toBe(1);
    expect(counters.op).toBe(2);
    // 正文 delta 照常渲染（末条流式行本身仍逐帧更新）
    expect(screen.getByTestId("markdown-view")).toHaveTextContent("回答第一段，继续输出");
  });

  it("时间轴事件到达（timeline 引用变化）：步骤恢复渲染（memo 不导致陈旧）", () => {
    const message = makeAssistant();
    const { rerender } = renderList([message]);
    expect(counters.op).toBe(2);

    // 模拟 tool_result 事件：工具节点原位更新产生新 timeline 数组引用
    const updatedTool: TimelineToolNode = {
      kind: "tool",
      toolCallId: "tc-1",
      toolName: "searchKnowledge",
      input: {},
      status: "success",
      output: {},
    };
    const afterToolResult: StreamMessage = {
      ...message,
      timeline: [TIMELINE[0], TIMELINE[1], updatedTool],
    };
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    rerender(
      <QueryClientProvider client={client}>
        <MessageList
          messages={[afterToolResult]}
          streaming
          sessionId="s-1"
          attachmentBlobUrls={{}}
          onNotify={() => {}}
        />
      </QueryClientProvider>,
    );

    // 时间轴子树随事件重新渲染（计数增长证明 memo 只是跳过、不是冻结）
    expect(counters.op).toBeGreaterThan(2);
  });
});
