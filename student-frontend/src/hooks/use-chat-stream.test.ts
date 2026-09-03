/**
 * useChatStream 测试（Task 11 核心 100%：对话页 SSE 状态机 + 到达序时间轴模型；
 * 2026-08-30 对齐设计稿：stage/query_plan 事件忽略、思考卡按 LLM 调用拆分）
 *
 * 覆盖层次：
 * 1. chatReducer 纯函数逐事件（brief Step 1 的 9 组 + 幂等/防御/纯函数边界；
 *    2026-08-28 时间线改版：断言以 StreamMessage.timeline 节点序与合并语义为准）
 * 2. sseEventToAction 事件映射（payload → action 的 JSON 解析、thinking stage 归一化、
 *    stage/query_plan 忽略返回 null）
 * 3. useChatStream 集成（mock fetch 返回可读流逐帧喂入；409/cancel 竞态；
 *    心跳重置与 30s 断流 fake timers；reconnect 指数退避；REPLAY_FAILED 分流；
 *    M10 降级续流不回放 metadata/sources 的等价锚点）
 *
 * 假流实现：用 node:stream/web 的 ReadableStream 构造响应体，可控制留口
 * （controllableSse）实现"按时间喂帧"语义，配合 fake timers 精确驱动断流/退避。
 */
import { act, renderHook, waitFor } from "@testing-library/react";
import { TextEncoder } from "node:util";
import { ReadableStream } from "node:stream/web";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AttachmentRecord, RetrievalSource } from "../lib/types";
import { ApiError } from "../lib/api";
import {
  chatReducer,
  createInitialState,
  findEditStartIndex,
  sseEventToAction,
  STOPPED_SUFFIX,
  useChatStream,
} from "./use-chat-stream";
import type { ChatStreamState, StreamMessage } from "./use-chat-stream";

// ===== 断言与构造工具 =====

/** JSON 序列化快捷（中文载荷保持可读） */
const J = (o: unknown) => JSON.stringify(o);

/** SSE 帧构造：id 行 + event 行 + data 行 + 空行（后端 SseEmitter 实证格式） */
function frame(id: number, name: string, data: string): string {
  return `id:${id}\nevent:${name}\ndata:${data}\n\n`;
}

/** metadata 帧（默认 run-1/sess-1） */
const md = (runId = "run-1", sessionId = "sess-1", model = "qwen3.8-max") =>
  frame(1, "metadata", J({ runId, sessionId, model }));

/** 一次性快发响应：全部帧入队后关闭流（断流计时随之在 finally 清除） */
function sseResponse(chunks: string[], status = 200): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
  return { status, ok: status >= 200 && status < 300, body: stream } as unknown as Response;
}

/** 可控流响应：帧由测试代码按时间推进（配 fake timers 精确驱动断流语义） */
interface StreamControl {
  response: Response;
  push: (chunk: string) => void;
  close: () => void;
}
function controllableSse(): StreamControl {
  const encoder = new TextEncoder();
  let controller: ReadableStreamDefaultController<Uint8Array> | null = null;
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  return {
    response: { status: 200, ok: true, body: stream } as unknown as Response,
    push: (chunk) => {
      try {
        controller?.enqueue(encoder.encode(chunk));
      } catch {
        // 流已被 cancel（卸载/新流接管后）：enqueue 抛「Controller is already closed」，静默
      }
    },
    close: () => controller?.close(),
  };
}

/** 非 2xx JSON 响应（409/503/401 等业务错误体） */
function jsonRes(status: number, body: unknown): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

/** AI 消息工厂（测试内直接构造中间态） */
function aiMsg(over?: Partial<StreamMessage>): StreamMessage {
  return {
    id: "run-1",
    role: "assistant",
    content: "",
    attachments: [],
    model: "m",
    text: "回答一部分",
    sources: [],
    timeline: [],
    endStatus: null,
    messageId: null,
    ...over,
  };
}

/** 用户消息工厂 */
function userMsg(over?: Partial<StreamMessage>): StreamMessage {
  return {
    id: "u-1",
    role: "user",
    content: "问题",
    attachments: [],
    model: null,
    text: "",
    sources: [],
    timeline: [],
    endStatus: null,
    messageId: null,
    ...over,
  };
}

/** 带 AI 槽的流式进行中状态（已 send + metadata 之后） */
function streamingWithAi(over?: Partial<ChatStreamState>): ChatStreamState {
  return {
    ...createInitialState(null),
    streaming: true,
    runId: "run-1",
    sessionId: "sess-1",
    messages: [userMsg(), aiMsg()],
    ...over,
  };
}

/** 全局 fetch stub（每个用例独立重置；api client 在调用时才读全局 fetch） */
const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// ===== 1. chatReducer 纯函数：逐事件 + 边界 =====

