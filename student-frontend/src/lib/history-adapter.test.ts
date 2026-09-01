/**
 * 历史消息适配器测试（Task 13 TDD 先行用例；2026-08-28 时间线改版补时间轴重建）
 *
 * 覆盖设计文档 §六.6 R1 历史回显映射规则：
 * - USER 行 → 用户消息（正文 + 附件 chips；G8 历史态无缩略图，由 MessageList 降级图标）
 * - ASSISTANT 行按 runId 归并为一条 AI 消息：正文→text、thinking→时间轴思考节点
 *   （按 thinkingStage 归组、存量 null 降级 generating；2026-08-30 按 LLM 调用拆分：
 *   上一张同 stage 卡之后有工具行则另起新卡）、query_plan 行不再建节点（对齐设计稿）、
 *   TOOL_CALL/TOOL_RESULT→时间轴工具节点（run 内按 toolCallId 配对，空串按到达顺序兜底）、
 *   sources→来源卡与时间轴来源节点（取最后一组非空，原位替换）、
 *   intentType 透传、messageId=正文行 id（J5 反馈来源）
 * - 三场景（Task 9 验收）：完整 run 重建 / 取消 run 过滤后仅剩用户行 / 旧数据无 stage 降级
 * - 输入按 (createdAt, seq) 稳定排序；空列表 → []
 */
import { describe, expect, it } from "vitest";
import { historyAdapter } from "./history-adapter";
import type { RetrievalSource, StudentMessage } from "./types";

/** 历史消息行工厂（默认一条 ASSISTANT 正文行；thinkingStage 默认 null = 存量旧行） */
function makeRow(overrides: Partial<StudentMessage> & { role?: string } = {}): StudentMessage {
  return {
    id: "m-1",
    role: "ASSISTANT",
    content: "",
    messageType: null,
    thinkingStage: null,
    intentType: "knowledge_question",
    runId: "run-1",
    seq: 1,
    createdAt: "2026-08-24T10:00:00",
    sources: [],
    attachments: [],
    ...overrides,
  };
}

const SOURCE: RetrievalSource = {
  chunkId: "c-1",
  docTitle: "RAG 白皮书",
  headingPath: "第三章",
  score: 0.9,
};

