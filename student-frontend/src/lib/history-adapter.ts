/**
 * 历史消息适配器：StudentMessage[] → StreamMessage[]（Task 13 核心；2026-08-28 时间线改版）
 *
 * 映射规则（设计文档 §六.6 R1 历史回显 + 审核补记 G8）：
 * - 输入先按 (createdAt, seq) 稳定排序（seq 为 run 内序号，createdAt 兜底跨 run 顺序），
 *   时间轴节点按行序（到达序）重建——与实时 SSE timeline 同构
 * - USER 行 → 用户消息：正文 + attachments 透传。
 *   G8：历史附件 url 是 MinIO objectKey 不可直接访问，本适配器不建任何 blob 预览，
 *   由 MessageList 的 UserAttachmentChips 降级为类型图标 + 文件名（无图片缩略图）
 * - ASSISTANT 行按 runId 归并为一条 AI 消息（run 内顺序由排序后的行序保证）：
 *   - messageType=null（正文行）→ text 按序拼接；sources 取最后一组非空数组 → 来源卡
 *     与时间轴来源节点（原位替换，保持首现位置）；messageId = 最后一条正文行 id
 *     （J5 反馈唯一来源）；intentType 原样透传
 *   - messageType=thinking → 时间轴思考节点：按 thinkingStage 归组（存量行 null/非法值
 *     降级 generating），同 stage 多行合并 lines，恒 ended=true（持久化即完成态）
 *   - messageType=query_plan → content JSON.parse + zod 校验建查询计划节点
 *     （坏 JSON/结构非法静默跳过，不阻断回显）
 *   - messageType=TOOL_CALL → 时间轴 pending 工具节点（content 为 JSON 串，与实时事件
 *     格式一致；坏 JSON 防御兜底）
 *   - messageType=TOOL_RESULT → 按 toolCallId 配对本 run 首个 pending 工具节点原位更新
 *     （空串同一谓词按到达顺序配对，与实时 chatReducer 语义一致）；无配对忽略
 * - 历史消息一律落 endStatus=COMPLETED（持久化即完成态；取消/异常 run 的 assistant
 *   行由服务端过滤不下发，前端天然只见完整 run）
 */
import type { StreamMessage, StreamTool } from "@/hooks/use-chat-stream";
import {
  queryPlanPayloadSchema,
  type ChatStageKey,
  type RetrievalSource,
  type StudentMessage,
  type TimelineNode,
} from "./types";

/** 合法思考阶段键集合（与 use-chat-stream STAGE_KEYS 同源契约；本模块独立判定不跨层引用） */
const THINKING_STAGES: ReadonlySet<string> = new Set([
  "attachments",
  "understanding",
  "generating",
]);

/** TOOL_CALL 行 JSON 内容解析结果（字段缺省按实时事件同形兜底） */
interface ToolCallPayload {
  toolCallId: string;
  toolName: string;
  input: unknown;
}

/** TOOL_RESULT 行 JSON 内容解析结果（status 缺省按 success，后端恒 success） */
interface ToolResultPayload {
  toolCallId: string;
  status: string;
  output: unknown;
}

/** 字符串字段容错提取（缺失/非字符串回退默认值） */
function strField(payload: Record<string, unknown>, key: string, fallback = ""): string {
  const value = payload[key];
  return typeof value === "string" ? value : fallback;
}

/**
 * 思考阶段键归一化：合法集合内原样透传；null/缺失/非法值降级 generating
 * （契约：存量 thinking 行无 thinking_stage 列值时按 generating 渲染，接口不报错）
 */
function normalizeThinkingStage(stage: string | null): ChatStageKey {
  return stage !== null && THINKING_STAGES.has(stage) ? (stage as ChatStageKey) : "generating";
}

