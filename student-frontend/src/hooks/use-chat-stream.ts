/**
 * useChatStream：对话页 SSE 10 事件状态机（Task 11 核心）
 *
 * 职责（设计文档 §1.5.4 + 契约 §5/§6）：
 * - chatReducer：纯函数状态机，消费 11 种 SSE 事件（metadata/thinking/thinking_end/
 *   delta/tool_call/tool_result/sources/stage/error/end + :heartbeat 心跳仅重置计时，不落状态）
 *   与 send/reconnect/reset 三个生命周期动作，产出 Task 12 组件消费的渲染视图模型
 * - useChatStream：fetch + ReadableStream + TextDecoder 手写 SSE 解析喂入；
 *   409 发送冲突语义、cancel 竞态静默、30s 断流心跳计时、重连指数退避（1s/2s 封顶 3 次）
 *
 * 关键设计决策：
 * - 事件 id 行（seq）→ state.lastEventId，作为断流重连锚点（reconnect?lastEventId=）
 * - 首个终态幂等：endedStatus/error 任一落位后，后续流事件整体忽略
 * - 多轮追问：send 重置 endedStatus/runId/lastEventId，run 级终态与锚点随新 run 重新
 *   累积（首轮 end 后不清除会被幂等守卫吞掉新流事件，导致 streaming 永久 true）
 * - run 级 error 事件与 error 状态分流：retryable（run 级 ERROR / 连接中断）/
 *   replay_failed（重连 REPLAY_FAILED）/ auth（发送阶段 401 刷新失败）
 * - tool_result 按 toolCallId 配对；空串容错按到达顺序（索引兜底）配对
 * - CANCELLED 终态在正文追加「已停止生成」后缀（设计 §1.5.4 end 行）
 * - 409 不落状态（streaming 保持、error 保持 null），由上层 toast（设计 §3.2）
 * - M10：reconnect 降级回放不含 metadata/sources，本状态机天然容忍（已建槽不重复、
 *   来源卡不重复渲染），无需特判
 * - 线程安全：单 hook 实例单消费者（React 组件内），reducer 纯函数无共享可变状态；
 *   流消费循环经世代号（gen）失活，避免重连/卸载后旧循环写入新状态
 */
import { useEffect, useReducer, useRef } from "react";
import { ApiError, cancelRun, postChat, reconnectChat } from "../lib/api";
import { createSseParser } from "../lib/sse-parser";
import type { AttachmentRecord, ChatStage, ChatStageKey, RetrievalSource } from "../lib/types";

/** 合法阶段键集合（后端 STAGE_* 契约同值；未知阶段事件整体忽略，防脏数据进状态机） */
const STAGE_KEYS: ReadonlySet<string> = new Set([
  "attachments",
  "understanding",
  "retrieving",
  "generating",
]);

// ===== 类型定义（Task 12 组件的接口依据，见 task-11-report.md） =====

/** AI 消息终态（end 事件状态；只认首个终态） */
export type EndStatus = "COMPLETED" | "CANCELLED" | "ERROR";

/** 错误分级：retryable=可重试（run 级失败/连接中断）、replay_failed=重放窗口过期、auth=认证失效 */
export type ErrorKind = "retryable" | "replay_failed" | "auth";

/** 流错误状态（页面横幅分级操作的依据） */
export interface ChatError {
  kind: ErrorKind;
  message: string;
}

/** 工具卡视图模型（tool_call/tool_result 按 toolCallId 配对；error 分支后端当前不可达，类型保留） */
export interface StreamTool {
  /** 工具调用标识；后端可发空串，空串按到达顺序兜底配对 */
  toolCallId: string;
  /** 工具名（如 searchKnowledge，UI 做人话映射） */
  toolName: string;
  /** tool_call 的 input 原文（JSON 任意结构，UI popover 展示用） */
  input: unknown;
  /** pending=等待结果、success=完成、error=失败（后端恒 success，分支保留待未来） */
  status: "pending" | "success" | "error";
  /** tool_result 的 output 原文（未配对前为 null） */
  output: unknown;
}