describe("chatReducer 纯函数", () => {
  it("用例1 metadata：建 AI 槽并暴露 sessionId（新会话 null → 值，供状态留存（E2E 实证修订：不 replace URL））；记录 runId/model", () => {
    const s = chatReducer(createInitialState(null), {
      type: "metadata",
      runId: "run-1",
      sessionId: "sess-1",
      model: "qwen3.8-max",
    });
    expect(s.sessionId).toBe("sess-1");
    expect(s.runId).toBe("run-1");
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0]).toMatchObject({
      id: "run-1",
      role: "assistant",
      model: "qwen3.8-max",
      text: "",
      sources: [],
      timeline: [],
      endStatus: null,
      messageId: null,
    });
    // 纯事件本身不置流式（streaming 由 send 动作置位）
    expect(s.streaming).toBe(false);
  });

  it("用例1 扩展：metadata 幂等：同 runId 二推仅更新 model/sessionId，不重复建槽", () => {
    let s = chatReducer(createInitialState(null), {
      type: "metadata",
      runId: "run-1",
      sessionId: "sess-1",
      model: "m1",
    });
    s = chatReducer(s, { type: "metadata", runId: "run-1", sessionId: "sess-2", model: "m2" });
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0].model).toBe("m2");
    expect(s.sessionId).toBe("sess-2");
  });

  it("用例1 扩展：metadata 空 sessionId 不覆盖既有会话归属", () => {
    const s0 = { ...createInitialState(null), sessionId: "sess-0" };
    const s = chatReducer(s0, { type: "metadata", runId: "run-9", sessionId: "", model: "m" });
    expect(s.sessionId).toBe("sess-0");
    expect(s.runId).toBe("run-9");
  });

  it("H3 锚点（M8 切回场景）：回放 metadata 的 sessionId 以服务端为准覆盖 initialSessionId，且仅改写本状态实例", () => {
    // 串台排查结论固化（spec M8 调研剩余项 H3）：reducer 层语义 = metadata 携带的
    // sessionId 是唯一权威（切回会话的回放流重建归属，服务端误发他人会话 id 时也以
    // 服务端为准）；工作区间的隔离由 key={sessionId} 重挂载承载——即使串台发生，
    // 污染也只落在本 hook 实例的 state 内，不越界改写其他实例（入参不可变是单实例
    // 侧锚点）。E2E multi-session 第三用例（互切不串入）为整链路复核。
    const before = createInitialState("sess-B");
    const next = chatReducer(before, {
      type: "metadata",
      runId: "run-A",
      sessionId: "sess-A",
      model: "m",
      seq: 1,
    });
    // 服务端值为准：归属与 run 锚点均随事件落位
    expect(next.sessionId).toBe("sess-A");
    expect(next.runId).toBe("run-A");
    expect(next.messages).toHaveLength(1);
    expect(next.messages[0]).toMatchObject({ role: "assistant", id: "run-A" });
    expect(next.lastEventId).toBe(1);
    // 入参不被原地污染（B 实例原状态保持：隔离语义的不可变侧锚点）
    expect(before.sessionId).toBe("sess-B");
    expect(before.messages).toHaveLength(0);
  });

  it("用例2 thinking（时间线改版）：同 stage 多 delta 合并一节点多行；thinking_end 置节点 ended", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "thinking", delta: "先检索", stage: "understanding" });
    s = chatReducer(s, { type: "thinking", delta: "，再组织\n新起一行", stage: "understanding" });
    // 同 stage 合并：一节点，delta 按换行并入（首段续接末行、其余各起新行）
    expect(s.messages[0].timeline).toEqual([
      {
        kind: "thinking",
        stage: "understanding",
        lines: ["先检索，再组织", "新起一行"],
        ended: false,
      },
    ]);
    s = chatReducer(s, { type: "thinking_end", stage: "understanding" });
    expect(s.messages[0].timeline[0]).toMatchObject({ kind: "thinking", ended: true });
    // 重复 thinking_end 幂等（原引用语义不重复置位）
    const again = chatReducer(s, { type: "thinking_end", stage: "understanding" });
    expect(again.messages[0].timeline[0]).toMatchObject({ ended: true });
  });

  it("用例2 扩展：不同 stage 的 thinking 各建节点（到达序）；thinking_end 按 stage 配对", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "thinking", delta: "理解问题", stage: "understanding" });
    s = chatReducer(s, { type: "thinking", delta: "组织回答", stage: "generating" });
    s = chatReducer(s, { type: "thinking", delta: "的第一步", stage: "generating" });
    expect(s.messages[0].timeline).toEqual([
      { kind: "thinking", stage: "understanding", lines: ["理解问题"], ended: false },
      { kind: "thinking", stage: "generating", lines: ["组织回答的第一步"], ended: false },
    ]);
    // thinking_end 按 stage 精确配对：仅 generating 节点置 ended
    s = chatReducer(s, { type: "thinking_end", stage: "generating" });
    expect(s.messages[0].timeline.map((n) => (n.kind === "thinking" ? n.ended : null))).toEqual([
      false,
      true,
    ]);
  });

  it("用例2 扩展：thinking_end 无同 stage 节点时防御性忽略（回放从 thinking_end 开始场景）", () => {
    const s = chatReducer(streamingWithAi({ messages: [aiMsg()] }), {
      type: "thinking_end",
      stage: "understanding",
    });
    // 不建空节点（时间轴保持原样），仅流事件本身被消费
    expect(s.messages[0].timeline).toEqual([]);
  });

  it("用例2 扩展：空 delta 不建空节点（噪声防御）", () => {
    const s = chatReducer(streamingWithAi({ messages: [aiMsg()] }), {
      type: "thinking",
      delta: "",
      stage: "understanding",
    });
    expect(s.messages[0].timeline).toEqual([]);
  });

  it("用例2 扩展：无 AI 槽时 thinking/delta 防御性忽略（reconnect 降级无 metadata 回放场景）", () => {
    const s0 = createInitialState(null);
    expect(chatReducer(s0, { type: "thinking", delta: "x", stage: "generating" })).toBe(s0);
    expect(chatReducer(s0, { type: "delta", text: "x" })).toBe(s0);
  });

  it("用例3 delta：正文跨帧累积（正文不入时间轴）", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "delta", text: "第一段。" });
    s = chatReducer(s, { type: "delta", text: "第二段。" });
    expect(s.messages[0].text).toBe("回答一部分第一段。第二段。");
    expect(s.messages[0].timeline).toEqual([]);
  });

  it("用例3b 对齐设计稿（2026-08-30）：stage/query_plan 事件不再消费（前端忽略，不落时间轴）", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    // stage 事件：忽略（「正在生成回答」等阶段文案不再展示）
    s = chatReducer(s, {
      type: "stage",
      stage: "understanding",
      label: "正在理解你的问题",
    } as never);
    expect(s.messages[0].timeline).toEqual([]);
    // query_plan 事件：忽略（「未识别意图」/重写查询清单不再展示；数据仍落库供审计）
    s = chatReducer(s, {
      type: "query_plan",
      plan: {
        intent: "knowledge_question",
        rewritten: ["RAG 检索增强生成"],
        filters: { courseNames: [] },
      },
    } as never);
    expect(s.messages[0].timeline).toEqual([]);
  });

  it("用例4 tool_call 建节点 pending；tool_result 按 toolCallId 原位更新为 success + output", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, {
      type: "tool_call",
      toolCallId: "t-1",
      toolName: "searchKnowledge",
      input: { query: "哈希表" },
    });
    expect(s.messages[0].timeline).toEqual([
      {
        kind: "tool",
        toolCallId: "t-1",
        toolName: "searchKnowledge",
        input: { query: "哈希表" },
        status: "pending",
        output: null,
      },
    ]);
    s = chatReducer(s, {
      type: "tool_result",
      toolCallId: "t-1",
      status: "success",
      output: { hits: 2 },
    });
    expect(s.messages[0].timeline[0]).toMatchObject({
      kind: "tool",
      status: "success",
      output: { hits: 2 },
    });
  });

  it("用例4 扩展：toolCallId 空串容错：按到达顺序（索引兜底）原位配对", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "tool_call", toolCallId: "", toolName: "a", input: null });
    s = chatReducer(s, { type: "tool_call", toolCallId: "", toolName: "b", input: null });
    s = chatReducer(s, { type: "tool_result", toolCallId: "", status: "success", output: "r1" });
    s = chatReducer(s, { type: "tool_result", toolCallId: "", status: "success", output: "r2" });
    expect(s.messages[0].timeline.map((n) => (n.kind === "tool" ? n.output : null))).toEqual([
      "r1",
      "r2",
    ]);
    expect(s.messages[0].timeline.every((n) => n.kind !== "tool" || n.status === "success")).toBe(
      true,
    );
  });

  it("用例4 扩展：无法配对的 tool_result 忽略；非 success 状态映射 error 态（类型保留）", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "tool_call", toolCallId: "t-1", toolName: "a", input: null });
    s = chatReducer(s, { type: "tool_result", toolCallId: "t-9", status: "success", output: "x" });
    expect(s.messages[0].timeline).toHaveLength(1);
    expect(s.messages[0].timeline[0]).toMatchObject({ kind: "tool", status: "pending" });
    // 非 success（后端当前恒 success，保留枚举分支）
    s = chatReducer(s, { type: "tool_result", toolCallId: "t-1", status: "failed", output: null });
    expect(s.messages[0].timeline[0]).toMatchObject({ kind: "tool", status: "error" });
  });

  it("用例5 sources：写入当前 AI 消息 + 时间轴来源节点；二推原位覆盖不重复", () => {
    const src1: RetrievalSource = {
      chunkId: "c-1",
      docTitle: "讲义",
      headingPath: "Ch3",
      score: 0.87,
    };
    const src2: RetrievalSource = {
      chunkId: "c-2",
      docTitle: "讲义",
      headingPath: "Ch4",
      score: 0.6,
    };
    const src3: RetrievalSource = {
      chunkId: "c-3",
      docTitle: "新讲义",
      headingPath: "Ch1",
      score: 0.95,
    };
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "sources", sources: [src1, src2] });
    expect(s.messages[0].sources).toEqual([src1, src2]);
    expect(s.messages[0].timeline).toEqual([{ kind: "sources", sources: [src1, src2] }]);
    // 幂等：二推整体原位覆盖，不累积重复节点
    s = chatReducer(s, { type: "sources", sources: [src3] });
    expect(s.messages[0].sources).toEqual([src3]);
    expect(s.messages[0].timeline).toEqual([{ kind: "sources", sources: [src3] }]);
  });

  it("用例5 扩展（2026-08-30）：思考卡按 LLM 调用拆分——同 stage 思考在上一张卡之后出现工具节点则另起新卡（主 agent 每次模型调用一块思考卡）", () => {
    // 主 agent 工具循环：调用1 思考 → 工具调用 → 工具结果 → 调用2 思考（同 stage）
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "thinking", delta: "第一轮思考", stage: "generating" });
    s = chatReducer(s, { type: "tool_call", toolCallId: "t-1", toolName: "a", input: null });
    s = chatReducer(s, { type: "tool_result", toolCallId: "t-1", status: "success", output: {} });
    // 调用2 思考：上一张 generating 卡之后已有工具节点 → 另起新卡（不合并）
    s = chatReducer(s, { type: "thinking", delta: "第二轮思考", stage: "generating" });
    expect(s.messages[0].timeline.map((n) => n.kind)).toEqual(["thinking", "tool", "thinking"]);
    expect(s.messages[0].timeline[0]).toMatchObject({
      kind: "thinking",
      stage: "generating",
      lines: ["第一轮思考"],
    });
    expect(s.messages[0].timeline[2]).toMatchObject({
      kind: "thinking",
      stage: "generating",
      lines: ["第二轮思考"],
    });
    // 同一轮调用内的多 delta 仍合并（无工具边界）
    s = chatReducer(s, { type: "thinking", delta: "，补充", stage: "generating" });
    const cards = s.messages[0].timeline.filter((n) => n.kind === "thinking");
    expect(cards).toHaveLength(2);
    expect(cards[1]).toMatchObject({ lines: ["第二轮思考，补充"] });
    // 跨调用思考不互并（工具边界后新卡不受后续 delta 影响）
    s = chatReducer(s, { type: "thinking", delta: "新一行", stage: "generating" });
    expect(s.messages[0].timeline.filter((n) => n.kind === "thinking")).toHaveLength(2);
  });

  it("时间线改版主链路（2026-08-30 对齐设计稿）：[thinking(u), thinking(g)调用1, tool, thinking(g)调用2, sources, delta] 节点序与思考卡拆分语义", () => {
    // 事件序列锚定：QU 思考 → 主 agent 思考（调用1）→ 工具调用/结果 → 主 agent 思考（调用2，
    // 工具边界后另起新卡）→ 检索来源 → 正文（不入时间轴）
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "thinking", delta: "理解中…", stage: "understanding", seq: 1 });
    s = chatReducer(s, {
      type: "thinking",
      delta: "基于检索结果组织",
      stage: "generating",
      seq: 2,
    });
    s = chatReducer(s, {
      type: "tool_call",
      toolCallId: "t-1",
      toolName: "searchKnowledge",
      input: null,
      seq: 3,
    });
    s = chatReducer(s, {
      type: "tool_result",
      toolCallId: "t-1",
      status: "success",
      output: { hits: 3 },
      seq: 4,
    });
    s = chatReducer(s, { type: "thinking", delta: "补充引用", stage: "generating", seq: 5 });
    const src: RetrievalSource = { chunkId: "c-9", docTitle: "讲义", headingPath: "", score: 0.5 };
    s = chatReducer(s, { type: "sources", sources: [src], seq: 6 });
    s = chatReducer(s, { type: "delta", text: "正文", seq: 7 });

    const ai = s.messages[0];
    // 节点序严格按到达序；delta 正文不入时间轴；stage/query_plan 不建节点
    expect(ai.timeline.map((node) => node.kind)).toEqual([
      "thinking",
      "thinking",
      "tool",
      "thinking",
      "sources",
    ]);
    // 思考卡归属：understanding 单卡；generating 按工具边界拆为两张卡
    expect(ai.timeline[0]).toMatchObject({ kind: "thinking", stage: "understanding" });
    expect(ai.timeline[1]).toMatchObject({ kind: "thinking", stage: "generating" });
    expect(ai.timeline[3]).toMatchObject({ kind: "thinking", stage: "generating" });
    // tool_result 原位更新（不追加第二节点）
    expect(ai.timeline[2]).toMatchObject({ kind: "tool", status: "success", output: { hits: 3 } });
    expect(ai.text).toBe("回答一部分正文");
    expect(s.lastEventId).toBe(7);
  });

  it("用例6 error：run 级 retryable 分流（streaming false、endStatus 交由 end 落位）", () => {
    const s = chatReducer(streamingWithAi(), {
      type: "error",
      kind: "retryable",
      message: "生成被中断",
    });
    expect(s.error).toEqual({ kind: "retryable", message: "生成被中断" });
    expect(s.streaming).toBe(false);
    expect(s.endedStatus).toBeNull();
  });

  it("用例6 扩展：REPLAY_FAILED 分流 replay_failed；auth 分流 auth；首个 error 后重复 error 幂等忽略", () => {
    let s = chatReducer(streamingWithAi(), {
      type: "error",
      kind: "replay_failed",
      message: "重放窗口过期",
    });
    expect(s.error).toEqual({ kind: "replay_failed", message: "重放窗口过期" });
    expect(s.streaming).toBe(false);
    // 重复 error 不覆盖首个（幂等）
    s = chatReducer(s, { type: "error", kind: "retryable", message: "后来的错误" });
    expect(s.error).toEqual({ kind: "replay_failed", message: "重放窗口过期" });

    const auth = chatReducer(streamingWithAi(), {
      type: "error",
      kind: "auth",
      message: "登录已失效",
    });
    expect(auth.error).toEqual({ kind: "auth", message: "登录已失效" });
  });

  it("用例7 end COMPLETED：messageId 记录 + 终态落位（streaming false、启用反馈依据）", () => {
    const s = chatReducer(streamingWithAi({ messages: [aiMsg()] }), {
      type: "end",
      status: "COMPLETED",
      messageId: "msg-1",
    });
    expect(s.endedStatus).toBe("COMPLETED");
    expect(s.streaming).toBe(false);
    expect(s.messages[0].endStatus).toBe("COMPLETED");
    expect(s.messages[0].messageId).toBe("msg-1");
  });

  it("用例7 扩展：COMPLETED 缺 messageId 容忍为 null（契约容错）", () => {
    const s = chatReducer(streamingWithAi({ messages: [aiMsg()] }), {
      type: "end",
      status: "COMPLETED",
    });
    expect(s.messages[0].messageId).toBeNull();
    expect(s.endedStatus).toBe("COMPLETED");
  });

  it("用例7 扩展：end CANCELLED 追加「已停止生成」后缀", () => {
    const s = chatReducer(streamingWithAi({ messages: [aiMsg()] }), {
      type: "end",
      status: "CANCELLED",
    });
    expect(s.messages[0].text).toBe(`回答一部分${STOPPED_SUFFIX}`);
    expect(s.messages[0].endStatus).toBe("CANCELLED");
    expect(s.endedStatus).toBe("CANCELLED");
  });

  it("用例7 扩展：end ERROR 落终态并带 retryable 分级（错误横幅依据）", () => {
    const s = chatReducer(streamingWithAi({ messages: [aiMsg()] }), {
      type: "end",
      status: "ERROR",
    });
    expect(s.endedStatus).toBe("ERROR");
    expect(s.messages[0].endStatus).toBe("ERROR");
    expect(s.error).toEqual({ kind: "retryable", message: "回答生成失败" });
    expect(s.streaming).toBe(false);
  });

  it("用例7 幂等：首个终态后再次 end（含 CANCELLED）不生效；无 AI 槽时 end 仍落终态不崩溃", () => {
    let s = chatReducer(streamingWithAi(), {
      type: "end",
      status: "COMPLETED",
      messageId: "msg-1",
    });
    const afterFirst = s;
    s = chatReducer(s, { type: "end", status: "CANCELLED" });
    // 幂等：保持首个终态，不追加停止后缀
    expect(s).toBe(afterFirst);
    expect(s.endedStatus).toBe("COMPLETED");

    const bare = chatReducer(createInitialState(null), {
      type: "end",
      status: "COMPLETED",
      messageId: "m",
    });
    expect(bare.endedStatus).toBe("COMPLETED");
    expect(bare.streaming).toBe(false);
    expect(bare.messages).toEqual([]);
  });

  it("终态后流事件整体幂等：error/metadata/delta 均被忽略（首个终态之后静默）", () => {
    let s = chatReducer(streamingWithAi(), {
      type: "end",
      status: "COMPLETED",
      messageId: "msg-1",
    });
    const afterEnd = s;
    s = chatReducer(s, { type: "error", kind: "retryable", message: "x" });
    s = chatReducer(s, { type: "metadata", runId: "run-2", sessionId: "s2", model: "m2" });
    s = chatReducer(s, { type: "delta", text: "晚到正文" });
    expect(s).toBe(afterEnd);
  });

  it("用例8 扩展（Critical-1）：end COMPLETED 后 send 新 run 重置 run 级终态与锚点（多轮追问前提）", () => {
    // 修复回归锚点：send 未重置 endedStatus 时，新 run 的 metadata 被 isTerminal
    // 幂等守卫整体吞掉，streaming 永久 true 页面假死
    const s0: ChatStreamState = {
      ...createInitialState(null),
      streaming: false,
      endedStatus: "COMPLETED",
      lastEventId: 10,
      runId: "run-1",
      sessionId: "sess-1",
      messages: [
        userMsg(),
        aiMsg({ endStatus: "COMPLETED", messageId: "msg-1", text: "第一轮回答" }),
      ],
    };
    const s = chatReducer(s0, { type: "send", id: "u-2", query: "追问", attachments: [] });
    // 关键：终态/runId/锚点清除，幂等守卫解除，新流事件得以落位
    expect(s.endedStatus).toBeNull();
    expect(s.runId).toBeNull();
    expect(s.lastEventId).toBeNull();
    expect(s.streaming).toBe(true);
    expect(s.error).toBeNull();
    // 保留既有消息（含首轮终态与反馈 id）与会话归属
    expect(s.messages).toHaveLength(3);
    expect(s.messages[1]).toMatchObject({
      id: "run-1",
      text: "第一轮回答",
      endStatus: "COMPLETED",
      messageId: "msg-1",
    });
    expect(s.messages[2]).toMatchObject({ id: "u-2", role: "user", content: "追问" });
    expect(s.sessionId).toBe("sess-1");
    // 新 run 的流事件可正常落位（2026 修复前此步被整体忽略）
    const s2 = chatReducer(s, {
      type: "metadata",
      runId: "run-2",
      sessionId: "sess-1",
      model: "m2",
      seq: 1,
    });
    expect(s2.messages).toHaveLength(4);
    expect(s2.messages[3]).toMatchObject({ id: "run-2", role: "assistant", model: "m2" });
    expect(s2.lastEventId).toBe(1);
  });

  it("用例8 send：追加用户消息（含附件）、置 streaming、清历史错误", () => {
    const attach: AttachmentRecord = { type: "image", url: "obj-1", name: "a.png", size: "1024" };
    const s0: ChatStreamState = {
      ...createInitialState(null),
      error: { kind: "replay_failed", message: "旧错误" },
    };
    const s = chatReducer(s0, {
      type: "send",
      id: "u-2",
      query: "什么是哈希表",
      attachments: [attach],
    });
    expect(s.messages[0]).toMatchObject({
      id: "u-2",
      role: "user",
      content: "什么是哈希表",
      attachments: [attach],
    });
    expect(s.streaming).toBe(true);
    expect(s.error).toBeNull();
  });

  it("用例8 语义：send 重置 run 级锚点/终态（runId/lastEventId/endedStatus），保留消息与会话归属（409 由 hook 层拦截，流向零污染）", () => {
    const s0: ChatStreamState = {
      ...streamingWithAi(),
      endedStatus: "COMPLETED",
      lastEventId: 5,
    };
    const s = chatReducer(s0, { type: "send", id: "u-2", query: "再问", attachments: [] });
    expect(s.streaming).toBe(true);
    expect(s.messages).toHaveLength(3);
    // 会话归属与既有消息不被新 send 覆盖（历史消息滚屏不受影响）；run 级锚点随新 run 重置
    expect(s.runId).toBeNull();
    expect(s.lastEventId).toBeNull();
    expect(s.endedStatus).toBeNull();
    expect(s.sessionId).toBe("sess-1");
    expect(s.messages[0]).toMatchObject({ id: "u-1", role: "user", content: "问题" });
    expect(s.messages[2]).toMatchObject({ id: "u-2", role: "user", content: "再问" });
  });

  it("用例8 语义：发送失败（409）不落 error 不落终态：reducer 视角为「无动作」（hook 层验证）", () => {
    // 契约：409 由 hook 在 dispatch 前拦截，streaming 保持、error 保持 null；
    // 此处验证 reducer 中不存在任何会把 409 写进状态的动作
    const s = chatReducer(streamingWithAi(), { type: "reconnect" });
    expect(s.error).toBeNull();
    expect(s.streaming).toBe(true);
  });

  it("用例9 reconnect：清除错误并恢复 streaming（手动重试入口）", () => {
    const s0: ChatStreamState = {
      ...streamingWithAi(),
      streaming: false,
      error: { kind: "retryable", message: "连接已断开，请重试" },
    };
    const s = chatReducer(s0, { type: "reconnect" });
    expect(s.error).toBeNull();
    expect(s.streaming).toBe(true);
  });

  it("用例9 扩展：终态后 reconnect 不恢复 streaming（保幂等）", () => {
    const s0: ChatStreamState = {
      ...streamingWithAi(),
      streaming: false,
      endedStatus: "COMPLETED",
    };
    const s = chatReducer(s0, { type: "reconnect" });
    expect(s.streaming).toBe(false);
  });

  it("hook 层 reset 出口：清空对话状态并保留会话归属", async () => {
    // 覆盖 useChatStream 返回对象的 reset 出口（reducer 层 reset 已另测）：
    // REPLAY_FAILED 横幅「重新提问」入口（chat-workspace）调用的是本出口
    const ctrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      throw new Error(`未预期的请求: ${String(input)}`);
    });
    const { result } = renderHook(() => useChatStream("sess-keep"));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
      ctrl.push(frame(2, "delta", J({ text: "回答" })));
      ctrl.close();
    });
    expect(result.current.state.messages).toHaveLength(2);
    expect(result.current.state.streaming).toBe(true);
    await act(async () => {
      result.current.reset();
    });
    expect(result.current.state.messages).toHaveLength(0);
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.sessionId).toBe("sess-1");
    // clearSession 变体（Task 13 新建对话干净态）：会话归属一并清空，
    // 下一次 send 的 sessionId=null → 后端建新会话
    await act(async () => {
      result.current.reset(true);
    });
    expect(result.current.state.sessionId).toBeNull();
  });

  it("reset：清消息/流式/错误/终结态/事件锚点，保留会话归属", () => {
    const s0: ChatStreamState = {
      ...streamingWithAi(),
      streaming: false,
      error: { kind: "retryable", message: "x" },
      lastEventId: 5,
      endedStatus: "ERROR",
    };
    const s = chatReducer(s0, { type: "reset" });
    expect(s).toEqual({ ...createInitialState("sess-1"), sessionId: "sess-1" });
    expect(s.messages).toEqual([]);
    expect(s.streaming).toBe(false);
  });

  it("reset clearSession=true：会话归属一并清空（Task 13 新建对话干净态）", () => {
    const s0: ChatStreamState = {
      ...streamingWithAi(),
      streaming: false,
      endedStatus: "COMPLETED",
    };
    const s = chatReducer(s0, { type: "reset", clearSession: true });
    // 会话归属清空：下一次 send 的 sessionId=null → 后端建新会话
    expect(s.sessionId).toBeNull();
    expect(s.messages).toEqual([]);
    expect(s.streaming).toBe(false);
    expect(s.endedStatus).toBeNull();
  });

  it("带 seq 的流事件更新 lastEventId（断流重连锚点）；无 seq 不更新", () => {
    let s = chatReducer(createInitialState(null), {
      type: "metadata",
      runId: "r",
      sessionId: "s",
      model: "m",
      seq: 1,
    });
    expect(s.lastEventId).toBe(1);
    s = chatReducer(s, { type: "delta", text: "a" });
    expect(s.lastEventId).toBe(1);
    s = chatReducer(s, { type: "end", status: "COMPLETED", messageId: "m1", seq: 7 });
    expect(s.lastEventId).toBe(7);
  });

  it("纯函数：冻结入参不被修改（metadata 建槽 / thinking 时间轴合并 / CANCELLED 后缀均不可变更新）", () => {
    // 深层冻结：readonly 形态仅供本用例断言原对象未被修改（reducer 若可变更新会直接抛错）
    const s0 = Object.freeze({
      ...createInitialState(null),
      streaming: true,
      messages: Object.freeze([Object.freeze(aiMsg())]),
    }) as unknown as ChatStreamState;
    const s1 = chatReducer(s0, { type: "metadata", runId: "run-1", sessionId: "s1", model: "m" });
    const s2 = chatReducer(s1, { type: "thinking", delta: "补充", stage: "generating" });
    const s3 = chatReducer(s2, { type: "end", status: "CANCELLED" });
    // 原对象未被任何一步修改（若 reducer 可变更新，冻结会直接抛错）
    expect(s0.messages[0].text).toBe("回答一部分");
    expect(s1.messages[0].text).toBe("回答一部分");
    expect(s2.messages[0].timeline[0]).toMatchObject({
      kind: "thinking",
      lines: ["补充"],
      ended: false,
    });
    expect(s3.messages[0].text).toBe(`回答一部分${STOPPED_SUFFIX}`);
  });
});

