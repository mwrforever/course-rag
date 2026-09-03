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
 *     降级 generating），同 stage 同次 LLM 调用多行合并 lines（2026-08-30 按调用拆分：
 *     上一张同 stage 思考卡之后已有工具节点则另起新卡——主 agent 每次模型调用一块思考卡），
 *     恒 ended=true（持久化即完成态）
 *   - messageType=TOOL_CALL → 时间轴 pending 工具节点（content 为 JSON 串，与实时事件
 *     格式一致；坏 JSON 防御兜底）
 *   - messageType=TOOL_RESULT → 按 toolCallId 配对本 run 首个 pending 工具节点原位更新
 *     （空串同一谓词按到达顺序配对，与实时 chatReducer 语义一致）；无配对忽略
 *   - 2026-08-30 对齐设计稿：query_plan 行不再建节点（重写正文/意图胶囊不回前端展示；
 *     数据仍落库供审计）
 * - M4（2026-09-01 问题修复）+ 2026-09-03 停止态改版：终态三态口径——runStatus 随行
 *   透传，CANCELLED/ERROR run 的半截回答全量保留并落对应终态（CANCELLED 底部小字提示 /
 *   ERROR 徽标数据源），旧数据无 runStatus 保持 COMPLETED 向后兼容；run 无任何内容行
 *   （仅 query_plan 等不回显行）剔除空 AI 消息占位（不渲染空回答）
 */
import { STAGE_KEYS, type StreamMessage } from "@/hooks/use-chat-stream";
import {
  type ChatStageKey,
  type RetrievalSource,
  type StudentMessage,
  type TimelineNode,
} from "./types";

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
 * （契约：存量 thinking 行无 thinking_stage 列值时按 generating 渲染，接口不报错）。
 * 合法集合复用实时流 STAGE_KEYS 单一事实源（BUG-22：曾因独立维护缺 retrieving，
 * 致 stage=retrieving 的历史思考行被降级归并为 generating 卡）
 */