/** 流消息视图模型：用户消息与 AI 消息统一载体（Task 12 组件渲染依据） */
export interface StreamMessage {
  /** 本地键：AI 消息=runId、用户消息=hook 生成；React key 与配对锚点 */
  id: string;
  /** 消息角色（AI 消息=assistant） */
  role: "user" | "assistant";
  /** 用户消息正文（用户气泡纯文本渲染防 XSS）；AI 消息为空串，正文走 text */
  content: string;
  /** 用户消息附件（发送时随 ChatRequest 提交的记录） */
  attachments: AttachmentRecord[];
  /** metadata.model（模型名，连接前为 null；reconnect 降级回放无 metadata 时保持原值） */
  model: string | null;
  /** thinking 累积文本（思考卡流式追加内容） */
  thinking: string;
  /** thinking_end 后为 true（思考卡折叠为摘要行） */
  thinkingEnded: boolean;
  /** delta 累积正文（CANCELLED 终态追加「已停止生成」后缀） */
  text: string;
  /** 来源卡数据（仅 knowledge_question 意图发送，不得假设必有；M10 降级重放不更新） */
  sources: RetrievalSource[];
  /**
   * 阶段进度条目（STAGE 事件按序追加：附件解析→理解问题→知识库查询→生成回答；
   * 2026-08-27 C 端改版：检索链路「先准备上下文，再流式返回」过程可见化）
   */
  stages: ChatStage[];
  /** 工具卡列表（toolCallId 配对） */
  tools: StreamTool[];
  /** 终态（end 事件后非 null；操作栏/反馈按钮浮现依据） */
  endStatus: EndStatus | null;
  /** end COMPLETED 的 messageId（反馈接口唯一来源；CANCELLED/ERROR 为 null） */
  messageId: string | null;
  /**
   * 意图透传（仅历史回显填充：StudentMessage.intentType 直通，可能为存量 unknown；
   * 实时流消息无此字段，FeedbackBar 按 hasSources 推断）
   */
  intentType?: string | null;
}

/** 对话流整体状态（useChatStream 暴露给页面的全部状态面） */
export interface ChatStreamState {
  /** 全部消息（用户消息 + AI 消息，按时间序） */
  messages: StreamMessage[];
  /** 是否正在生成（send 置位；终态/错误解除；409 冲突不受影响） */
  streaming: boolean;
  /** 流错误分级（null=无错误；横幅与分级操作依据） */
  error: ChatError | null;
  /** 最后一条已消费事件的 SSE id 行（seq），断流重连锚点 */
  lastEventId: number | null;
  /** 会话 id（null=新会话未落库；metadata 到达后落位。E2E 实证修订：不 replace URL，仅状态留存） */
  sessionId: string | null;
  /** 当前 run id（metadata 到达后落位；cancel/reconnect 的路径参数） */
  runId: string | null;
  /** run 终态（首个终态幂等；ERROR 由 error 分级配合呈现） */
  endedStatus: EndStatus | null;
}

/** reducer 动作：10 种 SSE 事件（携带 seq 锚点）+ 生命周期动作（send/reconnect/reset） */
export type ChatAction =
  | { type: "send"; id: string; query: string; attachments: AttachmentRecord[] }
  | { type: "metadata"; runId: string; sessionId: string; model: string; seq?: number | null }
  | { type: "thinking"; delta: string; seq?: number | null }
  | { type: "thinking_end"; seq?: number | null }
  | { type: "delta"; text: string; seq?: number | null }
  | {
      type: "tool_call";
      toolCallId: string;
      toolName: string;
      input: unknown;
      seq?: number | null;
    }
  | {
      type: "tool_result";
      toolCallId: string;
      status: string;
      output: unknown;
      seq?: number | null;
    }
  | { type: "sources"; sources: RetrievalSource[]; seq?: number | null }
  | { type: "stage"; stage: ChatStageKey; label: string; seq?: number | null }
  | { type: "error"; kind: ErrorKind; message: string; seq?: number | null }
  | {
      type: "end";
      status: EndStatus;
      messageId?: string | null;
      seq?: number | null;
    }
  | { type: "reconnect" }
  | { type: "reset" };

// ===== 常量 =====