// ===== 2. sseEventToAction 事件映射 =====

describe("sseEventToAction 事件映射（payload → action）", () => {
  it("事件全映射：字段透传 + seq 携带（metadata/thinking/thinking_end/delta/tool_call/tool_result/sources/end）", () => {
    expect(sseEventToAction("metadata", J({ runId: "r", sessionId: "s", model: "m" }), 1)).toEqual({
      type: "metadata",
      runId: "r",
      sessionId: "s",
      model: "m",
      seq: 1,
    });
    expect(sseEventToAction("thinking", J({ delta: "思", stage: "understanding" }), 1)).toEqual({
      type: "thinking",
      delta: "思",
      stage: "understanding",
      seq: 1,
    });
    expect(sseEventToAction("thinking_end", J({ stage: "generating" }), 1)).toEqual({
      type: "thinking_end",
      stage: "generating",
      seq: 1,
    });
    expect(sseEventToAction("delta", J({ text: "文" }), 1)).toEqual({
      type: "delta",
      text: "文",
      seq: 1,
    });
    expect(
      sseEventToAction("tool_call", J({ toolCallId: "t", toolName: "n", input: { q: 1 } }), 1),
    ).toEqual({ type: "tool_call", toolCallId: "t", toolName: "n", input: { q: 1 }, seq: 1 });
    expect(
      sseEventToAction(
        "tool_result",
        J({ toolCallId: "t", status: "success", output: { n: 2 } }),
        1,
      ),
    ).toEqual({
      type: "tool_result",
      toolCallId: "t",
      status: "success",
      output: { n: 2 },
      seq: 1,
    });
    const src: RetrievalSource = { chunkId: "c", docTitle: "d", headingPath: "h", score: 0.5 };
    expect(sseEventToAction("sources", J({ sources: [src] }), 1)).toEqual({
      type: "sources",
      sources: [src],
      seq: 1,
    });
    expect(
      sseEventToAction("end", J({ runId: "r", status: "COMPLETED", messageId: "m1" }), 2),
    ).toEqual({
      type: "end",
      status: "COMPLETED",
      messageId: "m1",
      seq: 2,
    });
  });

  it("对齐设计稿（2026-08-30）：query_plan/stage 事件忽略返回 null（后端照发、前端不消费）", () => {
    // query_plan：结构合法也忽略（重写正文/意图胶囊不再渲染）
    expect(
      sseEventToAction(
        "query_plan",
        J({ intent: "knowledge_question", rewritten: ["RAG"], filters: { courseNames: [] } }),
        2,
      ),
    ).toBeNull();
    // stage：合法阶段键同样忽略（「正在生成回答」等阶段文案不再渲染）
    expect(
      sseEventToAction("stage", J({ stage: "retrieving", label: "知识库查询中" }), 4),
    ).toBeNull();
    expect(sseEventToAction("stage", J({ stage: "generating" }), 5)).toBeNull();
    // 坏 JSON / 未知键维持既有忽略语义
    expect(sseEventToAction("query_plan", "not-json{{{", 1)).toBeNull();
    expect(sseEventToAction("stage", J({ stage: "hacking", label: "x" }), 6)).toBeNull();
  });

  it("thinking stage 归一化（时间线改版）：缺失/null/未知值降级 generating（历史回放与脏数据契约）", () => {
    // 后端真机实证：PG 回放的 thinking 行 stage 输出 JSON null
    expect(sseEventToAction("thinking", J({ delta: "思", stage: null }), 1)).toEqual({
      type: "thinking",
      delta: "思",
      stage: "generating",
      seq: 1,
    });
    // 字段缺失 / 未知键 / 非字符串同样降级（内容不丢）
    expect(sseEventToAction("thinking", J({ delta: "思" }), 1)).toMatchObject({
      stage: "generating",
    });
    expect(sseEventToAction("thinking", J({ delta: "思", stage: "hacking" }), 1)).toMatchObject({
      stage: "generating",
    });
    expect(sseEventToAction("thinking", J({ delta: "思", stage: 42 }), 1)).toMatchObject({
      stage: "generating",
    });
    expect(sseEventToAction("thinking_end", J({}), 1)).toMatchObject({ stage: "generating" });
    // 合法阶段键原样透传
    expect(sseEventToAction("thinking", J({ delta: "", stage: "attachments" }), 1)).toMatchObject({
      stage: "attachments",
    });
  });

  it("error 双形态分流：code=REPLAY_FAILED → replay_failed；run 级 → retryable；缺 message 兜底文案", () => {
    expect(sseEventToAction("error", J({ message: "重放失败", code: "REPLAY_FAILED" }), 3)).toEqual(
      {
        type: "error",
        kind: "replay_failed",
        message: "重放失败",
        seq: 3,
      },
    );
    expect(
      sseEventToAction("error", J({ runId: "r", status: "ERROR", message: "模型超时" }), 3),
    ).toEqual({
      type: "error",
      kind: "retryable",
      message: "模型超时",
      seq: 3,
    });
    expect(sseEventToAction("error", J({ runId: "r", status: "ERROR" }), 3)).toEqual({
      type: "error",
      kind: "retryable",
      message: "回答生成失败",
      seq: 3,
    });
  });

  it("end 状态白名单：CANCELLED/ERROR 映射；未知状态忽略（返回 null）", () => {
    expect(sseEventToAction("end", J({ runId: "r", status: "CANCELLED" }), 4)?.type).toBe("end");
    expect(sseEventToAction("end", J({ runId: "r", status: "ERROR" }), 4)?.type).toBe("end");
    expect(sseEventToAction("end", J({ runId: "r", status: "RUNNING" }), 4)).toBeNull();
    // CANCELLED/ERROR 无 messageId 容忍为空
    const cancelled = sseEventToAction("end", J({ runId: "r", status: "CANCELLED" }), 4);
    expect(
      cancelled && "messageId" in cancelled
        ? (cancelled as { messageId: string | null }).messageId
        : "?",
    ).toBeNull();
  });

  it("未知事件名与非法 JSON 返回 null（上层静默忽略，不落状态）", () => {
    // N3-C②：坏 JSON 现会打降级 warn（可观测性），此处静音 spy 保持断言聚焦返回行为
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(sseEventToAction("unknown_event", J({}), 1)).toBeNull();
    expect(sseEventToAction("delta", "not-json{{{", 1)).toBeNull();
    expect(sseEventToAction("metadata", "null", 1)).toBeNull();
    warnSpy.mockRestore();
  });

  it("N3-C②：坏 JSON/关键字段缺失打中文 warn 且按事件名计数（返回行为不变：null/兜底值照旧）", () => {
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    // 坏 JSON：返回 null 不抛 + warn（含事件名与 data 片段的诊断线索）
    expect(sseEventToAction("delta", "not-json{{{", 1)).toBeNull();
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(String(warnSpy.mock.calls[0]?.[0])).toContain("delta");
    expect(String(warnSpy.mock.calls[0]?.[0])).toContain("not-json");

    // metadata 缺 runId（槽键/重连/cancel 全依赖）：动作照旧返回（runId 兜底空串）+ warn
    const meta = sseEventToAction("metadata", J({ sessionId: "sess-1", model: "m" }), 2);
    expect(meta).toEqual({ type: "metadata", runId: "", sessionId: "sess-1", model: "m", seq: 2 });
    expect(warnSpy).toHaveBeenCalledTimes(2);

    // delta 缺 text 字段（「思考正常正文为空」诊断线索）：动作照旧返回（text 兜底空串）+ warn
    const delta = sseEventToAction("delta", J({}), 3);
    expect(delta).toEqual({ type: "delta", text: "", seq: 3 });
    expect(warnSpy).toHaveBeenCalledTimes(3);

    // end 状态非法：返回 null + warn（终态丢失将误入重连回放路径）
    expect(sseEventToAction("end", J({ status: "RUNNING" }), 4)).toBeNull();
    expect(warnSpy).toHaveBeenCalledTimes(4);
    expect(String(warnSpy.mock.calls[3]?.[0])).toContain("end");

    // 计数递增：同一事件名再触发一次坏 JSON，日志中的「第 N 次」序号递增
    expect(sseEventToAction("delta", "{bad", 5)).toBeNull();
    expect(warnSpy).toHaveBeenCalledTimes(5);
    const counts = warnSpy.mock.calls
      .map((call) => /第 (\d+) 次/.exec(String(call[0]))?.[1])
      .filter((value): value is string => value !== undefined)
      .map(Number);
    expect(counts.length).toBeGreaterThanOrEqual(2);
    expect(counts.at(-1)).toBeGreaterThan(counts[0]);
    warnSpy.mockRestore();
  });

  it("N3-C② 回归：正常事件与设计内忽略的事件名（stage/query_plan/未知）不打 warn", () => {
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    // 正常事件全字段透传：零告警
    expect(sseEventToAction("delta", J({ text: "正文" }), 1)).not.toBeNull();
    expect(
      sseEventToAction("metadata", J({ runId: "r", sessionId: "s", model: "m" }), 2),
    ).not.toBeNull();
    // thinking 空 delta / stage 降级为文档化契约（噪声防御），不告警
    expect(sseEventToAction("thinking", J({ delta: "", stage: "unknown" }), 3)).not.toBeNull();
    // 设计内忽略的事件名（后端照发、前端不消费）：不告警
    expect(sseEventToAction("stage", J({ stage: "understanding" }), 4)).toBeNull();
    expect(sseEventToAction("query_plan", J({ intent: "chat" }), 5)).toBeNull();
    expect(sseEventToAction("future_event", J({}), 6)).toBeNull();
    expect(warnSpy).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });
});