/** 解析行内容为 JSON 对象（坏 JSON / 非对象值返回 null） */
function parseJsonPayload(content: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(content);
    return parsed !== null && typeof parsed === "object"
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/** 解析 TOOL_CALL 行：坏 JSON 防御兜底（toolName 显「工具调用」，input 保留原文） */
function parseToolCall(content: string): ToolCallPayload {
  const payload = parseJsonPayload(content);
  if (!payload) {
    return { toolCallId: "", toolName: "工具调用", input: content };
  }
  return {
    toolCallId: strField(payload, "toolCallId"),
    toolName: strField(payload, "toolName", "工具调用"),
    input: payload["input"] ?? null,
  };
}

/** 解析 TOOL_RESULT 行：坏 JSON 防御兜底（status 按 success，output 保留原文） */
function parseToolResult(content: string): ToolResultPayload {
  const payload = parseJsonPayload(content);
  if (!payload) {
    return { toolCallId: "", status: "success", output: content };
  }
  return {
    toolCallId: strField(payload, "toolCallId"),
    status: strField(payload, "status", "success"),
    output: payload["output"] ?? null,
  };
}

/**
 * 在 run 归并草稿中追加一条工具结果：按 toolCallId 配对本 run 首个 pending 工具卡
 *（空串同一谓词按到达顺序配对；已配对/无匹配返回 false 静默忽略）
 */
function pairToolResult(tools: StreamTool[], result: ToolResultPayload): void {
  const target = tools.find(
    (tool) => tool.toolCallId === result.toolCallId && tool.status === "pending",
  );
  if (!target) {
    return;
  }
  target.status = result.status === "success" ? "success" : "error";
  target.output = result.output;
}

/**
 * 时间轴 upsert 思考节点：同 stage 既有节点合并（倒序找最近一个），否则新建
 * ended=true 节点（历史行齐备 = 思考已完成）。行内容按增量并入（与实时 reducer
 * mergeThinkingLines 同语义：首段续接末行、其余各起新行）——存量数据同 stage
 * 多行（逐 delta 落库的旧行）拼接后与实时累积渲染一致；空白行过滤（终态数据）
 */
function upsertHistoryThinkingNode(
  timeline: TimelineNode[],
  stage: ChatStageKey,
  content: string,
): void {
  // 行内容按换行拆分、首尾空白裁剪、空白行过滤（终态数据无「进行中行」概念）
  const parts = content
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  if (parts.length === 0) return;
  for (let i = timeline.length - 1; i >= 0; i -= 1) {
    const node = timeline[i];
    if (node.kind === "thinking" && node.stage === stage) {
      // 首段续接末行（同 stage 多行拼接），其余各起新行
      if (node.lines.length === 0) {
        node.lines.push(...parts);
      } else {
        node.lines[node.lines.length - 1] += parts[0];
        node.lines.push(...parts.slice(1));
      }
      return;
    }
  }
  timeline.push({ kind: "thinking", stage, lines: parts, ended: true });
}

/** 单条 ASSISTANT 行的 run 归并草稿（跨行累积，run 结束时写入 StreamMessage） */
interface RunDraft {
  runId: string;
  /** 正文行内容（messageType=null）按序拼接 */
  textParts: string[];
  /** thinking 行内容按序拼接 */
  thinkingParts: string[];
  /** 工具卡（TOOL_CALL 插入 / TOOL_RESULT 配对） */
  tools: StreamTool[];
  /** 时间轴节点（按行序到达序重建；thinking/query_plan/tool/sources 各建节点） */
  timeline: TimelineNode[];
  /** 来源卡数据（取最后一组非空数组） */
  sources: RetrievalSource[];
  /** 最后一条正文行 id（J5 反馈来源） */
  mainMessageId: string | null;
  /** 意图（存量消息可能为 null/unknown，原样透传由 FeedbackBar 过滤） */
  intentType: string | null;
}

/** USER 行 → 用户消息（正文 + 附件；历史态无本地 blob 预览，G8 由 MessageList 降级） */
function toUserMessage(row: StudentMessage): StreamMessage {
  return {
    id: row.id,
    role: "user",
    content: row.content,
    attachments: row.attachments,
    model: null,
    thinking: "",
    thinkingEnded: false,
    text: "",
    sources: [],
    stages: [],
    tools: [],
    timeline: [],
    endStatus: null,
    messageId: null,
  };
}

/**
 * 历史消息 → 对话流视图模型（消息流组件族直接消费；时间轴与实时 SSE 同构）
 *
 * @param messages R1 接口返回的历史消息行（可按任意顺序，内部稳定排序）
 * @returns 时间序的用户消息与 AI 消息；空输入返回空数组
 */
export function historyAdapter(messages: StudentMessage[]): StreamMessage[] {
  if (messages.length === 0) {
    return [];
  }
  const rows = [...messages].sort((a, b) =>
    // 稳定排序：createdAt 跨 run 定序，同刻按 run 内 seq 定序
    a.createdAt === b.createdAt ? a.seq - b.seq : a.createdAt.localeCompare(b.createdAt),
  );
  const output: StreamMessage[] = [];
  // runId → 归并草稿与其在 output 中的下标（首行出现时插入占位，后续行就地累积）
  const drafts = new Map<string, RunDraft>();
  const indices = new Map<string, number>();

  for (const row of rows) {
    if (row.role !== "ASSISTANT") {
      // USER（或未知角色）行按用户消息原样输出（取消 run 的 assistant 行由服务端
      // 过滤不下发——USER 行可能无后续 AI 消息，独立成条不挂时间轴）
      output.push(toUserMessage(row));
      continue;
    }
    const { runId } = row;
    let draft = drafts.get(runId);
    if (!draft) {
      draft = {
        runId,
        textParts: [],
        thinkingParts: [],
        tools: [],
        timeline: [],
        sources: [],
        mainMessageId: null,
        intentType: row.intentType,
      };
      drafts.set(runId, draft);
      // 首行出现位置插入 AI 消息占位（保证 user → assistant 交错顺序）
      output.push({
        id: runId,
        role: "assistant",
        content: "",
        attachments: [],
        model: null,
        thinking: "",
        thinkingEnded: true,
        text: "",
        sources: [],
        // 历史消息无 STAGE 事件可回放（阶段是瞬时进度，不落库）——恒空数组
        stages: [],
        tools: [],
        timeline: [],
        endStatus: "COMPLETED",
        messageId: null,
        intentType: row.intentType,
      });
      indices.set(runId, output.length - 1);
    } else if (draft.intentType === null) {
      // 后续行携带意图时补齐（存量行可能只有部分行有 intentType）
      draft.intentType = row.intentType;
    }

    switch (row.messageType) {
      case "thinking":
        // thinking 行：思考文本累积（持久化行齐备 → 折叠思考卡）+ 时间轴同 stage 合并节点
        draft.thinkingParts.push(row.content);
        upsertHistoryThinkingNode(
          draft.timeline,
          normalizeThinkingStage(row.thinkingStage),
          row.content,
        );
        break;
      case "query_plan": {
        // query_plan 行：content 原样 JSON 由前端 parse + zod 校验（坏数据静默跳过）
        const payload = parseJsonPayload(row.content);
        const parsed = payload === null ? null : queryPlanPayloadSchema.safeParse(payload);
        if (parsed !== null && parsed.success) {
          // 每 run 语义唯一：二现原位替换（与实时 reducer replaceOrPush 语义一致）
          const node: TimelineNode = {
            kind: "queryPlan",
            intent: parsed.data.intent,
            rewritten: parsed.data.rewritten,
            courseNames: parsed.data.filters.courseNames,
          };
          const index = draft.timeline.findIndex((item) => item.kind === "queryPlan");
          if (index >= 0) {
            draft.timeline[index] = node;
          } else {
            draft.timeline.push(node);
          }
        }
        break;
      }
      case "TOOL_CALL": {
        // 工具调用行：插入 pending 工具卡（后续 TOOL_RESULT 按 toolCallId 配对）
        const call = parseToolCall(row.content);
        draft.tools.push({ ...call, status: "pending", output: null });
        draft.timeline.push({
          kind: "tool",
          toolCallId: call.toolCallId,
          toolName: call.toolName,
          input: call.input,
          status: "pending",
          output: null,
        });
        break;
      }
      case "TOOL_RESULT": {
        // 工具结果行：配对本 run 内首个同 toolCallId 的 pending 工具卡（双通道同步原位更新）
        const result = parseToolResult(row.content);
        pairToolResult(draft.tools, result);
        const target = draft.timeline.find(
          (item) =>
            item.kind === "tool" &&
            item.toolCallId === result.toolCallId &&
            item.status === "pending",
        );
        if (target && target.kind === "tool") {
          target.status = result.status === "success" ? "success" : "error";
          target.output = result.output;
        }
        break;
      }
      default:
        // messageType=null（正文行）：正文拼接；sources 取最后一组非空 → 来源卡与时间轴
        // 来源节点（原位替换保持首现位置）；意图/反馈 id 落位
        draft.textParts.push(row.content);
        if (row.sources.length > 0) {
          draft.sources = row.sources;
          const node: TimelineNode = { kind: "sources", sources: row.sources };
          const index = draft.timeline.findIndex((item) => item.kind === "sources");
          if (index >= 0) {
            draft.timeline[index] = node;
          } else {
            draft.timeline.push(node);
          }
        }
        draft.mainMessageId = row.id;
        if (row.intentType !== null) {
          draft.intentType = row.intentType;
        }
    }

    // run 归并结果同步回占位消息（就地更新，保持 output 顺序稳定）
    const index = indices.get(runId) as number;
    const target = output[index];
    target.text = draft.textParts.join("");
    target.thinking = draft.thinkingParts.join("");
    target.tools = draft.tools;
    target.sources = draft.sources;
    target.timeline = draft.timeline;
    target.messageId = draft.mainMessageId;
    target.intentType = draft.intentType;
  }
  return output;
}