/** CANCELLED 终态追加到正文的停止后缀（设计 §1.5.4 end 行） */
export const STOPPED_SUFFIX = "已停止生成";

/** 断流判定窗口：无任何行（含心跳）30s 即触发重连或错误分级 */
const STALL_TIMEOUT_MS = 30_000;
/** 重连尝试封顶次数（指数退避 1s/2s/4s 序列中 4s 留给第 4 次，故封顶 3 次） */
const MAX_RECONNECT_ATTEMPTS = 3;
/** 第 i（≥2）次尝试前的退避时长（毫秒）：第 2 次等 1s、第 3 次等 2s */
const RECONNECT_BACKOFF_MS = [1_000, 2_000];

// ===== 纯工具（无状态） =====

/** 终态判定：首个终态（end）或首个错误落位后，后续流事件整体幂等忽略 */
function isTerminal(state: ChatStreamState): boolean {
  return state.endedStatus !== null || state.error !== null;
}

/** 应用事件锚点 seq 到 lastEventId（无 seq 的事件不改动锚点） */
function applySeq(state: ChatStreamState, seq: number | null | undefined): ChatStreamState {
  if (seq === undefined || seq === null) return state;
  return { ...state, lastEventId: seq };
}

/** 在消息数组中按 runId 定位 AI 消息槽（从尾部线性找最后一个匹配） */
function findAssistantIndex(messages: StreamMessage[], runId: string): number {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "assistant" && messages[i].id === runId) return i;
  }
  return -1;
}

/**
 * 以不可变方式更新最后一条 AI 消息；不存在 AI 消息时原样返回传入状态
 * （无槽时的防御性忽略：reconnect 降级回放不含 metadata 的边缘场景不崩溃不建槽）
 */
function updateLastAssistant(
  state: ChatStreamState,
  update: (msg: StreamMessage) => StreamMessage,
): ChatStreamState {
  for (let i = state.messages.length - 1; i >= 0; i--) {
    const msg = state.messages[i];
    if (msg.role !== "assistant") continue;
    const messages = state.messages.slice();
    messages[i] = update(msg);
    return { ...state, messages };
  }
  return state;
}

/** AI 消息槽工厂（元数据默认值；thinking/text/sources/stages/tools 由事件逐步填充） */
function createAssistantMessage(init: { id: string; model: string }): StreamMessage {
  return {
    id: init.id,
    role: "assistant",
    content: "",
    attachments: [],
    model: init.model,
    thinking: "",
    thinkingEnded: false,
    text: "",
    sources: [],
    stages: [],
    tools: [],
    endStatus: null,
    messageId: null,
  };
}

/** 初始状态工厂（reset 与 hook 初始化共用；保留会话归属，其余清零） */
export function createInitialState(initialSessionId: string | null): ChatStreamState {
  return {
    messages: [],
    streaming: false,
    error: null,
    lastEventId: null,
    sessionId: initialSessionId,
    runId: null,
    endedStatus: null,
  };
}

/**
 * chatReducer：对话流纯函数状态机（10 事件 + send/reconnect/reset）
 *
 * 不可变更新契约：任何动作都不修改入参 state；终态/空槽等无操作路径返回原引用，
 * 冻结入参亦安全（幂等与纯函数测试锚点）。
 */