// ===== 3. useChatStream 集成 =====

describe("useChatStream 集成", () => {
  it("send 全链路：事件逐帧喂入还原完整消息状态机（时间轴节点序 + lastEventId 链路锚点；stage/query_plan 帧被忽略）", async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        md(),
        // 2026-08-30 对齐设计稿：stage/query_plan 帧后端照发、前端忽略（不落时间轴）
        frame(2, "stage", J({ stage: "understanding", label: "正在理解你的问题" })),
        frame(3, "thinking", J({ delta: "先检索课程知识库", stage: "understanding" })),
        frame(4, "thinking", J({ delta: "，再组织回答", stage: "understanding" })),
        frame(5, "thinking_end", J({ stage: "understanding" })),
        frame(
          6,
          "query_plan",
          J({
            intent: "knowledge_question",
            rewritten: ["哈希表课程资料检索"],
            filters: { courseNames: [] },
          }),
        ),
        frame(7, "thinking", J({ delta: "组织回答", stage: "generating" })),
        frame(8, "thinking_end", J({ stage: "generating" })),
        frame(9, "delta", J({ text: "第一段。" })),
        frame(10, "delta", J({ text: "第二段。" })),
        frame(
          11,
          "tool_call",
          J({ toolCallId: "t-1", toolName: "searchKnowledge", input: { query: "哈希表" } }),
        ),
        frame(12, "tool_result", J({ toolCallId: "t-1", status: "success", output: { hits: 2 } })),
        frame(
          13,
          "sources",
          J({
            sources: [
              { chunkId: "c-1", docTitle: "数据结构讲义", headingPath: "Ch3 > 3.1", score: 0.87 },
            ],
          }),
        ),
        frame(14, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "msg-123" })),
      ]),
    );
    const { result } = renderHook(() => useChatStream(null));

    await act(async () => {
      await result.current.send("你好", []);
    });

    // 发起契约：POST /student/chat，ChatRequest 含 sessionId:null 与空附件
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe("/api/v1/student/chat");
    expect(JSON.parse(String(init?.body))).toEqual({
      sessionId: null,
      query: "你好",
      attachments: null,
    });

    await waitFor(() => {
      expect(result.current.state.endedStatus).toBe("COMPLETED");
    });

    const state = result.current.state;
    expect(state.messages).toHaveLength(2);
    expect(state.messages[0]).toMatchObject({ role: "user", content: "你好", attachments: [] });
    const ai = state.messages[1];
    // 时间轴按到达序建节点：stage/query_plan 忽略 → thinking(u) → thinking(g) → tool → sources
    expect(ai.timeline).toEqual([
      {
        kind: "thinking",
        stage: "understanding",
        lines: ["先检索课程知识库，再组织回答"],
        ended: true,
      },
      { kind: "thinking", stage: "generating", lines: ["组织回答"], ended: true },
      {
        kind: "tool",
        toolCallId: "t-1",
        toolName: "searchKnowledge",
        input: { query: "哈希表" },
        status: "success",
        output: { hits: 2 },
      },
      {
        kind: "sources",
        sources: [
          { chunkId: "c-1", docTitle: "数据结构讲义", headingPath: "Ch3 > 3.1", score: 0.87 },
        ],
      },
    ]);
    expect(ai).toMatchObject({
      id: "run-1",
      role: "assistant",
      model: "qwen3.8-max",
      text: "第一段。第二段。",
      sources: [
        { chunkId: "c-1", docTitle: "数据结构讲义", headingPath: "Ch3 > 3.1", score: 0.87 },
      ],
      endStatus: "COMPLETED",
      messageId: "msg-123",
    });
    expect(state.streaming).toBe(false);
    expect(state.endedStatus).toBe("COMPLETED");
    expect(state.error).toBeNull();
    // metadata 到达后 sessionId 暴露（状态留存依据，E2E 实证修订：不 replace URL）
    expect(state.sessionId).toBe("sess-1");
    expect(state.runId).toBe("run-1");
    expect(state.lastEventId).toBe(14);
  });

  it("send 携带附件：ChatRequest.attachments 透传 + 用户消息渲染附件 chips 数据", async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        frame(1, "metadata", J({ runId: "run-1", sessionId: "s1", model: "m" })),
        frame(2, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "m1" })),
      ]),
    );
    const attach: AttachmentRecord = {
      type: "document",
      url: "obj-2",
      name: "讲义.pdf",
      size: "2048",
    };
    const { result } = renderHook(() => useChatStream(null));

    await act(async () => {
      await result.current.send("结合附件回答", [attach]);
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));

    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      sessionId: null,
      query: "结合附件回答",
      attachments: [attach],
    });
    expect(result.current.state.messages[0].attachments).toEqual([attach]);
  });

  it("409 发送失败：reject ApiError(409)、活跃 run 的 streaming 保持 true、error 保持 null、无幽灵用户消息", async () => {
    // 第一问：可控流挂起（活跃 run）
    const ctrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      throw new Error(`未预期的请求: ${input}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("第一问", []);
    });
    await act(async () => {
      ctrl.push(md());
    });
    await waitFor(() => expect(result.current.state.streaming).toBe(true));

    // 第二问：活跃 run 冲突 → 409
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") {
        return jsonRes(409, { code: 409, message: "会话 sess-1 已有活跃的 Run，无法创建新 Run" });
      }
      throw new Error(`未预期的请求: ${input}`);
    });
    const err = await act(async () => result.current.send("第二问", []).catch((e: unknown) => e));
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe(409);
    // 409 语义：streaming 保持 true（活跃 run 未受影响）、error null、不追加幽灵用户消息
    expect(result.current.state.streaming).toBe(true);
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.messages).toHaveLength(2); // 第一问的用户消息 + AI 槽，无第三问
    expect(result.current.state.messages[0]).toMatchObject({ role: "user", content: "第一问" });
    expect(result.current.state.messages[1]).toMatchObject({ role: "assistant", id: "run-1" });
  });

  it("BUG-36：metadata 前失败后重发：清失败现场后以新对话语义发送（不留幽灵提问接续，UI 与服务端新会话对齐）", async () => {
    // 复现路径：新会话首问流在 metadata 到达前即断（error 落位、sessionId/runId 均 null）——
    // 服务端已为失败提问建过会话但 id 唯一下发通道（metadata 事件）未达前端；
    // 修复前重发直接 POST sessionId=null 另建新会话，UI 却把新提问接在旧历史后（历史不连续）
    const ctrl = controllableSse();
    const brokenBody = {
      getReader: () => ({
        read: () => Promise.reject(new Error("connection reset")),
      }),
    } as unknown as ReadableStream;
    let postCount = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") {
        // 第 1 次：metadata 前断流；第 2 次（重发）：正常可流式响应
        postCount += 1;
        return postCount === 1
          ? ({ status: 200, ok: true, body: brokenBody } as unknown as Response)
          : ctrl.response;
      }
      throw new Error(`未预期的请求: ${input}`);
    });
    const { result } = renderHook(() => useChatStream(null));

    // 首问：metadata 前失败（error 落位，会话归属/ run 均未落位，仅幽灵用户消息残留）
    await act(async () => {
      await result.current.send("第一问", []);
    });
    await waitFor(() =>
      expect(result.current.state.error).toEqual({
        kind: "retryable",
        message: "连接中断，请重试",
      }),
    );
    expect(result.current.state.sessionId).toBeNull();
    expect(result.current.state.runId).toBeNull();
    expect(result.current.state.messages).toHaveLength(1);

    // 重发：修复语义 = 先清失败现场（丢弃未获服务端会话确认的幽灵提问）再发，
    // 新提问即新对话起点——消息历史与新建会话的服务端历史完全对齐
    await act(async () => {
      await result.current.send("再问一次", []);
    });
    expect(result.current.state.messages).toHaveLength(1);
    expect(result.current.state.messages[0]).toMatchObject({ role: "user", content: "再问一次" });
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.streaming).toBe(true);
    // 重发 POST 恰好 2 次，第 2 次按新对话语义（sessionId=null，由新 metadata 落位新会话）
    expect(postCount).toBe(2);
    const secondBody = JSON.parse(fetchMock.mock.calls[1][1]?.body as string) as {
      sessionId: string | null;
    };
    expect(secondBody.sessionId).toBeNull();
  });

  it("BUG-36 回归：会话已确立（metadata 落位）后的 error 重发不清历史且复用同一 sessionId", async () => {
    vi.useFakeTimers();
    const firstCtrl = controllableSse();
    const secondCtrl = controllableSse();
    let getCount = 0;
    let postCount = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") {
        postCount += 1;
        return postCount === 1 ? firstCtrl.response : secondCtrl.response;
      }
      if (init?.method === "GET") {
        getCount += 1;
        // 重连三次全失败：第 1 次网络层异常，第 2/3 次服务端 503
        return getCount === 1
          ? Promise.reject(new TypeError("Failed to fetch"))
          : jsonRes(503, { code: 503, message: "服务暂时不可用" });
      }
      throw new Error(`未预期的请求: ${url} ${init?.method}`);
    });
    const { result } = renderHook(() => useChatStream(null));

    // 第一问成功确立会话（sess-1）后断流：3 次重连失败 → error 落位
    await act(async () => {
      await result.current.send("第一问", []);
    });
    await act(async () => {
      firstCtrl.push(md());
      firstCtrl.push(frame(2, "delta", J({ text: "部分回答" })));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(33_000);
    });
    expect(result.current.state.error).toEqual({
      kind: "retryable",
      message: "连接已断开，请重试",
    });
    expect(result.current.state.streaming).toBe(false);

    // 重发（普通重试语义）：POST 复用 sess-1，完整历史保留，不受 metadata 前失败守卫影响
    await act(async () => {
      await result.current.send("重问", []);
    });
    expect(postCount).toBe(2);
    const postCalls = fetchMock.mock.calls.filter(
      (c) => String(c[0]).endsWith("/student/chat") && (c[1] as RequestInit)?.method === "POST",
    );
    const secondBody = JSON.parse(postCalls[1]?.[1]?.body as string) as {
      sessionId: string | null;
    };
    expect(secondBody.sessionId).toBe("sess-1");
    expect(result.current.state.messages).toHaveLength(3);
    expect(result.current.state.messages[2]).toMatchObject({ role: "user", content: "重问" });
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.streaming).toBe(true);
  });

  it("Critical-1 全链路：首轮 end COMPLETED 后追问，新 run 事件全部正常落位（终态恢复/反馈 id 更新/第二轮 CANCELLED 后缀）", async () => {
    // 多轮追问回归：send 未重置 endedStatus 时，第二轮 metadata 被 isTerminal 守卫吞掉，
    // streaming 永久 true 页面假死（三轮可控流模拟首轮 COMPLETED → 二轮 COMPLETED → 三轮 CANCELLED）
    const streams = [controllableSse(), controllableSse(), controllableSse()];
    let postCount = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") {
        return streams[postCount++].response;
      }
      throw new Error(`未预期的请求: ${input}`);
    });
    const { result } = renderHook(() => useChatStream(null));

    // 第一轮：thinking + delta 推流至 end COMPLETED
    await act(async () => {
      await result.current.send("第一问", []);
    });
    await act(async () => {
      streams[0].push(
        frame(1, "metadata", J({ runId: "run-1", sessionId: "sess-1", model: "m1" })),
      );
      streams[0].push(frame(2, "thinking", J({ delta: "思1", stage: "generating" })));
      streams[0].push(frame(3, "delta", J({ text: "第一轮回答" })));
      streams[0].push(
        frame(4, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "msg-1" })),
      );
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.lastEventId).toBe(4);

    // 第二轮追问：终态被 send 清除，新流 metadata/thinking/delta/end 全部正常落位
    await act(async () => {
      await result.current.send("追问", []);
    });
    await act(async () => {
      streams[1].push(
        frame(1, "metadata", J({ runId: "run-2", sessionId: "sess-1", model: "m1" })),
      );
      streams[1].push(frame(2, "thinking", J({ delta: "思2", stage: "generating" })));
      streams[1].push(frame(3, "delta", J({ text: "第二轮回答" })));
      streams[1].push(
        frame(4, "end", J({ runId: "run-2", status: "COMPLETED", messageId: "msg-2" })),
      );
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));
    const st2 = result.current.state;
    expect(st2.streaming).toBe(false);
    // 两轮 AI 消息各自完整落位，互不污染（时间轴节点随各自 run 归位）
    expect(st2.messages).toHaveLength(4);
    expect(st2.messages[1]).toMatchObject({
      id: "run-1",
      text: "第一轮回答",
      endStatus: "COMPLETED",
      messageId: "msg-1",
    });
    expect(st2.messages[3]).toMatchObject({
      id: "run-2",
      role: "assistant",
      text: "第二轮回答",
      endStatus: "COMPLETED",
      messageId: "msg-2",
    });
    expect(st2.messages[3].timeline).toEqual([
      { kind: "thinking", stage: "generating", lines: ["思2"], ended: false },
    ]);
    // 反馈语义：messageId 已更新为第二轮值（J5 反馈接口唯一来源）
    expect(st2.messages[3].messageId).toBe("msg-2");
    expect(st2.runId).toBe("run-2");
    expect(st2.sessionId).toBe("sess-1");

    // 第三轮 end CANCELLED：停止后缀落在第三轮自己的消息上，前两轮不被污染
    await act(async () => {
      await result.current.send("再问", []);
    });
    await act(async () => {
      streams[2].push(
        frame(1, "metadata", J({ runId: "run-3", sessionId: "sess-1", model: "m1" })),
      );
      streams[2].push(frame(2, "delta", J({ text: "部分回答" })));
      streams[2].push(frame(3, "end", J({ runId: "run-3", status: "CANCELLED" })));
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("CANCELLED"));
    const st3 = result.current.state;
    expect(st3.messages).toHaveLength(6);
    expect(st3.messages[5]).toMatchObject({
      id: "run-3",
      text: `部分回答${STOPPED_SUFFIX}`,
      endStatus: "CANCELLED",
      messageId: null,
    });
    // 第二轮不受第三轮 CANCELLED 影响（正文无后缀、反馈 id 仍是 msg-2）
    expect(st3.messages[3]).toMatchObject({ id: "run-2", text: "第二轮回答", messageId: "msg-2" });
    expect(st3.messages[1]).toMatchObject({ id: "run-1", text: "第一轮回答", messageId: "msg-1" });
    expect(st3.streaming).toBe(false);
    expect(st3.error).toBeNull();
  });

  it("cancel（M2 点击即停）：本地立即终态收尾（不等后端 end）——streaming=false、消息置 CANCELLED+停止后缀、随后照常发 cancelRun；后端 end 迟到幂等消化", async () => {
    const ctrl = controllableSse();
    let cancelCalls = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      if (url.includes("/cancel")) {
        cancelCalls += 1;
        // 第一次取消成功（空体）；第二次（终态后）后端 409
        return cancelCalls === 1
          ? ({ status: 200, ok: true } as Response)
          : jsonRes(409, { code: 409, message: "会话已有活跃的 Run" });
      }
      throw new Error(`未预期的请求: ${url} ${init?.method}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("问题", []);
    });
    await act(async () => {
      ctrl.push(md());
      ctrl.push(frame(2, "delta", J({ text: "部分回答" })));
    });
    await waitFor(() => expect(result.current.state.runId).toBe("run-1"));

    // When：点击停止——本地立即收尾，不等后端 end 事件
    await act(async () => {
      await result.current.cancel();
    });

    // Then：本地终态即刻落位（streaming=false + CANCELLED + 停止后缀 + 输入框恢复依据）
    expect(cancelCalls).toBe(1);
    const cancelCall = fetchMock.mock.calls.find((c) => String(c[0]).includes("/cancel"))!;
    expect(String(cancelCall[0])).toBe("/api/v1/student/chat/run-1/cancel");
    expect((cancelCall[1] as RequestInit).method).toBe("POST");
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.endedStatus).toBe("CANCELLED");
    expect(result.current.state.messages[1].endStatus).toBe("CANCELLED");
    expect(result.current.state.messages[1].text).toBe(`部分回答${STOPPED_SUFFIX}`);
    expect(result.current.state.error).toBeNull();

    // 后端 end CANCELLED 迟到到达：终态幂等消化（后缀不重复追加、状态不二次变更）
    await act(async () => {
      ctrl.push(frame(3, "end", J({ runId: "run-1", status: "CANCELLED" })));
    });
    expect(result.current.state.endedStatus).toBe("CANCELLED");
    expect(result.current.state.messages[1].text).toBe(`部分回答${STOPPED_SUFFIX}`);
    expect(result.current.state.streaming).toBe(false);

    // 终态后再 cancel：409 静默（不抛、不染状态）
    await act(async () => {
      await result.current.cancel();
    });
    expect(cancelCalls).toBe(2);
    expect(result.current.state.endedStatus).toBe("CANCELLED");
    expect(result.current.state.streaming).toBe(false);
  });

  it("cancel 无 runId（未收到 metadata）不发任何请求", async () => {
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.cancel();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("断流 30s 触发重连：GET 携带 lastEventId；心跳帧重置断流计时", async () => {
    vi.useFakeTimers();
    const ctrl = controllableSse();
    const reconnectCtrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      if (url.includes("/reconnect") && init?.method === "GET") return reconnectCtrl.response;
      throw new Error(`未预期的请求: ${url} ${init?.method}`);
    });
    const { result } = renderHook(() => useChatStream(null));

    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
      ctrl.push(frame(2, "delta", J({ text: "部分答案" })));
      // 断流前预置来源卡（M10 降级续流不得清掉已有来源卡）
      ctrl.push(
        frame(
          3,
          "sources",
          J({
            sources: [
              { chunkId: "c-1", docTitle: "数据结构讲义", headingPath: "Ch3", score: 0.87 },
            ],
          }),
        ),
      );
    });
    expect(result.current.state.runId).toBe("run-1");
    expect(result.current.state.messages[1].sources).toHaveLength(1);

    // t=15s 前无任何行：未够 30s 不触发
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_000);
    });
    // t=15s 心跳到达：重置断流计时（30s 窗口从此刻重新计）
    await act(async () => {
      ctrl.push(":heartbeat\n\n");
    });
    // 心跳后 29s（t=44s < 45s）：仍不触发
    await act(async () => {
      await vi.advanceTimersByTimeAsync(29_000);
    });
    expect(
      fetchMock.mock.calls.filter((c) => (c[1] as RequestInit)?.method === "GET"),
    ).toHaveLength(0);
    // 心跳后累计 31s（t=46s ≥ 45s）：触发重连，URL 携带最后事件锚点 lastEventId=3
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000);
    });
    const getCall = fetchMock.mock.calls.find((c) => (c[1] as RequestInit)?.method === "GET")!;
    expect(String(getCall[0])).toBe("/api/v1/student/chat/run-1/reconnect?lastEventId=3");

    // M10 锚点：重连续流不回放 metadata/sources：不新建 AI 槽、不重复来源卡、不清已有来源卡，仅续接正文
    await act(async () => {
      reconnectCtrl.push(frame(4, "delta", J({ text: "续流内容" })));
    });
    expect(result.current.state.messages).toHaveLength(2);
    expect(result.current.state.messages[1]).toMatchObject({
      model: "qwen3.8-max",
      // 非平凡断言：断流前预置的来源卡在降级续流后原样保留（未被清空/覆盖/重复）
      sources: [{ chunkId: "c-1", docTitle: "数据结构讲义", headingPath: "Ch3", score: 0.87 }],
      text: "部分答案续流内容",
    });
    // 时间轴来源节点同样原样保留（M10 降级续流不重建时间轴）
    expect(result.current.state.messages[1].timeline).toEqual([
      {
        kind: "sources",
        sources: [{ chunkId: "c-1", docTitle: "数据结构讲义", headingPath: "Ch3", score: 0.87 }],
      },
    ]);
    expect(result.current.state.lastEventId).toBe(4);
  });

  it("Critical-1 锚点：终态后新 run 断流重连携带新 run 自己的锚点，不携带上一 run 的锚点", async () => {
    // 回归锚点：send 未重置 lastEventId 时，run2 断流重连会拿 run1 的 seq 去重放，
    // 后端按错误序列回放（run1 的 seq 在 run2 流中不存在）
    vi.useFakeTimers();
    const ctrl1 = controllableSse();
    const ctrl2 = controllableSse();
    const reconnectCtrl = controllableSse();
    let postCount = 0;
    let getCount = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") {
        return postCount++ === 0 ? ctrl1.response : ctrl2.response;
      }
      if (init?.method === "GET") {
        getCount += 1;
        return getCount === 1 ? reconnectCtrl.response : jsonRes(503, { code: 503, message: "忙" });
      }
      throw new Error(`未预期的请求: ${url}`);
    });
    const { result } = renderHook(() => useChatStream(null));

    // 第一轮完整推流（锚点累积到 run1 的 seq=3）至 end COMPLETED
    await act(async () => {
      await result.current.send("第一问", []);
    });
    await act(async () => {
      ctrl1.push(frame(1, "metadata", J({ runId: "run-1", sessionId: "sess-1", model: "m1" })));
      ctrl1.push(frame(2, "delta", J({ text: "第一轮回答" })));
      ctrl1.push(frame(3, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "m1" })));
    });
    expect(result.current.state.endedStatus).toBe("COMPLETED");
    expect(result.current.state.lastEventId).toBe(3);

    // 第二轮 send：锚点立即清零（不带参/空值），不得携带 run1 的 seq=3
    await act(async () => {
      await result.current.send("追问", []);
    });
    expect(result.current.state.lastEventId).toBeNull();
    expect(result.current.state.runId).toBeNull();
    expect(result.current.state.endedStatus).toBeNull();

    // run2 消费两个事件（seq 1/2）后断流 30s
    await act(async () => {
      ctrl2.push(frame(1, "metadata", J({ runId: "run-2", sessionId: "sess-1", model: "m1" })));
      ctrl2.push(frame(2, "delta", J({ text: "第二轮部分" })));
    });
    expect(result.current.state.lastEventId).toBe(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    const getCall = fetchMock.mock.calls.find((c) => (c[1] as RequestInit)?.method === "GET")!;
    // 重连携带 run2 自己的锚点（seq=2），全程未出现 run1 的 seq=3
    expect(String(getCall[0])).toBe("/api/v1/student/chat/run-2/reconnect?lastEventId=2");
    expect(getCount).toBe(1);

    // 续流落在 run2 的消息上（不建新槽），run1 消息保持终态原样
    await act(async () => {
      reconnectCtrl.push(frame(3, "delta", J({ text: "续流内容" })));
    });
    const st = result.current.state;
    expect(st.messages).toHaveLength(4);
    expect(st.messages[3]).toMatchObject({ id: "run-2", text: "第二轮部分续流内容" });
    expect(st.messages[1]).toMatchObject({
      id: "run-1",
      text: "第一轮回答",
      endStatus: "COMPLETED",
    });
    expect(st.streaming).toBe(true);
    expect(st.error).toBeNull();
  });

  it("BUG-18：重连退避期间新 run 建立，循环耗尽的无条件 error 不击落在途新流（新 run 事件不被 isTerminal 吞）", async () => {
    vi.useFakeTimers();
    const firstCtrl = controllableSse();
    const secondCtrl = controllableSse();
    let postCount = 0;
    let getCount = 0;
    /** 第 3 次重连 GET 的挂起句柄：在途期间由测试侧制造「用户发送新问题」竞态 */
    let resolveThirdGet!: (r: Response) => void;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") {
        postCount += 1;
        return postCount === 1 ? firstCtrl.response : secondCtrl.response;
      }
      if (init?.method === "GET") {
        getCount += 1;
        // 第 1 次网络层失败、第 2 次 503、第 3 次挂起（在途期间新 run 建立——竞态窗口）
        if (getCount === 1) throw new TypeError("Failed to fetch");
        if (getCount === 2) return jsonRes(503, { code: 503, message: "服务暂时不可用" });
        return new Promise<Response>((resolve) => {
          resolveThirdGet = resolve;
        });
      }
      throw new Error(`未预期的请求: ${url} ${init?.method}`);
    });
    const { result } = renderHook(() => useChatStream(null));

    // 第一问（run-1）推流后断流 30s：第 1 次重连失败 + 退避 1s 第 2 次失败 + 退避 2s 第 3 次在途
    await act(async () => {
      await result.current.send("第一问", []);
    });
    await act(async () => {
      firstCtrl.push(md("run-1", "sess-1"));
      firstCtrl.push(frame(2, "delta", J({ text: "旧回答" })));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(33_000);
    });
    expect(getCount).toBe(3);

    // 竞态窗口：第 3 次重连 GET 在途期间用户发送新问题，run-2 建立并开始产出事件
    await act(async () => {
      await result.current.send("第二问", []);
    });
    await act(async () => {
      secondCtrl.push(md("run-2", "sess-1"));
      secondCtrl.push(frame(2, "delta", J({ text: "新回答" })));
    });
    expect(result.current.state.runId).toBe("run-2");
    expect(result.current.state.messages.at(-1)).toMatchObject({ id: "run-2", text: "新回答" });

    // 第 3 次重连以 503 收场 → 三次尝试耗尽：修复前无条件 dispatch error 击落 run-2
    //（isTerminal 判终态，后续事件全吞）；修复后 runId 已切换（run-2 ≠ 发起时的 run-1），
    // 旧 run 的过期 error 丢弃不打到新流上
    await act(async () => {
      resolveThirdGet(jsonRes(503, { code: 503, message: "服务暂时不可用" }));
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.endedStatus).toBeNull();
    expect(result.current.state.streaming).toBe(true);

    // 新 run 后续事件正常消费（不被终态守卫静默丢弃——正文冻结回归断言）
    await act(async () => {
      secondCtrl.push(frame(3, "delta", J({ text: "续写" })));
    });
    expect(result.current.state.messages.at(-1)).toMatchObject({ id: "run-2", text: "新回答续写" });
  });

  it("BUG-18：重连目标锁定发起时 runId——在途重连成功不替换新建立的流（回放不重放已消费事件致正文重复）", async () => {
    vi.useFakeTimers();
    const firstCtrl = controllableSse();
    const secondCtrl = controllableSse();
    const replayCtrl = controllableSse();
    let postCount = 0;
    let getCount = 0;
    /** 第 2 次重连 GET 的挂起句柄：在途期间制造新 run 竞态后以 200 回放流放行 */
    let resolveSecondGet!: (r: Response) => void;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") {
        postCount += 1;
        return postCount === 1 ? firstCtrl.response : secondCtrl.response;
      }
      if (init?.method === "GET") {
        getCount += 1;
        // 第 1 次网络层失败；第 2 次挂起（在途期间新 run 建立，随后 200 成功返回回放流）
        if (getCount === 1) throw new TypeError("Failed to fetch");
        return new Promise<Response>((resolve) => {
          resolveSecondGet = resolve;
        });
      }
      throw new Error(`未预期的请求: ${url} ${init?.method}`);
    });
    const { result } = renderHook(() => useChatStream(null));

    // 第一问（run-1）断流 30s：第 1 次重连失败，退避 1s 后第 2 次在途
    await act(async () => {
      await result.current.send("第一问", []);
    });
    await act(async () => {
      firstCtrl.push(md("run-1", "sess-1"));
      firstCtrl.push(frame(2, "delta", J({ text: "旧回答" })));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000);
    });
    expect(getCount).toBe(2);

    // 竞态窗口：重连 GET 在途期间新 run 建立并产出事件
    await act(async () => {
      await result.current.send("第二问", []);
    });
    await act(async () => {
      secondCtrl.push(md("run-2", "sess-1"));
      secondCtrl.push(frame(2, "delta", J({ text: "新回答" })));
    });
    expect(result.current.state.runId).toBe("run-2");

    // 第 2 次重连成功返回 run-1 的回放流：修复前 startStream 用回放流替换 run-2 原始流，
    // 已消费事件重放（delta 追加型更新产生正文重复片段）；修复后复查 runId 已切换 →
    // 丢弃回放响应（不得接管新流）
    await act(async () => {
      resolveSecondGet(replayCtrl.response);
      await vi.advanceTimersByTimeAsync(0);
    });
    // 回放流事件（重放 run-1 的 metadata + 已消费 delta）不得污染 run-2 的正文
    await act(async () => {
      replayCtrl.push(md("run-1", "sess-1"));
      replayCtrl.push(frame(2, "delta", J({ text: "旧回答" })));
    });
    expect(result.current.state.messages.at(-1)).toMatchObject({ id: "run-2", text: "新回答" });
    expect(result.current.state.runId).toBe("run-2");
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.streaming).toBe(true);

    // run-2 原始流仍然健在：继续推帧正常消费（未被回放流替换的回归断言）
    await act(async () => {
      secondCtrl.push(frame(3, "delta", J({ text: "续写" })));
    });
    expect(result.current.state.messages.at(-1)).toMatchObject({ id: "run-2", text: "新回答续写" });
  });

  it("重连指数退避：3 次失败（1s/2s 间隔）后错误分级 retryable 且 streaming=false", async () => {
    vi.useFakeTimers();
    const ctrl = controllableSse();
    let getCount = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      if (init?.method === "GET") {
        getCount += 1;
        if (getCount === 1) throw new TypeError("Failed to fetch");
        return jsonRes(503, { code: 503, message: "服务暂时不可用" });
      }
      throw new Error(`未预期的请求: ${url}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
      ctrl.push(frame(2, "delta", J({ text: "部分" })));
    });

    // t=30s 断流 → 第 1 次重连立即发起（网络失败）
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(getCount).toBe(1);
    // 退避 1s → 第 2 次
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(getCount).toBe(2);
    // 退避 2s → 第 3 次（封顶 3 次，不产生第 4 次）
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000);
    });
    expect(getCount).toBe(3);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(getCount).toBe(3);
    // 三次失败 → 错误分级（retryable 挂横幅）+ 解除流式占用
    expect(result.current.state.error).toEqual({
      kind: "retryable",
      message: "连接已断开，请重试",
    });
    expect(result.current.state.streaming).toBe(false);
  });

  it("重连流收到 REPLAY_FAILED：分流 replay_failed 且 streaming=false", async () => {
    vi.useFakeTimers();
    const ctrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      if (init?.method === "GET") {
        return sseResponse([
          frame(3, "error", J({ message: "重放窗口过期", code: "REPLAY_FAILED" })),
        ]);
      }
      throw new Error(`未预期的请求: ${url}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
    });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(result.current.state.error).toEqual({ kind: "replay_failed", message: "重放窗口过期" });
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.endedStatus).toBeNull();
  });

  it("REPLAY_FAILED 后手动 reconnect：清除错误、恢复 streaming 并续流", async () => {
    vi.useFakeTimers();
    const ctrl = controllableSse();
    const reconnectCtrl = controllableSse();
    let getCount = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      if (init?.method === "GET") {
        getCount += 1;
        return getCount === 1
          ? sseResponse([frame(3, "error", J({ message: "重放窗口过期", code: "REPLAY_FAILED" }))])
          : reconnectCtrl.response;
      }
      throw new Error(`未预期的请求: ${url}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
      ctrl.push(frame(2, "delta", J({ text: "部分答案" })));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(result.current.state.error?.kind).toBe("replay_failed");

    await act(async () => {
      await result.current.reconnect();
    });
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.streaming).toBe(true);
    expect(getCount).toBe(2);

    await act(async () => {
      reconnectCtrl.push(frame(4, "delta", J({ text: "恢复内容" })));
    });
    expect(result.current.state.messages[1].text).toBe("部分答案恢复内容");
    expect(result.current.state.streaming).toBe(true);
  });

  it("无 runId / 已终态时不发起 reconnect（不发任何请求）", async () => {
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.reconnect();
    });
    expect(fetchMock).not.toHaveBeenCalled();

    // 已 COMPLETED 的会话：reconnect 亦不动作
    fetchMock.mockResolvedValue(
      sseResponse([
        md(),
        frame(2, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "m1" })),
      ]),
    );
    const { result: r2 } = renderHook(() => useChatStream(null));
    await act(async () => {
      await r2.current.send("你好", []);
    });
    await waitFor(() => expect(r2.current.state.endedStatus).toBe("COMPLETED"));
    fetchMock.mockClear();
    await act(async () => {
      await r2.current.reconnect();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("未知事件名与非法 JSON 静默忽略（流不崩溃、不落状态）", async () => {
    // N3-C②：坏 JSON 帧现会打降级 warn（可观测性），静音 spy 保持输出干净
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    fetchMock.mockResolvedValue(
      sseResponse([
        md(),
        frame(2, "unknown_event", J({})),
        frame(3, "delta", "not-json{{{"),
        frame(4, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "m1" })),
      ]),
    );
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));
    expect(result.current.state.messages[1].text).toBe("");
    expect(result.current.state.lastEventId).toBe(4);
    warnSpy.mockRestore();
  });

  it("响应体缺失（body=null）：错误分级 retryable 且不建任何槽", async () => {
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: null } as Response);
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await waitFor(() => expect(result.current.state.error?.kind).toBe("retryable"));
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.messages).toHaveLength(1); // 仅用户消息，无 AI 槽
  });

  it("读取流抛错（如连接被服务端掐断）：错误分级 retryable「连接中断」", async () => {
    const brokenBody = {
      getReader: () => ({
        read: () => Promise.reject(new Error("connection reset")),
      }),
    } as unknown as ReadableStream;
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: brokenBody } as unknown as Response);
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await waitFor(() => {
      expect(result.current.state.error).toEqual({
        kind: "retryable",
        message: "连接中断，请重试",
      });
    });
    expect(result.current.state.streaming).toBe(false);
  });

  it("Minor-3：流被干净关闭（done）且未终态：视为断流走既有重连路径（不落错误、续流成功）", async () => {
    // 兜底回归：服务端未发 end/error 直接掐流（如网关超时）。此前 EOF 无任何处理，
    // streaming 永久 true 且断流计时已被 finally 清除，页面假死；修复后与 30s 断流同语义
    vi.useFakeTimers();
    const ctrl = controllableSse();
    const reconnectCtrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      if (init?.method === "GET") return reconnectCtrl.response;
      throw new Error(`未预期的请求: ${url}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
      ctrl.push(frame(2, "delta", J({ text: "部分" })));
    });

    // 服务端干净关闭连接（done=true），未发 end/error
    await act(async () => {
      ctrl.close();
      // EOF 判定前 hook 让出 50ms 宏任务（渲染提交窗口，E2E route-mock 实证既有瞬时
      // 流漏重连），fake timers 下同步推进时钟
      await vi.advanceTimersByTimeAsync(50);
    });
    // 立即走重连路径（第 1 次立即尝试，无需等 30s），携带最后事件锚点
    const getCall = fetchMock.mock.calls.find((c) => (c[1] as RequestInit)?.method === "GET")!;
    expect(String(getCall[0])).toBe("/api/v1/student/chat/run-1/reconnect?lastEventId=2");
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.streaming).toBe(true);

    // 重连续流成功：正文续接，未建新槽
    await act(async () => {
      reconnectCtrl.push(frame(3, "delta", J({ text: "续流" })));
    });
    expect(result.current.state.messages).toHaveLength(2);
    expect(result.current.state.messages[1].text).toBe("部分续流");
    expect(result.current.state.streaming).toBe(true);
    expect(result.current.state.error).toBeNull();
  });

  it("Minor-3 扩展：流干净关闭且已终态（end COMPLETED 后收流）：不触发重连（正常收尾）", async () => {
    vi.useFakeTimers();
    const ctrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      throw new Error(`未预期的请求: ${input}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
      ctrl.push(frame(2, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "m1" })));
      ctrl.close(); // end 终态落位后服务端正常收流
    });
    expect(result.current.state.endedStatus).toBe("COMPLETED");
    // 终态后 EOF 不触发重连（fetch 只被 POST 调用过一次）
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.error).toBeNull();
  });

  it("发送阶段 401（刷新失败已全局登出）：error auth 分级 + send reject", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/refresh")) {
        return jsonRes(401, { code: 401, message: "Refresh Token 无效或已过期" });
      }
      return jsonRes(401, { code: 401, message: "令牌无效或已过期" });
    });
    const { result } = renderHook(() => useChatStream(null));
    const err = await act(async () => result.current.send("你好", []).catch((e: unknown) => e));
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe(401);
    expect(result.current.state.error?.kind).toBe("auth");
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.messages).toHaveLength(0);
  });

  it("卸载清理：中断读取循环与断流计时器（无未处理拒绝、无崩溃）", async () => {
    const ctrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      throw new Error(`未预期的请求: ${input}`);
    });
    const { result, unmount } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
    });
    expect(result.current.state.streaming).toBe(true);

    unmount();

    // 卸载后继续推帧：gen 失活后静默忽略
    await act(async () => {
      ctrl.push(frame(2, "delta", J({ text: "卸载后帧" })));
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("resume：切回活跃会话全量回放续流（GET 不带查询参数；metadata 建槽 → delta/end 落终态）", async () => {
    const resumeCtrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/reconnect") && init?.method === "GET") return resumeCtrl.response;
      throw new Error(`未预期的请求: ${url} ${init?.method}`);
    });
    const { result } = renderHook(() => useChatStream("sess-9"));

    // 挂载即续流（页面切回仍有 run 在生成的会话）
    await act(async () => {
      await result.current.resume("run-9");
    });
    // lastEventId=null → 全量回放（不带查询参数）
    const getCall = fetchMock.mock.calls.find((c) => (c[1] as RequestInit)?.method === "GET")!;
    expect(String(getCall[0])).toBe("/api/v1/student/chat/run-9/reconnect");

    // 回放帧：metadata（建槽 + runId/sessionId 落位）→ delta 正文 → end 终态（ring 全量回放续接）
    await act(async () => {
      resumeCtrl.push(md("run-9", "sess-9"));
      resumeCtrl.push(frame(2, "delta", J({ text: "全量回放正文" })));
      resumeCtrl.push(frame(3, "end", J({ status: "COMPLETED", messageId: "m9" })));
      resumeCtrl.close();
    });
    expect(result.current.state.runId).toBe("run-9");
    expect(result.current.state.messages[0].text).toBe("全量回放正文");
    expect(result.current.state.endedStatus).toBe("COMPLETED");
    expect(result.current.state.streaming).toBe(false);
  });

  it("resume：已流式时幂等不动作（本会话正在生成，不发起续流请求防顶掉新流）", async () => {
    const ctrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      throw new Error(`未预期的请求: ${String(input)}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
    });
    expect(result.current.state.streaming).toBe(true);

    // 已流式：resume 直接放弃（前端守卫路径=用户已在本会话发起新提问）
    await act(async () => {
      await result.current.resume("run-9");
    });
    expect(
      fetchMock.mock.calls.filter((c) => (c[1] as RequestInit)?.method === "GET"),
    ).toHaveLength(0);
  });

  it("resume：网络层失败静默放弃（不落 error、run 继续服务端执行、不阻断历史回显）", async () => {
    fetchMock.mockRejectedValue(new TypeError("网络不可达"));
    const { result } = renderHook(() => useChatStream("sess-9"));

    await act(async () => {
      await result.current.resume("run-9");
    });
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.messages).toHaveLength(0);
  });

  it("resume：服务端拒绝（run 已终结/归属失效，非 2xx）静默放弃（不落 error、不接管流，完成态由历史回显兜底）", async () => {
    fetchMock.mockResolvedValue(jsonRes(409, { code: 409, message: "Run 已终结" }));
    const { result } = renderHook(() => useChatStream("sess-9"));

    await act(async () => {
      await result.current.resume("run-9");
    });
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.messages).toHaveLength(0);
    // 拒绝后不重试（切出再切回才可重试续流）：仅一次 GET reconnect
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/v1/student/chat/run-9/reconnect");
  });

  it("resume：请求在途被新提问取代（streaming 已置位）→ 丢弃响应体放弃接管（不顶掉新流）", async () => {
    // 新提问的流（可控且保持挂起：send 落位后 streaming 持续由新流持有）
    const sendCtrl = controllableSse();
    // 续流回放响应（延迟兑现：兑现时新流已建立，本次续流应作废）
    let resolveReconnect: ((r: Response) => void) | null = null;
    const reconnectPending = new Promise<Response>((resolve) => {
      resolveReconnect = resolve;
    });
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/student/chat") && init?.method === "POST") return sendCtrl.response;
      if (url.includes("/reconnect") && init?.method === "GET") return reconnectPending;
      throw new Error(`未预期的请求: ${url} ${init?.method}`);
    });
    const { result } = renderHook(() => useChatStream("sess-9"));

    // 续流请求在途（GET 已发出未兑现）
    let resumePromise: Promise<void> | null = null;
    act(() => {
      resumePromise = result.current.resume("run-9");
    });

    // 在途期间用户发起新提问：用户消息落位 + streaming 置位（新流建立）
    await act(async () => {
      await result.current.send("切回后立刻新提问", []);
    });
    expect(result.current.state.streaming).toBe(true);

    // 续流响应到达：已被新流取代 → 丢弃响应体放弃接管（不 dispatch reconnect、不清 streaming）
    const resumeCtrl = controllableSse();
    const cancelSpy = vi.spyOn(resumeCtrl.response.body!, "cancel");
    await act(async () => {
      resolveReconnect!(resumeCtrl.response);
      await resumePromise;
    });
    expect(cancelSpy).toHaveBeenCalledTimes(1);
    expect(result.current.state.error).toBeNull();
    expect(result.current.state.streaming).toBe(true);

    // 新流继续持有状态：已作废的 run-9 回放帧不落态，新流 metadata/delta 正常落位
    await act(async () => {
      resumeCtrl.push(md("run-9", "sess-9"));
      sendCtrl.push(md("run-new", "sess-9"));
      sendCtrl.push(frame(2, "delta", J({ text: "新流正文" })));
    });
    expect(result.current.state.runId).toBe("run-new");
    expect(result.current.state.messages[1].text).toBe("新流正文");
  });

  it("detach：停消费循环释放读取器（新建对话干净态；旧流事件不再落态、状态不清）", async () => {
    const ctrl = controllableSse();
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") return ctrl.response;
      throw new Error(`未预期的请求: ${String(input)}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("你好", []);
    });
    await act(async () => {
      ctrl.push(md());
    });
    expect(result.current.state.messages[1].text).toBe("");

    // 离开旧会话前 detach：停旧流消费（不清状态，reset 由调用方承担）
    await act(async () => {
      result.current.detach();
    });
    // detach 后旧流事件不再落态（gen 失活），状态保持（streaming 仍 true 由 reset 收口）
    await act(async () => {
      ctrl.push(frame(2, "delta", J({ text: "旧流残留帧" })));
    });
    expect(result.current.state.messages[1].text).toBe("");
    expect(result.current.state.streaming).toBe(true);
  });
});

// ===== M5 消息级重放：replay_rollback / findEditStartIndex / replay() =====

describe("M5 replay_rollback 纯函数", () => {
  /** 构造一轮完整问答（u1 → run-1）+ 第二问（u2 → run-2）的多轮消息列表 */
  function twoRounds(): StreamMessage[] {
    return [
      userMsg({ id: "u-1", content: "第一问" }),
      aiMsg({ id: "run-1", text: "第一答", endStatus: "COMPLETED" }),
      userMsg({ id: "u-2", content: "第二问" }),
      aiMsg({ id: "run-2", text: "第二答", endStatus: "COMPLETED" }),
    ];
  }

  it("REGENERATE：仅移除目标 AI 回答（id===targetRunId），用户消息保留", () => {
    const s0 = {
      ...createInitialState("sess-1"),
      messages: twoRounds(),
      endedStatus: "COMPLETED" as const,
    };
    const s = chatReducer(s0, {
      type: "replay_rollback",
      targetRunId: "run-2",
      keepUserMessage: true,
    });
    // 目标回答移除，其用户消息（第二问）保留，前一轮不动
    expect(s.messages.map((m) => m.id)).toEqual(["u-1", "run-1", "u-2"]);
    // 回滚即进入新一轮：流式置位、终态/锚点清零（runId 待新流 metadata 落位）
    expect(s.streaming).toBe(true);
    expect(s.endedStatus).toBeNull();
    expect(s.runId).toBeNull();
    expect(s.lastEventId).toBeNull();
  });

  it("EDIT：移除目标用户消息及其后全部（含目标回答）", () => {
    const s0 = { ...createInitialState("sess-1"), messages: twoRounds() };
    const s = chatReducer(s0, {
      type: "replay_rollback",
      targetRunId: "run-2",
      keepUserMessage: false,
    });
    // 第二问 + 第二答一并移除（其后内容本就不存在；保留第一轮）
    expect(s.messages.map((m) => m.id)).toEqual(["u-1", "run-1"]);
    expect(s.streaming).toBe(true);
    expect(s.error).toBeNull();
  });

  it("findEditStartIndex：定位目标回答前一条用户消息下标；未配对/首条防御返回 length", () => {
    const messages = twoRounds();
    // run-2 前一条是 u-2 → 下标 2（含其后全部移除的起点）
    expect(findEditStartIndex(messages, "run-2")).toBe(2);
    expect(findEditStartIndex(messages, "run-1")).toBe(0);
    // 未配对 targetRunId（历史回显消息不在本地状态/脏值）：防御返回 length（保留全部）
    expect(findEditStartIndex(messages, "run-x")).toBe(messages.length);
    // 回答是首条消息（无前置用户消息）：防御返回 length
    expect(findEditStartIndex([aiMsg({ id: "run-solo" })], "run-solo")).toBe(1);
  });
});

describe("M5 replay() 生命周期", () => {
  it("replay EDIT 200：本地移除目标用户消息及其后内容 + 新消息落位 + 新流接管", async () => {
    // 第一轮先经 send 完成（建立 sess-1 上下文与两轮消息）
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") {
        return sseResponse([
          md("run-1", "sess-1"),
          frame(2, "delta", J({ text: "第一答" })),
          frame(3, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "m1" })),
        ]);
      }
      if (String(input).endsWith("/replay")) {
        return sseResponse([
          md("run-9", "sess-1"),
          frame(2, "delta", J({ text: "改后的答" })),
          frame(3, "end", J({ runId: "run-9", status: "COMPLETED", messageId: "m9" })),
        ]);
      }
      throw new Error(`未预期的请求: ${String(input)}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("原问题", []);
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));
    expect(result.current.state.messages.map((m) => m.role)).toEqual(["user", "assistant"]);
    expect(result.current.state.messages[0].content).toBe("原问题");

    // EDIT replay：POST /replay 携带 mode/query/targetRunId
    await act(async () => {
      await result.current.replay("EDIT", "改后的问题", "run-1");
    });
    const [replayUrl, replayInit] = fetchMock.mock.calls.at(-1)!;
    expect(String(replayUrl)).toBe("/api/v1/student/chat/session/sess-1/replay");
    expect(JSON.parse(String(replayInit?.body))).toEqual({
      mode: "EDIT",
      query: "改后的问题",
      targetRunId: "run-1",
    });
    // 本地回滚 + 新消息落位 + 新流接管：旧问答移除、新用户消息与 run-9 回答渲染
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));
    expect(result.current.state.messages.map((m) => m.role)).toEqual(["user", "assistant"]);
    expect(result.current.state.messages[0].content).toBe("改后的问题");
    expect(result.current.state.messages[1]).toMatchObject({ id: "run-9", text: "改后的答" });
    expect(result.current.state.runId).toBe("run-9");
  });

  it("replay REGENERATE 200：目标回答移除、用户消息保留、新流直接接续", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") {
        return sseResponse([
          md("run-1", "sess-1"),
          frame(2, "delta", J({ text: "旧回答" })),
          frame(3, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "m1" })),
        ]);
      }
      if (String(input).endsWith("/replay")) {
        return sseResponse([
          md("run-2", "sess-1"),
          frame(2, "delta", J({ text: "新回答" })),
          frame(3, "end", J({ runId: "run-2", status: "COMPLETED", messageId: "m2" })),
        ]);
      }
      throw new Error(`未预期的请求: ${String(input)}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("原问题", []);
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));

    await act(async () => {
      await result.current.replay("REGENERATE", null, "run-1");
    });
    const [, replayInit] = fetchMock.mock.calls.at(-1)!;
    // REGENERATE 不带 query（服务端回填原问题）
    expect(JSON.parse(String(replayInit?.body))).toEqual({
      mode: "REGENERATE",
      targetRunId: "run-1",
    });
    // 用户消息保留（本地 id 不变，不依赖模块级递增序号断言）、新回答替换旧回答
    const keptUserId = result.current.state.messages[0].id;
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));
    expect(result.current.state.messages.map((m) => m.role)).toEqual(["user", "assistant"]);
    expect(result.current.state.messages[0].id).toBe(keptUserId);
    expect(result.current.state.messages[0].content).toBe("原问题");
    expect(result.current.state.messages[1].text).toBe("新回答");
  });

  it("replay 409（正在回答/位置校验失败）：本地状态不动，向上抛 ApiError 供 toast", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/student/chat") && init?.method === "POST") {
        return sseResponse([
          md("run-1", "sess-1"),
          frame(2, "delta", J({ text: "完整回答" })),
          frame(3, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "m1" })),
        ]);
      }
      if (String(input).endsWith("/replay")) {
        return jsonRes(409, { code: 409, message: "正在回答中，请稍后操作" });
      }
      throw new Error(`未预期的请求: ${String(input)}`);
    });
    const { result } = renderHook(() => useChatStream(null));
    await act(async () => {
      await result.current.send("原问题", []);
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));
    const before = result.current.state;

    // 409：向上抛 ApiError（上层 toast），本地消息/终态/锚点全部不动
    await act(async () => {
      await expect(result.current.replay("REGENERATE", null, "run-1")).rejects.toMatchObject({
        code: 409,
        message: "正在回答中，请稍后操作",
      });
    });
    expect(result.current.state.messages).toBe(before.messages);
    expect(result.current.state.endedStatus).toBe("COMPLETED");
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.error).toBeNull();
  });

  it("replay 发送阶段 401（刷新失败已全局登出）：error auth 分级 + replay reject（本地消息不动）", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/refresh")) {
        return jsonRes(401, { code: 401, message: "Refresh Token 无效或已过期" });
      }
      return jsonRes(401, { code: 401, message: "令牌无效或已过期" });
    });
    const { result } = renderHook(() => useChatStream("sess-1"));
    const err = await act(async () =>
      result.current.replay("EDIT", "改后的问题", "run-1").catch((e: unknown) => e),
    );
    // 发送阶段失败向上抛 ApiError(401)（与 send 401 同款语义，上层感知登出）
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe(401);
    expect(String(fetchMock.mock.calls.at(-1)![0])).toBe(
      "/api/v1/student/chat/session/sess-1/replay",
    );
    // auth 分级落位供页面感知（api 层单飞刷新失败已全局登出）
    expect(result.current.state.error?.kind).toBe("auth");
    // 本地消息不动（replay_rollback 未发生，与服务端一致无需恢复）
    expect(result.current.state.messages).toHaveLength(0);
    expect(result.current.state.streaming).toBe(false);
  });
});
