/**
 * useChatStream 测试（Task 11 核心 100%：对话页 SSE 10 事件状态机）
 *
 * 覆盖层次：
 * 1. chatReducer 纯函数逐事件（brief Step 1 的 9 组 + 幂等/防御/纯函数边界）
 * 2. sseEventToAction 事件映射（payload → action 的 JSON 解析与分流）
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
    thinking: "",
    thinkingEnded: false,
    text: "回答一部分",
    sources: [],
    stages: [],
    tools: [],
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
    thinking: "",
    thinkingEnded: false,
    text: "",
    sources: [],
    stages: [],
    tools: [],
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
      thinking: "",
      thinkingEnded: false,
      text: "",
      sources: [],
      stages: [],
      tools: [],
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

  it("用例2 thinking：跨帧文本累积；thinking_end 置折叠标记", () => {
    let s = streamingWithAi({ messages: [aiMsg({ thinking: "" })] });
    s = chatReducer(s, { type: "thinking", delta: "先检索" });
    s = chatReducer(s, { type: "thinking", delta: "课程资料" });
    expect(s.messages[0].thinking).toBe("先检索课程资料");
    expect(s.messages[0].thinkingEnded).toBe(false);
    s = chatReducer(s, { type: "thinking_end" });
    expect(s.messages[0].thinkingEnded).toBe(true);
  });

  it("用例2 扩展：无 AI 槽时 thinking/delta 防御性忽略（reconnect 降级无 metadata 回放场景）", () => {
    const s0 = createInitialState(null);
    expect(chatReducer(s0, { type: "thinking", delta: "x" })).toBe(s0);
    expect(chatReducer(s0, { type: "delta", text: "x" })).toBe(s0);
  });

  it("用例3 delta：正文跨帧累积", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "delta", text: "第一段。" });
    s = chatReducer(s, { type: "delta", text: "第二段。" });
    expect(s.messages[0].text).toBe("回答一部分第一段。第二段。");
  });

  it("用例4 tool_call 入列 pending；tool_result 按 toolCallId 配对为 success + output", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, {
      type: "tool_call",
      toolCallId: "t-1",
      toolName: "searchKnowledge",
      input: { query: "哈希表" },
    });
    expect(s.messages[0].tools).toEqual([
      {
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
    expect(s.messages[0].tools[0]).toMatchObject({ status: "success", output: { hits: 2 } });
  });

  it("用例4 扩展：toolCallId 空串容错：按到达顺序（索引兜底）配对", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "tool_call", toolCallId: "", toolName: "a", input: null });
    s = chatReducer(s, { type: "tool_call", toolCallId: "", toolName: "b", input: null });
    s = chatReducer(s, { type: "tool_result", toolCallId: "", status: "success", output: "r1" });
    s = chatReducer(s, { type: "tool_result", toolCallId: "", status: "success", output: "r2" });
    expect(s.messages[0].tools.map((t) => t.output)).toEqual(["r1", "r2"]);
    expect(s.messages[0].tools.every((t) => t.status === "success")).toBe(true);
  });

  it("用例4 扩展：无法配对的 tool_result 忽略；非 success 状态映射 error 态（类型保留）", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, { type: "tool_call", toolCallId: "t-1", toolName: "a", input: null });
    s = chatReducer(s, { type: "tool_result", toolCallId: "t-9", status: "success", output: "x" });
    expect(s.messages[0].tools).toHaveLength(1);
    expect(s.messages[0].tools[0].status).toBe("pending");
    // 非 success（后端当前恒 success，保留枚举分支）
    s = chatReducer(s, { type: "tool_result", toolCallId: "t-1", status: "failed", output: null });
    expect(s.messages[0].tools[0].status).toBe("error");
  });

  it("用例5 sources：写入当前 AI 消息；二推覆盖不重复", () => {
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
    // 幂等：二推整体覆盖，不累积重复
    s = chatReducer(s, { type: "sources", sources: [src3] });
    expect(s.messages[0].sources).toEqual([src3]);
  });

  it("用例5 扩展 stage（2026-08-27）：阶段按序追加到当前 AI 消息；同键去重（ring 回放幂等）；终态后忽略", () => {
    let s = streamingWithAi({ messages: [aiMsg()] });
    s = chatReducer(s, {
      type: "stage",
      stage: "understanding",
      label: "正在理解你的问题",
      seq: 2,
    });
    s = chatReducer(s, { type: "stage", stage: "retrieving", label: "知识库查询中", seq: 3 });
    expect(s.messages[0].stages).toEqual([
      { stage: "understanding", label: "正在理解你的问题" },
      { stage: "retrieving", label: "知识库查询中" },
    ]);
    // 同键重发（ring 全量回放）不追加
    s = chatReducer(s, { type: "stage", stage: "retrieving", label: "知识库查询中", seq: 4 });
    expect(s.messages[0].stages).toHaveLength(2);
    // 锚点随 seq 前进
    expect(s.lastEventId).toBe(4);
    // 终态后 stage 忽略
    s = chatReducer(s, { type: "end", status: "COMPLETED", messageId: "m-1", seq: 5 });
    s = chatReducer(s, { type: "stage", stage: "generating", label: "正在生成回答", seq: 6 });
    expect(s.messages[0].stages).toHaveLength(2);
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

  it("纯函数：冻结入参不被修改（metadata 建槽 / CANCELLED 后缀均不可变更新）", () => {
    // 深层冻结：readonly 形态仅供本用例断言原对象未被修改（reducer 若可变更新会直接抛错）
    const s0 = Object.freeze({
      ...createInitialState(null),
      streaming: true,
      messages: Object.freeze([Object.freeze(aiMsg({ thinking: "" }))]),
    }) as unknown as ChatStreamState;
    const s1 = chatReducer(s0, { type: "metadata", runId: "run-1", sessionId: "s1", model: "m" });
    const s2 = chatReducer(s1, { type: "thinking", delta: "补充" });
    const s3 = chatReducer(s2, { type: "end", status: "CANCELLED" });
    // 原对象未被任何一步修改（若 reducer 可变更新，冻结会直接抛错）
    expect(s0.messages[0].text).toBe("回答一部分");
    expect(s1.messages[0].text).toBe("回答一部分");
    expect(s2.messages[0].thinking).toBe("补充");
    expect(s3.messages[0].text).toBe(`回答一部分${STOPPED_SUFFIX}`);
  });
});

// ===== 2. sseEventToAction 事件映射 =====

describe("sseEventToAction 事件映射（payload → action）", () => {
  it("10 类事件全映射：字段透传 + seq 携带（metadata/thinking/delta/tool_call/tool_result/sources/end）", () => {
    expect(sseEventToAction("metadata", J({ runId: "r", sessionId: "s", model: "m" }), 1)).toEqual({
      type: "metadata",
      runId: "r",
      sessionId: "s",
      model: "m",
      seq: 1,
    });
    expect(sseEventToAction("thinking", J({ delta: "思" }), 1)).toEqual({
      type: "thinking",
      delta: "思",
      seq: 1,
    });
    expect(sseEventToAction("thinking_end", J({}), 1)).toEqual({ type: "thinking_end", seq: 1 });
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

  it("stage 事件映射（2026-08-27）：合法阶段键透传 + label 缺省回退键名；未知键/坏 JSON 忽略", () => {
    expect(sseEventToAction("stage", J({ stage: "retrieving", label: "知识库查询中" }), 4)).toEqual(
      { type: "stage", stage: "retrieving", label: "知识库查询中", seq: 4 },
    );
    // label 缺失回退键名（不阻断）
    expect(sseEventToAction("stage", J({ stage: "generating" }), 5)).toEqual({
      type: "stage",
      stage: "generating",
      label: "generating",
      seq: 5,
    });
    // 未知阶段键整体忽略（防脏数据）
    expect(sseEventToAction("stage", J({ stage: "hacking", label: "x" }), 6)).toBeNull();
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
    expect(sseEventToAction("unknown_event", J({}), 1)).toBeNull();
    expect(sseEventToAction("delta", "not-json{{{", 1)).toBeNull();
    expect(sseEventToAction("metadata", "null", 1)).toBeNull();
  });
});

// ===== 3. useChatStream 集成 =====

describe("useChatStream 集成", () => {
  it("send 全链路：10 事件逐帧喂入还原完整消息状态机（含 lastEventId 链路锚点）", async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        md(),
        frame(2, "thinking", J({ delta: "先检索课程知识库" })),
        frame(3, "thinking", J({ delta: "，再组织回答" })),
        frame(4, "thinking_end", J({})),
        frame(5, "delta", J({ text: "第一段。" })),
        frame(6, "delta", J({ text: "第二段。" })),
        frame(
          7,
          "tool_call",
          J({ toolCallId: "t-1", toolName: "searchKnowledge", input: { query: "哈希表" } }),
        ),
        frame(8, "tool_result", J({ toolCallId: "t-1", status: "success", output: { hits: 2 } })),
        frame(
          9,
          "sources",
          J({
            sources: [
              { chunkId: "c-1", docTitle: "数据结构讲义", headingPath: "Ch3 > 3.1", score: 0.87 },
            ],
          }),
        ),
        frame(10, "end", J({ runId: "run-1", status: "COMPLETED", messageId: "msg-123" })),
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
    expect(ai).toMatchObject({
      id: "run-1",
      role: "assistant",
      model: "qwen3.8-max",
      thinking: "先检索课程知识库，再组织回答",
      thinkingEnded: true,
      text: "第一段。第二段。",
      sources: [
        { chunkId: "c-1", docTitle: "数据结构讲义", headingPath: "Ch3 > 3.1", score: 0.87 },
      ],
      tools: [
        {
          toolCallId: "t-1",
          toolName: "searchKnowledge",
          input: { query: "哈希表" },
          status: "success",
          output: { hits: 2 },
        },
      ],
      endStatus: "COMPLETED",
      messageId: "msg-123",
    });
    expect(state.streaming).toBe(false);
    expect(state.endedStatus).toBe("COMPLETED");
    expect(state.error).toBeNull();
    // 妖点：metadata 到达后 sessionId 暴露（UI replace URL 的依据）
    expect(state.sessionId).toBe("sess-1");
    expect(state.runId).toBe("run-1");
    expect(state.lastEventId).toBe(10);
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
      streams[0].push(frame(2, "thinking", J({ delta: "思1" })));
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
      streams[1].push(frame(2, "thinking", J({ delta: "思2" })));
      streams[1].push(frame(3, "delta", J({ text: "第二轮回答" })));
      streams[1].push(
        frame(4, "end", J({ runId: "run-2", status: "COMPLETED", messageId: "msg-2" })),
      );
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("COMPLETED"));
    const st2 = result.current.state;
    expect(st2.streaming).toBe(false);
    // 两轮 AI 消息各自完整落位，互不污染
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
      thinking: "思2",
      text: "第二轮回答",
      endStatus: "COMPLETED",
      messageId: "msg-2",
    });
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

  it("cancel：POST runId/cancel 后流以 end CANCELLED 收尾（停止后缀）；终态后再 cancel 的 409 静默", async () => {
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

    await act(async () => {
      await result.current.cancel();
    });
    expect(cancelCalls).toBe(1);
    const cancelCall = fetchMock.mock.calls.find((c) => String(c[0]).includes("/cancel"))!;
    expect(String(cancelCall[0])).toBe("/api/v1/student/chat/run-1/cancel");
    expect((cancelCall[1] as RequestInit).method).toBe("POST");

    // 服务端侧流继续，直到 end CANCELLED
    await act(async () => {
      ctrl.push(frame(3, "end", J({ runId: "run-1", status: "CANCELLED" })));
    });
    await waitFor(() => expect(result.current.state.endedStatus).toBe("CANCELLED"));
    expect(result.current.state.messages[1].text).toBe(`部分回答${STOPPED_SUFFIX}`);
    expect(result.current.state.streaming).toBe(false);
    expect(result.current.state.error).toBeNull();

    // 终态后再 cancel：409 静默（不抛、不染状态）
    await act(async () => {
      await result.current.cancel();
    });
    expect(cancelCalls).toBe(2);
    expect(result.current.state.endedStatus).toBe("CANCELLED");
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
});