function normalizeThinkingStage(stage: string | null): ChatStageKey {
  return stage !== null && STAGE_KEYS.has(stage) ? (stage as ChatStageKey) : "generating";
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
 * 在时间轴原位配对一条工具结果：按 toolCallId 找本 run 首个 pending 工具节点
 * 写结果（空串同一谓词按到达顺序配对，与实时 chatReducer 语义一致）；
 * 无配对静默忽略
 */
function pairTimelineToolResult(timeline: TimelineNode[], result: ToolResultPayload): void {
  const target = timeline.find(
    (node) =>
      node.kind === "tool" && node.toolCallId === result.toolCallId && node.status === "pending",
  );
  if (!target || target.kind !== "tool") {
    return;
  }
  // 后端恒 success（design 注记）；非 success 映射 error 态保留枚举分支
  target.status = result.status === "success" ? "success" : "error";
  target.output = result.output;
}

/**
 * 时间轴 upsert 思考节点：同 stage 既有节点合并（倒序找最近一个），否则新建
 * ended=true 节点（历史行齐备 = 思考已完成）。行内容按增量并入（与实时 reducer
 * mergeThinkingLines 同语义：首段续接末行、其余各起新行）——存量数据同 stage
 * 多行（逐 delta 落库的旧行）拼接后与实时累积渲染一致；空白行过滤（终态数据）。
 *
 * 2026-08-30 思考卡按 LLM 调用拆分（与实时 reducer upsertThinkingNode 同规则）：
 * 最近一张同 stage 思考卡之后已出现工具节点 → 视为新一次模型调用的思考，另起新卡
 * （主 agent 每次调用之间必隔工具调用，TOOL_CALL 行即调用边界）
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
      // 上一张同 stage 卡之后已有工具节点 → 新一次 LLM 调用的思考，另起新卡
      if (hasToolNodeAfter(timeline, i)) {
        break;
      }
      // 首段续接末行（同 stage 同次调用多行拼接），其余各起新行；
      // 既有节点恒有 ≥1 行（空内容在上方守卫已拦截，无空行节点入轴）
      node.lines[node.lines.length - 1] += parts[0];
      node.lines.push(...parts.slice(1));
      return;
    }
  }
  timeline.push({ kind: "thinking", stage, lines: parts, ended: true });
}

/** 时间轴中 index 之后是否存在工具节点（主 agent LLM 调用边界标记；与实时 reducer 同规则） */
function hasToolNodeAfter(timeline: TimelineNode[], index: number): boolean {
  for (let i = index + 1; i < timeline.length; i += 1) {
    if (timeline[i].kind === "tool") return true;
  }
  return false;
}

/** 单条 ASSISTANT 行的 run 归并草稿（跨行累积，run 结束时写入 StreamMessage） */
interface RunDraft {
  runId: string;
  /** 正文行内容（messageType=null）按序拼接 */
  textParts: string[];
  /** 时间轴节点（按行序到达序重建；thinking/tool/sources 各建节点，query_plan 不回显） */
  timeline: TimelineNode[];
  /** 来源卡数据（取最后一组非空数组） */
  sources: RetrievalSource[];
  /** 最后一条正文行 id（J5 反馈来源） */
  mainMessageId: string | null;
  /** 意图（存量消息可能为 null/unknown，原样透传由 FeedbackBar 过滤） */
  intentType: string | null;
  /** 所属 run 终态（M4：COMPLETED/CANCELLED/ERROR，随行透传；旧行缺省 null → COMPLETED） */
  runStatus: string | null;
  /** run 错误信息（M4：仅 runStatus=ERROR 行有值，徽标 tooltip 文案） */
  errorMessage: string | null;
}

/** USER 行 → 用户消息（正文 + 附件；历史态无本地 blob 预览，G8 由 MessageList 降级） */
function toUserMessage(row: StudentMessage): StreamMessage {
  return {
    id: row.id,
    role: "user",
    content: row.content,
    attachments: row.attachments,
    model: null,
    text: "",
    sources: [],
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
      // USER（或未知角色）行按用户消息原样输出——M4 起服务端全量下发历史行（含取消/
      // 错误 run 的 assistant 行），USER 行后无 AI 消息的真实原因是取消/失败发生在首
      // chunk 前仅落了 USER 行；此时 USER 独立成条、不挂时间轴
      output.push(toUserMessage(row));
      continue;
    }
    const { runId } = row;
    let draft = drafts.get(runId);
    if (!draft) {
      draft = {
        runId,
        textParts: [],
        timeline: [],
        sources: [],
        mainMessageId: null,
        intentType: row.intentType,
        runStatus: null,
        errorMessage: null,
      };
      drafts.set(runId, draft);
      // 首行出现位置插入 AI 消息占位（保证 user → assistant 交错顺序）
      output.push({
        id: runId,
        role: "assistant",
        content: "",
        attachments: [],
        model: null,
        text: "",
        sources: [],
        // 历史消息无 STAGE 事件可回放（阶段是瞬时进度，不落库）——时间轴由
        // thinking/tool/sources 行重建，阶段节点天然缺席（query_plan 行对齐设计稿不回显）
        timeline: [],
        // 占位默认 COMPLETED，本轮行处理末尾的同步块按 runStatus 落实际终态（M4）
        endStatus: "COMPLETED",
        messageId: null,
        intentType: row.intentType,
        errorMessage: null,
      });
      indices.set(runId, output.length - 1);
    } else if (draft.intentType === null) {
      // 后续行携带意图时补齐（存量行可能只有部分行有 intentType）
      draft.intentType = row.intentType;
    }
    // M4：历史回显按行携带的 runStatus 落终态（同 run 各行同值幂等覆盖；
    // 旧数据无 runStatus → null，同步块兜底 COMPLETED 向后兼容）
    draft.runStatus = row.runStatus ?? null;
    draft.errorMessage = row.errorMessage ?? null;

    switch (row.messageType) {
      case "thinking":
        // thinking 行：时间轴同 stage 同次调用合并节点（持久化行齐备 → 恒 ended=true；
        // 2026-08-30 按 LLM 调用拆分：上一张同 stage 卡之后有工具节点则另起新卡）
        upsertHistoryThinkingNode(
          draft.timeline,
          normalizeThinkingStage(row.thinkingStage),
          row.content,
        );
        break;
      case "query_plan":
        // 2026-08-30 对齐设计稿：query_plan 行不再建节点不回显（重写正文/意图胶囊
        // 不回前端；数据仍落库供审计），显式跳过避免落入 default 正文拼接分支
        break;
      case "TOOL_CALL": {
        // 工具调用行：时间轴插入 pending 工具节点（后续 TOOL_RESULT 按 toolCallId 配对）
        const call = parseToolCall(row.content);
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
        // 工具结果行：时间轴原位配对本 run 内首个同 toolCallId 的 pending 工具节点
        pairTimelineToolResult(draft.timeline, parseToolResult(row.content));
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
    target.sources = draft.sources;
    target.timeline = draft.timeline;
    target.messageId = draft.mainMessageId;
    target.intentType = draft.intentType;
    // M4：按 runStatus 落终态（CANCELLED/ERROR 半截现场保留 + 徽标数据源；
    // 旧数据无 runStatus → null 时保持 COMPLETED 向后兼容）
    const terminalStatus: StreamMessage["endStatus"] =
      draft.runStatus === "CANCELLED" || draft.runStatus === "ERROR"
        ? draft.runStatus
        : "COMPLETED";
    target.endStatus = terminalStatus;
    // M4：错误信息仅 ERROR 终态透传（「生成失败」徽标 tooltip）；其余终态恒 null
    target.errorMessage = draft.runStatus === "ERROR" ? draft.errorMessage : null;
  }

  // M4：无内容行（textParts/timeline/sources 全空）的终态 run 不渲染空回答
  // （首 chunk 前失败的 run 服务端仅落 USER 行——本就无 run 草稿；此处防御
  //  「仅 query_plan 等不回显行」导致的空占位）
  const emptyRunIndices: number[] = [];
  for (const [runId, index] of indices) {
    const draft = drafts.get(runId);
    if (!draft) continue;
    const hasContent =
      draft.textParts.join("").length > 0 || draft.timeline.length > 0 || draft.sources.length > 0;
    if (!hasContent) emptyRunIndices.push(index);
  }
  // 按下标降序 splice：先删高位占位，后续低位下标不发生位移，避免误删
  for (const index of emptyRunIndices.sort((a, b) => b - a)) {
    output.splice(index, 1);
  }
  return output;
}