describe("historyAdapter：基础映射", () => {
  it("空列表 → 空数组", () => {
    expect(historyAdapter([])).toEqual([]);
  });

  it("USER 行 → 用户消息：正文 + 附件透传（G8：url 为 objectKey，缩略图由 MessageList 降级为图标）", () => {
    const rows = [
      makeRow({
        id: "msg-user-1",
        role: "USER",
        content: "什么是 RAG？",
        runId: "run-1",
        seq: 1,
        attachments: [{ type: "image", url: "obj/1.png", name: "图.png", size: "1024" }],
      }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.role).toBe("user");
    expect(message.content).toBe("什么是 RAG？");
    expect(message.attachments).toEqual([
      { type: "image", url: "obj/1.png", name: "图.png", size: "1024" },
    ]);
    expect(message.text).toBe("");
    expect(message.endStatus).toBeNull();
  });

  it("ASSISTANT 正文行 → AI 消息：text/messageId=行 id/endStatus COMPLETED/intentType 透传 + sources（时间轴来源节点）", () => {
    const rows = [
      makeRow({
        id: "msg-a-1",
        messageType: null,
        content: "RAG 是检索增强生成。",
        intentType: "knowledge_question",
        sources: [SOURCE],
      }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.role).toBe("assistant");
    expect(message.id).toBe("run-1");
    expect(message.text).toBe("RAG 是检索增强生成。");
    expect(message.sources).toEqual([SOURCE]);
    expect(message.messageId).toBe("msg-a-1");
    expect(message.endStatus).toBe("COMPLETED");
    expect(message.intentType).toBe("knowledge_question");
    expect(message.timeline).toEqual([{ kind: "sources", sources: [SOURCE] }]);
  });

  it("intentType 为 null（存量消息）原样透传 null", () => {
    const [message] = historyAdapter([makeRow({ intentType: null })]);
    expect(message.intentType).toBeNull();
  });

  it("首行意图为空、后续行携带意图时补齐透传（存量行部分缺 intentType）", () => {
    const rows = [
      makeRow({ id: "t1", seq: 2, messageType: "thinking", content: "思考", intentType: null }),
      makeRow({ id: "a1", seq: 3, messageType: null, content: "正文", intentType: "chat" }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.intentType).toBe("chat");
  });

  it("thinking 行 → 时间轴思考节点（同 stage 同次调用合并行、恒 ended=true）", () => {
    const rows = [
      makeRow({
        id: "r1",
        seq: 2,
        messageType: "thinking",
        content: "第一步分析",
        thinkingStage: "understanding",
      }),
      makeRow({
        id: "r2",
        seq: 3,
        messageType: "thinking",
        content: "，第二步检索",
        thinkingStage: "understanding",
      }),
      makeRow({ id: "r3", seq: 4, messageType: null, content: "最终回答" }),
    ];
    const [message] = historyAdapter(rows);
    // 同 stage 同次调用多行合并一节点（持久化即完成态：ended=true）
    expect(message.timeline).toEqual([
      { kind: "thinking", stage: "understanding", lines: ["第一步分析，第二步检索"], ended: true },
    ]);
    expect(message.text).toBe("最终回答");
  });

  it("stage=retrieving 原样透传不降级（BUG-22：历史回显与实时流 STAGE_KEYS 单源化）", () => {
    const rows = [
      makeRow({
        id: "r1",
        seq: 2,
        messageType: "thinking",
        content: "检索知识库",
        thinkingStage: "retrieving",
      }),
      makeRow({ id: "r2", seq: 3, messageType: null, content: "回答" }),
    ];
    const [message] = historyAdapter(rows);
    // retrieving 属合法阶段集合（复用实时流 STAGE_KEYS）：不再被降级归并为 generating 卡
    expect(message.timeline).toEqual([
      { kind: "thinking", stage: "retrieving", lines: ["检索知识库"], ended: true },
    ]);
  });

  it("思考卡按 LLM 调用拆分（2026-08-30）：上一张同 stage 思考卡之后有工具行则另起新卡（主 agent 每次模型调用一块思考卡）", () => {
    const rows = [
      // 调用1 思考（generating）
      makeRow({
        seq: 2,
        messageType: "thinking",
        content: "第一轮组织",
        thinkingStage: "generating",
      }),
      // 工具调用/结果（调用边界）
      makeRow({
        seq: 3,
        messageType: "TOOL_CALL",
        content: JSON.stringify({ toolCallId: "t1", toolName: "searchKnowledge", input: {} }),
      }),
      makeRow({
        seq: 4,
        messageType: "TOOL_RESULT",
        content: JSON.stringify({ toolCallId: "t1", status: "success", output: {} }),
      }),
      // 调用2 思考（同 stage，工具边界后 → 另起新卡）
      makeRow({
        seq: 5,
        messageType: "thinking",
        content: "第二轮组织",
        thinkingStage: "generating",
      }),
      makeRow({ seq: 6, messageType: null, content: "回答" }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.timeline.map((node) => node.kind)).toEqual(["thinking", "tool", "thinking"]);
    expect(message.timeline[0]).toMatchObject({
      kind: "thinking",
      stage: "generating",
      lines: ["第一轮组织"],
    });
    expect(message.timeline[2]).toMatchObject({
      kind: "thinking",
      stage: "generating",
      lines: ["第二轮组织"],
    });
  });

  it("query_plan 行（2026-08-30 对齐设计稿）：不再建节点（重写正文/意图胶囊不回前端），正文正常回显", () => {
    const plan = {
      intent: "knowledge_question",
      rewritten: ["RAG 检索增强生成的概念"],
      filters: { courseNames: ["高等数学"] },
    };
    const rows = [
      makeRow({ id: "q1", seq: 2, messageType: "query_plan", content: JSON.stringify(plan) }),
      makeRow({ id: "q2", seq: 3, messageType: null, content: "回答" }),
    ];
    const [message] = historyAdapter(rows);
    // query_plan 行静默跳过（数据仍落库供审计），不建节点不崩溃
    expect(message.timeline).toEqual([]);
    expect(message.text).toBe("回答");
  });
});

describe("historyAdapter：工具卡配对", () => {
  it("TOOL_CALL/TOOL_RESULT 按 toolCallId 配对（success 态写回 output；时间轴工具节点同步原位更新）", () => {
    const rows = [
      makeRow({
        seq: 2,
        messageType: "TOOL_CALL",
        content: JSON.stringify({
          toolCallId: "t1",
          toolName: "searchKnowledge",
          input: { query: "RAG" },
        }),
      }),
      makeRow({
        seq: 3,
        messageType: "TOOL_RESULT",
        content: JSON.stringify({ toolCallId: "t1", status: "success", output: { hits: 3 } }),
      }),
    ];
    const [message] = historyAdapter(rows);
    // 时间轴工具节点：TOOL_CALL 建 pending 节点 → TOOL_RESULT 原位转 success
    expect(message.timeline).toEqual([
      {
        kind: "tool",
        toolCallId: "t1",
        toolName: "searchKnowledge",
        input: { query: "RAG" },
        status: "success",
        output: { hits: 3 },
      },
    ]);
  });

  it("空 toolCallId 按到达顺序兜底配对（等价索引兜底；时间轴同步）", () => {
    const rows = [
      makeRow({
        seq: 2,
        messageType: "TOOL_CALL",
        content: JSON.stringify({ toolCallId: "", toolName: "listCourses", input: {} }),
      }),
      makeRow({
        seq: 3,
        messageType: "TOOL_CALL",
        content: JSON.stringify({ toolCallId: "", toolName: "searchKnowledge", input: {} }),
      }),
      makeRow({
        seq: 4,
        messageType: "TOOL_RESULT",
        content: JSON.stringify({ toolCallId: "", status: "success", output: "A" }),
      }),
      makeRow({
        seq: 5,
        messageType: "TOOL_RESULT",
        content: JSON.stringify({ toolCallId: "", status: "success", output: "B" }),
      }),
    ];
    const [message] = historyAdapter(rows);
    // 时间轴工具节点：空 toolCallId 按到达顺序原位配对
    expect(message.timeline.map((node) => (node.kind === "tool" ? node.output : null))).toEqual([
      "A",
      "B",
    ]);
  });

  it("多 run 隔离：各 run 工具卡独立配对，不串 run", () => {
    const rows = [
      makeRow({ id: "u1", role: "USER", content: "问题一", runId: "run-1", seq: 1 }),
      makeRow({
        runId: "run-1",
        seq: 2,
        messageType: "TOOL_CALL",
        content: JSON.stringify({ toolCallId: "t1", toolName: "searchKnowledge", input: {} }),
      }),
      makeRow({ id: "u2", role: "USER", content: "问题二", runId: "run-2", seq: 5 }),
      makeRow({
        runId: "run-2",
        seq: 6,
        messageType: "TOOL_CALL",
        content: JSON.stringify({ toolCallId: "t1", toolName: "queryCourseDetail", input: {} }),
      }),
      // run-2 的结果只配对 run-2 自己的 pending（run-1 的 t1 仍是 pending）
      makeRow({
        runId: "run-2",
        seq: 7,
        messageType: "TOOL_RESULT",
        content: JSON.stringify({ toolCallId: "t1", status: "success", output: "课程详情" }),
      }),
    ];
    const [user1, assistant1, user2, assistant2] = historyAdapter(rows);
    expect(user1.role).toBe("user");
    expect(user2.role).toBe("user");
    expect(assistant1.id).toBe("run-1");
    expect(assistant1.timeline.map((node) => (node.kind === "tool" ? node.status : null))).toEqual([
      "pending",
    ]);
    expect(assistant2.id).toBe("run-2");
    expect(assistant2.timeline).toHaveLength(1);
    expect(assistant2.timeline[0]).toMatchObject({
      kind: "tool",
      toolName: "queryCourseDetail",
      status: "success",
      output: "课程详情",
    });
  });

  it("坏 JSON 的 TOOL_CALL/TOOL_RESULT 行防御兜底（不崩溃，按顺序配对）", () => {
    const rows = [
      makeRow({ seq: 2, messageType: "TOOL_CALL", content: "not-json" }),
      makeRow({ seq: 3, messageType: "TOOL_RESULT", content: "also-bad" }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.timeline).toHaveLength(1);
    expect(message.timeline[0]).toMatchObject({ kind: "tool", status: "success", toolCallId: "" });
  });

  it("无配对的 TOOL_RESULT 忽略（不产生错误工具卡，时间轴同样不建节点）", () => {
    const rows = [
      makeRow({
        seq: 2,
        messageType: "TOOL_RESULT",
        content: JSON.stringify({ toolCallId: "ghost", status: "success", output: {} }),
      }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.timeline).toEqual([]);
  });
});

describe("historyAdapter：顺序与归并", () => {
  it("输入按 (createdAt, seq) 稳定排序：乱序输入仍按时间序输出", () => {
    const rows = [
      makeRow({
        id: "u2",
        role: "USER",
        content: "晚的问题",
        runId: "run-2",
        seq: 1,
        createdAt: "2026-08-24T10:05:00",
      }),
      makeRow({
        id: "u1",
        role: "USER",
        content: "早的问题",
        runId: "run-1",
        seq: 1,
        createdAt: "2026-08-24T10:00:00",
      }),
    ];
    const messages = historyAdapter(rows);
    expect(messages.map((message) => message.content)).toEqual(["早的问题", "晚的问题"]);
  });

  it("用户消息与 run 归并消息交错时保持顺序（user → assistant → user → assistant）", () => {
    const rows = [
      makeRow({ id: "u1", role: "USER", content: "问题一", runId: "run-1", seq: 1 }),
      makeRow({ id: "a1", runId: "run-1", seq: 2, messageType: "thinking", content: "思考一" }),
      makeRow({ id: "a2", runId: "run-1", seq: 3, messageType: null, content: "回答一" }),
      makeRow({ id: "u2", role: "USER", content: "问题二", runId: "run-2", seq: 4 }),
      makeRow({ id: "a3", runId: "run-2", seq: 5, messageType: null, content: "回答二" }),
    ];
    const messages = historyAdapter(rows);
    expect(messages.map((message) => message.role)).toEqual([
      "user",
      "assistant",
      "user",
      "assistant",
    ]);
    expect(messages[1].text).toBe("回答一");
    expect(messages[1].timeline).toEqual([
      { kind: "thinking", stage: "generating", lines: ["思考一"], ended: true },
    ]);
    expect(messages[3].text).toBe("回答二");
  });

  it("正文多行按 seq 拼接为一条 AI 消息（messageId 取最后一条正文行）", () => {
    const rows = [
      makeRow({ id: "a1", seq: 2, messageType: null, content: "第一段" }),
      makeRow({ id: "a2", seq: 3, messageType: null, content: "第二段" }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.text).toBe("第一段第二段");
    expect(message.messageId).toBe("a2");
  });

  it("sources 取最后一组非空数组（空数组不覆盖既有来源；时间轴来源节点原位替换）", () => {
    const later: RetrievalSource = {
      chunkId: "c-2",
      docTitle: "新讲义",
      headingPath: "一",
      score: 0.8,
    };
    const rows = [
      makeRow({ seq: 2, messageType: null, content: "正文", sources: [SOURCE] }),
      makeRow({ seq: 3, messageType: null, content: "续写", sources: [later] }),
      makeRow({ seq: 4, messageType: null, content: "收尾", sources: [] }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.sources).toEqual([later]);
    // 时间轴来源节点：单节点原位替换（内容取最后一组非空，位置保持首现）
    expect(message.timeline).toEqual([{ kind: "sources", sources: [later] }]);
  });

  it("run 无正文行：仅思考/工具卡也还原（messageId=null，无反馈来源）", () => {
    const rows = [
      makeRow({ seq: 2, messageType: "thinking", content: "思考中", thinkingStage: "generating" }),
      makeRow({
        seq: 3,
        messageType: "TOOL_CALL",
        content: JSON.stringify({ toolCallId: "t1", toolName: "searchKnowledge", input: {} }),
      }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.text).toBe("");
    expect(message.messageId).toBeNull();
    expect(message.endStatus).toBe("COMPLETED");
    expect(message.timeline.map((node) => node.kind)).toEqual(["thinking", "tool"]);
  });
});

describe("historyAdapter：时间轴重建三场景（Task 9 验收）", () => {
  it("场景一 完整 run：thinking/tool/sources 行按 seq 重建与实时 SSE 同构的时间轴（query_plan 行不回显）", () => {
    // 模拟真机落库行序：thinking(u) → query_plan（不回显）→ TOOL_CALL → TOOL_RESULT →
    // thinking(g) → 正文(带 sources)
    const plan = {
      intent: "knowledge_question",
      rewritten: ["RAG检索增强生成技术的概念与原理"],
      filters: { courseNames: [] },
    };
    const rows = [
      makeRow({ id: "u1", role: "USER", content: "什么是 RAG？", runId: "run-9", seq: 1 }),
      makeRow({
        id: "t-u",
        runId: "run-9",
        seq: 2,
        messageType: "thinking",
        thinkingStage: "understanding",
        content: "用户想了解 RAG 概念\n需要检索课程资料",
      }),
      makeRow({
        id: "q-p",
        runId: "run-9",
        seq: 3,
        messageType: "query_plan",
        content: JSON.stringify(plan),
      }),
      makeRow({
        id: "t-c",
        runId: "run-9",
        seq: 4,
        messageType: "TOOL_CALL",
        content: JSON.stringify({
          toolCallId: "tc-1",
          toolName: "searchKnowledge",
          input: { query: "RAG" },
        }),
      }),
      makeRow({
        id: "t-r",
        runId: "run-9",
        seq: 5,
        messageType: "TOOL_RESULT",
        content: JSON.stringify({ toolCallId: "tc-1", status: "success", output: { hits: 2 } }),
      }),
      makeRow({
        id: "t-g",
        runId: "run-9",
        seq: 6,
        messageType: "thinking",
        thinkingStage: "generating",
        content: "组织回答结构",
      }),
      makeRow({
        id: "a-1",
        runId: "run-9",
        seq: 7,
        messageType: null,
        content: "RAG 是检索增强生成。",
        sources: [SOURCE],
      }),
    ];
    const [user, assistant] = historyAdapter(rows);
    expect(user.role).toBe("user");
    expect(assistant.timeline).toEqual([
      {
        kind: "thinking",
        stage: "understanding",
        lines: ["用户想了解 RAG 概念", "需要检索课程资料"],
        ended: true,
      },
      {
        kind: "tool",
        toolCallId: "tc-1",
        toolName: "searchKnowledge",
        input: { query: "RAG" },
        status: "success",
        output: { hits: 2 },
      },
      { kind: "thinking", stage: "generating", lines: ["组织回答结构"], ended: true },
      { kind: "sources", sources: [SOURCE] },
    ]);
  });

  it("场景二 取消 run 过滤后：服务端剔除取消 run 的 assistant 行，仅剩 USER 行独立成条（无悬挂 AI 消息）", () => {
    // 服务端契约（M3 半截过滤）：仅 COMPLETED run 的 assistant 行下发；取消 run 的
    // USER 行保留——前端视角 = 一条用户消息后无 AI 消息，直接衔接下一 run
    const rows = [
      makeRow({ id: "u1", role: "USER", content: "被取消的问题", runId: "run-cancel", seq: 1 }),
      makeRow({ id: "u2", role: "USER", content: "下一个问题", runId: "run-ok", seq: 2 }),
      makeRow({ id: "a1", runId: "run-ok", seq: 3, messageType: null, content: "正常回答" }),
    ];
    const messages = historyAdapter(rows);
    expect(messages.map((message) => message.role)).toEqual(["user", "user", "assistant"]);
    expect(messages[0]).toMatchObject({ content: "被取消的问题", timeline: [] });
    expect(messages[2]).toMatchObject({ id: "run-ok", text: "正常回答" });
  });

  it("场景三 旧数据无 stage 列：thinking 行 thinkingStage=null 全部降级归组 generating 节点", () => {
    const rows = [
      makeRow({
        id: "t1",
        seq: 2,
        messageType: "thinking",
        content: "旧思考一",
        thinkingStage: null,
      }),
      makeRow({
        id: "t2",
        seq: 3,
        messageType: "thinking",
        content: "旧思考二",
        thinkingStage: null,
      }),
      makeRow({ id: "a1", seq: 4, messageType: null, content: "旧回答" }),
    ];
    const [message] = historyAdapter(rows);
    // 降级契约：null → generating；存量逐 delta 行按增量续接（与旧 join("") 渲染等价）
    expect(message.timeline).toEqual([
      { kind: "thinking", stage: "generating", lines: ["旧思考一旧思考二"], ended: true },
    ]);
    expect(message.text).toBe("旧回答");
  });

  it("thinking 行内容全为空白：不建空节点（防御）", () => {
    const rows = [
      makeRow({
        id: "t1",
        seq: 2,
        messageType: "thinking",
        content: "  \n  ",
        thinkingStage: "generating",
      }),
      makeRow({ id: "a1", seq: 3, messageType: null, content: "回答" }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.timeline).toEqual([]);
    expect(message.text).toBe("回答");
  });

  it("query_plan 行二现（2026-08-30 对齐设计稿）：不建节点不回显，正文正常拼接", () => {
    const rows = [
      makeRow({
        id: "q1",
        seq: 2,
        messageType: "query_plan",
        content: JSON.stringify({
          intent: "chat",
          rewritten: ["旧改写"],
          filters: { courseNames: [] },
        }),
      }),
      makeRow({
        id: "q2",
        seq: 3,
        messageType: "query_plan",
        content: JSON.stringify({
          intent: "knowledge_question",
          rewritten: ["新改写"],
          filters: { courseNames: [] },
        }),
      }),
      makeRow({ id: "a1", seq: 4, messageType: null, content: "回答" }),
    ];
    const [message] = historyAdapter(rows);
    // query_plan 行整体跳过（重写正文/意图胶囊不回前端），时间轴仅空；正文不受污染
    expect(message.timeline).toEqual([]);
    expect(message.text).toBe("回答");
  });

  it("不同 stage 的 thinking 行各自归组（understanding 与 generating 不合并）", () => {
    const rows = [
      makeRow({
        id: "t1",
        seq: 2,
        messageType: "thinking",
        content: "理解",
        thinkingStage: "understanding",
      }),
      makeRow({
        id: "t2",
        seq: 3,
        messageType: "thinking",
        content: "生成",
        thinkingStage: "generating",
      }),
      makeRow({
        id: "t3",
        seq: 4,
        messageType: "thinking",
        content: "继续生成",
        thinkingStage: "generating",
      }),
    ];
    const [message] = historyAdapter(rows);
    // 同 stage 行按增量续接为同一行（存量行 = 逐 delta 落库），不同 stage 各建节点
    expect(message.timeline).toEqual([
      { kind: "thinking", stage: "understanding", lines: ["理解"], ended: true },
      { kind: "thinking", stage: "generating", lines: ["生成继续生成"], ended: true },
    ]);
  });
});
