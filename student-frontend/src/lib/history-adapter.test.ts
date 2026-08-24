/**
 * 历史消息适配器测试（Task 13 TDD 先行用例）
 *
 * 覆盖设计文档 §六.6 R1 历史回显映射规则：
 * - USER 行 → 用户消息（正文 + 附件 chips；G8 历史态无缩略图，由 MessageList 降级图标）
 * - ASSISTANT 行按 runId 归并为一条 AI 消息：正文→text、thinking→思考卡（thinkingEnded），
 *   TOOL_CALL/TOOL_RESULT→工具卡（run 内按 toolCallId 配对，空串按到达顺序兜底）、
 *   sources→来源卡（取最后一组非空）、intentType 透传、messageId=正文行 id（J5 反馈来源）
 * - 输入按 (createdAt, seq) 稳定排序；空列表 → []
 */
import { describe, expect, it } from "vitest";
import { historyAdapter } from "./history-adapter";
import type { RetrievalSource, StudentMessage } from "./types";

/** 历史消息行工厂（默认一条 ASSISTANT 正文行） */
function makeRow(overrides: Partial<StudentMessage> & { role?: string } = {}): StudentMessage {
  return {
    id: "m-1",
    role: "ASSISTANT",
    content: "",
    messageType: null,
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

  it("ASSISTANT 正文行 → AI 消息：text/messageId=行 id/endStatus COMPLETED/intentType 透传 + sources", () => {
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
    expect(message.thinking).toBe("");
    expect(message.tools).toEqual([]);
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

  it("thinking 行 → 思考文本累积（多行按序拼接）+ thinkingEnded=true（折叠思考卡）", () => {
    const rows = [
      makeRow({ id: "r1", seq: 2, messageType: "thinking", content: "第一步分析" }),
      makeRow({ id: "r2", seq: 3, messageType: "thinking", content: "，第二步检索" }),
      makeRow({ id: "r3", seq: 4, messageType: null, content: "最终回答" }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.thinking).toBe("第一步分析，第二步检索");
    expect(message.thinkingEnded).toBe(true);
    expect(message.text).toBe("最终回答");
  });
});

describe("historyAdapter：工具卡配对", () => {
  it("TOOL_CALL/TOOL_RESULT 按 toolCallId 配对（success 态写回 output）", () => {
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
    expect(message.tools).toHaveLength(1);
    expect(message.tools[0]).toEqual({
      toolCallId: "t1",
      toolName: "searchKnowledge",
      input: { query: "RAG" },
      status: "success",
      output: { hits: 3 },
    });
  });

  it("空 toolCallId 按到达顺序兜底配对（等价索引兜底）", () => {
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
    expect(message.tools.map((tool) => tool.output)).toEqual(["A", "B"]);
    expect(message.tools.map((tool) => tool.status)).toEqual(["success", "success"]);
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
    expect(assistant1.tools.map((tool) => tool.status)).toEqual(["pending"]);
    expect(assistant2.id).toBe("run-2");
    expect(assistant2.tools).toHaveLength(1);
    expect(assistant2.tools[0]).toMatchObject({
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
    expect(message.tools).toHaveLength(1);
    expect(message.tools[0].status).toBe("success");
    expect(message.tools[0].toolCallId).toBe("");
  });

  it("无配对的 TOOL_RESULT 忽略（不产生错误工具卡）", () => {
    const rows = [
      makeRow({
        seq: 2,
        messageType: "TOOL_RESULT",
        content: JSON.stringify({ toolCallId: "ghost", status: "success", output: {} }),
      }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.tools).toEqual([]);
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
    expect(messages[1].thinking).toBe("思考一");
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

  it("sources 取最后一组非空数组（空数组不覆盖既有来源）", () => {
    const rows = [
      makeRow({ seq: 2, messageType: null, content: "正文", sources: [SOURCE] }),
      makeRow({ seq: 3, messageType: null, content: "续写", sources: [] }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.sources).toEqual([SOURCE]);
  });

  it("run 无正文行：仅思考/工具卡也还原（messageId=null，无反馈来源）", () => {
    const rows = [
      makeRow({ seq: 2, messageType: "thinking", content: "思考中" }),
      makeRow({
        seq: 3,
        messageType: "TOOL_CALL",
        content: JSON.stringify({ toolCallId: "t1", toolName: "searchKnowledge", input: {} }),
      }),
    ];
    const [message] = historyAdapter(rows);
    expect(message.text).toBe("");
    expect(message.thinking).toBe("思考中");
    expect(message.tools).toHaveLength(1);
    expect(message.messageId).toBeNull();
    expect(message.endStatus).toBe("COMPLETED");
  });
});