export function chatReducer(state: ChatStreamState, action: ChatAction): ChatStreamState {
  switch (action.type) {
    case "send": {
      // 用户发送：追加用户消息（新提问视为新一轮，清历史错误、置流式）
      const userMsg: StreamMessage = {
        id: action.id,
        role: "user",
        content: action.query,
        attachments: action.attachments,
        model: null,
        thinking: "",
        thinkingEnded: false,
        text: "",
        sources: [],
        stages: [],
        tools: [],
        endStatus: null,
        messageId: null,
      };
      // 关键：新 run 起始必须重置 run 级终态与锚点。上一轮 end 落位后若不清除，
      // 新 run 的 metadata 等流事件会被 isTerminal 幂等守卫整体吞掉，streaming 永久 true 页面假死；
      // 消息历史（messages）与会话归属（sessionId）保留，本 run 的锚点重新从零累积
      return {
        ...state,
        messages: [...state.messages, userMsg],
        streaming: true,
        error: null,
        endedStatus: null,
        runId: null,
        lastEventId: null,
      };
    }
    case "metadata": {
      // metadata：建 AI 槽（同 runId 二推幂等仅补 model）；sessionId 落位（不再 replace URL，E2E 实证修订）
      if (isTerminal(state)) return state;
      const next = applySeq(state, action.seq);
      const idx = findAssistantIndex(next.messages, action.runId);
      const messages =
        idx >= 0
          ? next.messages.map((msg, i) => (i === idx ? { ...msg, model: action.model } : msg))
          : [...next.messages, createAssistantMessage({ id: action.runId, model: action.model })];
      return {
        ...next,
        messages,
        // 空/缺失 sessionId 不覆盖既有会话归属（新会话在 metadata 到达前为 null）
        sessionId: action.sessionId || next.sessionId,
        runId: action.runId,
      };
    }
    case "thinking": {
      // thinking：思考卡流式追加（无槽/终态时防御性忽略）
      if (isTerminal(state)) return state;
      return updateLastAssistant(applySeq(state, action.seq), (msg) => ({
        ...msg,
        thinking: msg.thinking + action.delta,
      }));
    }
    case "thinking_end": {
      // thinking_end：思考卡折叠标记（UI 折叠为摘要行）
      if (isTerminal(state)) return state;
      return updateLastAssistant(applySeq(state, action.seq), (msg) => ({
        ...msg,
        thinkingEnded: true,
      }));
    }
    case "delta": {
      // delta：正文流式追加（Markdown 渲染源）
      if (isTerminal(state)) return state;
      return updateLastAssistant(applySeq(state, action.seq), (msg) => ({
        ...msg,
        text: msg.text + action.text,
      }));
    }
    case "tool_call": {
      // tool_call：插入 pending 工具卡（spinner）
      if (isTerminal(state)) return state;
      return updateLastAssistant(applySeq(state, action.seq), (msg) => ({
        ...msg,
        tools: [
          ...msg.tools,
          {
            toolCallId: action.toolCallId,
            toolName: action.toolName,
            input: action.input,
            status: "pending",
            output: null,
          },
        ],
      }));
    }
    case "tool_result": {
      // tool_result：按 toolCallId 配对转成功态；空串容错：同一 find 谓词下，
      // 空串工具卡按到达顺序（首个 pending）配对，等价于索引兜底
      if (isTerminal(state)) return state;
      return updateLastAssistant(applySeq(state, action.seq), (msg) => {
        const target = msg.tools.find(
          (t) => t.toolCallId === action.toolCallId && t.status === "pending",
        );
        if (!target) return msg;
        return {
          ...msg,
          tools: msg.tools.map((t) =>
            t === target
              ? {
                  ...t,
                  // 后端恒 success（design 注记）；非 success 映射 error 态保留枚举分支
                  status: action.status === "success" ? "success" : "error",
                  output: action.output,
                }
              : t,
          ),
        };
      });
    }
    case "sources": {
      // sources：写入当前 AI 消息（二推整体覆盖不重复；M10 降级重放不发本事件）
      if (isTerminal(state)) return state;
      return updateLastAssistant(applySeq(state, action.seq), (msg) => ({
        ...msg,
        sources: action.sources,
      }));
    }
    case "stage": {
      // stage：阶段进度按序追加（知识库查询中/正在生成回答等可见化）；
      // ring 全量回放（锚点丢失）可能重发已消费阶段——按阶段键去重保证幂等
      if (isTerminal(state)) return state;
      return updateLastAssistant(applySeq(state, action.seq), (msg) =>
        msg.stages.some((item) => item.stage === action.stage)
          ? msg
          : { ...msg, stages: [...msg.stages, { stage: action.stage, label: action.label }] },
      );
    }
    case "error": {
      // error：错误分级落位 + 解除流式（run 级错误/重连失败/认证失效统一入口）
      if (isTerminal(state)) return state;
      return {
        ...applySeq(state, action.seq),
        streaming: false,
        error: { kind: action.kind, message: action.message },
      };
    }
    case "end": {
      // end：首个终态幂等落位（COMPLETED 记 messageId / CANCELLED 追加停止后缀 / ERROR 分级）
      if (isTerminal(state)) return state;
      const next = applySeq(state, action.seq);
      if (action.status === "ERROR") {
        return updateLastAssistant(
          {
            ...next,
            streaming: false,
            endedStatus: "ERROR",
            error: { kind: "retryable", message: "回答生成失败" },
          },
          (msg) => ({ ...msg, endStatus: "ERROR" }),
        );
      }
      return updateLastAssistant(
        { ...next, streaming: false, endedStatus: action.status },
        (msg) =>
          action.status === "COMPLETED"
            ? { ...msg, endStatus: "COMPLETED", messageId: action.messageId ?? null }
            : { ...msg, endStatus: "CANCELLED", text: msg.text + STOPPED_SUFFIX },
      );
    }
    case "reconnect": {
      // reconnect：清除错误并恢复流式（手动重试入口；终态后保持幂等）
      return {
        ...state,
        error: null,
        streaming: state.endedStatus === null ? true : state.streaming,
      };
    }
    case "reset": {
      // reset：清消息/流式/错误/终态/事件锚点，保留会话归属
      return createInitialState(state.sessionId);
    }
  }
}

// ===== SSE 事件 → reducer 动作映射（纯函数，未知事件/坏 JSON 返回 null 静默忽略） =====

/** 解析事件 data 为对象；非法 JSON / 非对象值返回 null */
function parseEventData(data: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(data);
    return parsed !== null && typeof parsed === "object"
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/** 字符串字段容错提取（缺失/非字符串回退默认值） */
function strField(payload: Record<string, unknown>, key: string, fallback = ""): string {
  const value = payload[key];
  return typeof value === "string" ? value : fallback;
}

/**
 * 将一条 SSE 命名事件映射为 reducer 动作（10 事件转发；未知事件/非法 JSON/未知 end 状态 → null）
 *
 * @param name SSE 事件名（metadata/thinking/thinking_end/delta/tool_call/tool_result/sources/error/end）
 * @param data 事件 data 行原文（后端原始 JSON，无引号包裹）
 * @param seq  事件 id 行解析出的 seq（无 id 行为 null），作为断流重连锚点
 */
export function sseEventToAction(
  name: string,
  data: string,
  seq: number | null,
): ChatAction | null {
  const payload = parseEventData(data);
  if (payload === null) return null;
  switch (name) {
    case "metadata":
      return {
        type: "metadata",
        runId: strField(payload, "runId"),
        sessionId: strField(payload, "sessionId"),
        model: strField(payload, "model"),
        seq,
      };
    case "thinking":
      return { type: "thinking", delta: strField(payload, "delta"), seq };
    case "thinking_end":
      return { type: "thinking_end", seq };
    case "delta":
      return { type: "delta", text: strField(payload, "text"), seq };
    case "tool_call":
      return {
        type: "tool_call",
        toolCallId: strField(payload, "toolCallId"),
        toolName: strField(payload, "toolName"),
        input: payload["input"],
        seq,
      };
    case "tool_result":
      return {
        type: "tool_result",
        toolCallId: strField(payload, "toolCallId"),
        status: strField(payload, "status"),
        output: payload["output"],
        seq,
      };
    case "sources": {
      const sources = payload["sources"];
      return {
        type: "sources",
        sources: Array.isArray(sources) ? (sources as RetrievalSource[]) : [],
        seq,
      };
    }
    case "stage": {
      // STAGE 阶段事件（2026-08-27）：stage 键合法才转发，label 缺省回退键名（不阻断）
      const stage = strField(payload, "stage");
      if (!STAGE_KEYS.has(stage)) return null;
      return {
        type: "stage",
        stage: stage as ChatStageKey,
        label: strField(payload, "label", stage),
        seq,
      };
    }
    case "error":
      // 双形态分流：重连失败 code=REPLAY_FAILED → replay_failed；其余（run 级 ERROR）→ retryable
      return {
        type: "error",
        kind: payload["code"] === "REPLAY_FAILED" ? "replay_failed" : "retryable",
        message: strField(payload, "message", "回答生成失败"),
        seq,
      };
    case "end": {
      const status = payload["status"];
      if (status !== "COMPLETED" && status !== "CANCELLED" && status !== "ERROR") return null;
      return {
        type: "end",
        status,
        messageId: strField(payload, "messageId") || null,
        seq,
      };
    }
    default:
      // 未知事件名（如未来新增事件）：上层静默忽略，不落状态
      return null;
  }
}

// ===== useChatStream：fetch + ReadableStream + TextDecoder 流式消费 =====

/** SSE id 行解析：seq 为后端 Integer（事件序号），非法值按无锚点处理 */
function parseSeq(id: string | null): number | null {
  if (id === null) return null;
  const seq = Number(id);
  return Number.isFinite(seq) ? seq : null;
}

/** 本地消息键生成（模块级递增计数；仅用户消息使用，AI 消息键=runId） */
let localIdSeq = 0;
function nextLocalId(): string {
  localIdSeq += 1;
  return `local-${localIdSeq}`;
}

/** 线程安全的睡眠（重连指数退避用；fake timers 可精确驱动） */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 对话流 hook：SSE 10 事件状态机 + 停止/重连/409/错误分级四条生命周期路径
 *
 * @param initialSessionId 初始会话 id（/chat 新会话为 null，/chat/[sessionId] 传入 URL 参数）；
 *                         metadata 到达后用其值覆盖（E2E 实证修订：新会话不 replace URL）
 * @returns state 全量对话状态；send/cancel/reconnect/reset 四个生命周期操作（reset 供
 *          REPLAY_FAILED 错误横幅「重新提问」入口清空对话，保留会话归属）
 */
export function useChatStream(initialSessionId: string | null): {
  state: ChatStreamState;
  send: (query: string, attachments: AttachmentRecord[]) => Promise<void>;
  cancel: () => Promise<void>;
  reconnect: () => Promise<void>;
  reset: () => void;
} {
  const [state, dispatch] = useReducer(chatReducer, initialSessionId, createInitialState);
  // 最新状态镜像：供 send/cancel/reconnect 等异步回调读取（闭包捕获首渲染实例，经 ref 拿最新值）
  const stateRef = useRef(state);
  // 流世代号：每次新流（send/reconnect 成功）自增，旧读取循环经世代检查失活
  const genRef = useRef(0);
  // 重连周期互斥：断流计时触发与手动重试不并发跑两条退避链
  const reconnectBusyRef = useRef(false);
  // 断流计时器句柄（30s 无任何行触发重连或错误分级）
  const stallTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 当前读取器句柄：新流接管/卸载时 cancel 释放旧流
  const readerRef = useRef<ReadableStreamDefaultReader<Uint8Array> | null>(null);

  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  /** 清除断流计时器（终态/心跳重置/流关闭时调用） */
  function clearStallTimer(): void {
    if (stallTimerRef.current !== null) {
      clearTimeout(stallTimerRef.current);
      stallTimerRef.current = null;
    }
  }

  /** 将运行中流的错误响应体转为 ApiError（409/503 等，供上层 toast 文案） */
  async function toApiError(response: Response): Promise<ApiError> {
    let message = `请求失败（HTTP ${response.status}）`;
    try {
      const body = (await response.json()) as { message?: unknown };
      if (typeof body?.message === "string" && body.message) message = body.message;
    } catch {
      // 非 JSON 响应体（网关错误页等）：保留 HTTP 兜底文案
    }
    return new ApiError(response.status, message);
  }

  /**
   * 消费 SSE 流：ReadableStream + TextDecoder 逐帧喂解析器；
   * 心跳/任意字节重置 30s 断流计时；事件锚点 seq 写入 lastEventId
   * @param response postChat/reconnectChat 返回的原始响应（流式体）
   * @param gen 所属流世代号（世代失活后本循环不再派发事件）
   */
  async function consumeStream(response: Response, gen: number): Promise<void> {
    let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
    // 本流内是否已消费终态事件（end/error）：独立于 React 提交时机做 EOF 判定，
    // stateRef 经 effect 更新晚于 dispatch（宏任务），流尾紧邻终态事件时读到的是旧值，
    // 直接以事件流本身为准（确定性判定，且保证已终态收流的 EOF 不触发重连）
    let finished = false;
    try {
      if (!response.body) {
        // 响应体缺失（异常空流）：视为连接失败，错误分级
        if (genRef.current === gen) {
          dispatch({ type: "error", kind: "retryable", message: "连接已断开" });
        }
        return;
      }
      reader = response.body.getReader();
      readerRef.current = reader;
      const decoder = new TextDecoder();
      const feed = createSseParser({
        onEvent: (name, data, id) => {
          // 世代检查：重连/新 send/卸载后本流事件一律丢弃
          if (genRef.current !== gen) return;
          const action = sseEventToAction(name, data, parseSeq(id));
          if (!action) return;
          dispatch(action);
          // 终态事件（end/error）到达后断流计时已无意义，立即清除
          if (action.type === "end" || action.type === "error") {
            finished = true;
            clearStallTimer();
          }
        },
        onHeartbeat: () => {
          // 心跳保活帧：重置 30s 断流计时（设计 §1.5.4 :heartbeat 行）
          if (genRef.current === gen) armStallTimer(gen);
        },
      });
      // 流建立即开始计断流窗口
      armStallTimer(gen);
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        // 任意字节到达均视为活跃，重置断流窗口后再喂解析器
        armStallTimer(gen);
        feed(decoder.decode(value, { stream: true }));
      }
      // 流尾 flush 兜底：{stream:true} 模式下解码器可能滞留断流前残包末尾的多字节字符
      // （如汉字被截断在最后一个 chunk），收尾 decode() 把剩余字节也喂给解析器，避免尾部内容缺失
      feed(decoder.decode());
      // 流被服务端干净关闭（done）但本流未消费任何终态事件（end/error）：
      // 视为连接被服务端断开（与 30s 断流同一语义），走既有断流路径
      // （runReconnect 指数退避续流），不重复造第二套错误分级；
      // 已终态/已错误为正常收尾，不再动作。
      // 注意：先让出宏任务再读 stateRef：dispatch 的 React 渲染提交晚于本同步链
      // （瞬时流可在渲染提交前读完 EOF），立即判读会把 streaming 误判为未置位
      // 而漏掉重连（E2E route-mock 实证）；50ms 足够提交且对真实持续流无感知影响
      if (genRef.current === gen && !finished) {
        await new Promise((resolve) => setTimeout(resolve, 50));
        if (genRef.current !== gen) return;
        const current = stateRef.current;
        if (current.endedStatus === null && current.error === null && current.streaming) {
          void runReconnect();
        }
      }
    } catch {
      // 读取异常（连接被服务端掐断等）：非世代变更导致的错误分级为 retryable
      if (genRef.current === gen) {
        dispatch({ type: "error", kind: "retryable", message: "连接中断，请重试" });
      }
    } finally {
      if (genRef.current === gen) {
        readerRef.current = null;
        clearStallTimer();
      }
    }
  }

  /**
   * 启动/切换一条流：世代自增使旧循环失活，并释放旧读取器
   * @param response 新流的原始响应（send 的 POST 或 reconnect 的 GET）
   */
  function startStream(response: Response): void {
    const gen = ++genRef.current;
    // 释放旧读取器（取消挂起的 read，避免泄漏旧连接）
    void readerRef.current?.cancel().catch(() => {});
    readerRef.current = null;
    void consumeStream(response, gen);
  }

  /**
   * 重连周期：指数退避（1s/2s/4s 序列，封顶 3 次尝试）调 GET reconnect
   * 成功 → 新流接管续流；全部失败 → retryable 错误分级并解除流式
   */
  async function runReconnect(): Promise<void> {
    if (reconnectBusyRef.current) return;
    reconnectBusyRef.current = true;
    try {
      for (let attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt += 1) {
        // 第 2 次起按指数退避等待（1s/2s；4s 留给第 4 次，封顶 3 次不产生）
        if (attempt > 1) {
          await sleep(RECONNECT_BACKOFF_MS[attempt - 2]);
        }
        const current = stateRef.current;
        // 重连期间终态/新 run 到达（如用户另开新问题）→ 放弃本次重连；
        // 注：错误态不在此列，手动重试入口（reconnect 动作）已先清错误
        if (current.endedStatus !== null) return;
        const runId = current.runId;
        if (!runId) return;
        let response: Response;
        try {
          // 锚点 lastEventId：断流处续放（null=全量回放）
          response = await reconnectChat(runId, current.lastEventId);
        } catch {
          // 网络层失败：退避后进入下一次尝试
          continue;
        }
        if (!response.ok) {
          // 服务端拒绝（run 已终结/暂不可用）：退避后下一次尝试
          continue;
        }
        // 重连成功：新流接管（世代切换），返回后交还心跳/事件驱动
        startStream(response);
        return;
      }
      // 三次尝试全部失败：错误分级 + 解除流式（页面横幅与重试入口）
      dispatch({ type: "error", kind: "retryable", message: "连接已断开，请重试" });
    } finally {
      reconnectBusyRef.current = false;
    }
  }

  /**
   * 布防 30s 断流计时器：到点后若流仍活跃（未终态、无错误、流式中）触发重连周期
   * @param gen 所属流世代号（世代失活后计时器作废，由新流重新布防）
   */
  function armStallTimer(gen: number): void {
    clearStallTimer();
    stallTimerRef.current = setTimeout(() => {
      stallTimerRef.current = null;
      if (genRef.current !== gen) return;
      const current = stateRef.current;
      // 已终态/已错误/非流式（如用户已停止）：断流窗口不再触发重连
      if (current.endedStatus !== null || current.error !== null || !current.streaming) return;
      void runReconnect();
    }, STALL_TIMEOUT_MS);
  }

  /**
   * 发送提问：POST /student/chat（含 401 刷新重放）。响应非 2xx 一律抛 ApiError 不落状态
   * （409 冲突语义：streaming 保持、error 保持 null，toast 由上层处理）；
   * 仅响应确立（非 409 且可流式）后才追加用户消息并启动流消费，杜绝失败路径的幽灵消息
   */
  async function send(query: string, attachments: AttachmentRecord[]): Promise<void> {
    let response: Response;
    try {
      response = await postChat({
        sessionId: stateRef.current.sessionId,
        query,
        attachments: attachments.length === 0 ? null : attachments,
      });
    } catch (error) {
      // 发送阶段 401（api 层单飞刷新失败已全局登出）：落 auth 分级供页面感知
      if (error instanceof ApiError && error.code === 401) {
        dispatch({ type: "error", kind: "auth", message: error.message });
      }
      throw error;
    }
    if (!response.ok) {
      // 409/503 等：不 dispatch 任何状态动作（见函数注释），向上抛供上层 toast
      throw await toApiError(response);
    }
    dispatch({ type: "send", id: nextLocalId(), query, attachments });
    startStream(response);
  }

  /**
   * 取消当前 run：POST /student/chat/{runId}/cancel（尽力而为）。
   * 终态后再取消后端 409、网络失败等一律静默：流仍会走 end 终态收尾，不污染状态
   */
  async function cancel(): Promise<void> {
    const runId = stateRef.current.runId;
    if (!runId) return;
    try {
      await cancelRun(runId);
    } catch {
      // 409（run 已结束）/网络失败：静默吞（设计 §1.5.4：终态后 409 静默）
    }
  }

  /**
   * 手动重连（错误横幅「重试」入口）：无 run / 已终态不动作；
   * 先清错误恢复流式，再走完整重连周期（退避最多 3 次）
   */
  async function reconnect(): Promise<void> {
    const current = stateRef.current;
    if (!current.runId || current.endedStatus !== null) return;
    dispatch({ type: "reconnect" });
    await runReconnect();
  }

  // 卸载清理：世代失活流循环、清除断流计时、释放读取器
  useEffect(() => {
    return () => {
      genRef.current += 1;
      clearStallTimer();
      void readerRef.current?.cancel().catch(() => {});
      readerRef.current = null;
    };
  }, []);

  return {
    state,
    send,
    cancel,
    reconnect,
    // 重新提问入口：清空消息/流式/错误/终态/锚点（保留会话归属），由 REPLAY_FAILED 横幅调用
    reset: () => dispatch({ type: "reset" }),
  };
}
