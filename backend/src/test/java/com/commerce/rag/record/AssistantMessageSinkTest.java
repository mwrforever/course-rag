package com.commerce.rag.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.record.AssistantMessageCapture.AssistantToolCall;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AssistantMessageSink 单元测试 —— per-run LLM 调用捕获容器的全分支直测（2026-08-29 消息实体化）。
 *
 * <p>覆盖：捕获顺序、思考增量截取（多图 caption 共享累加缓冲的场景）、主 agent 流式
 * reasoning 累积与 FINISHED 回退读取、空调用跳过、快照深拷贝隔离。
 *
 * <p>断言口径：面向业务结果（捕获列表内容与增量语义），不绑定 synchronized/内部 Map 等实现细节。
 */
@DisplayName("AssistantMessageSink LLM 调用捕获容器测试")
class AssistantMessageSinkTest {

    @Test
    @DisplayName("capture 按调用结束顺序入列，快照返回深拷贝（后续捕获不影响已取快照）")
    void capture_keepsCallOrderAndSnapshotIsCopy() {
        AssistantMessageSink sink = new AssistantMessageSink();
        sink.capture("understanding", "QU 思考", "{\"intent\":\"chat\"}", List.of());
        sink.capture("generating", "主 agent 思考", "回答正文", List.of());

        List<AssistantMessageCapture> first = sink.snapshot();
        sink.capture("attachments", "图 1 思考", "图片描述", List.of());

        assertEquals(2, first.size(), "快照必须为捕获时点的深拷贝（后续捕获不得污染）");
        assertEquals("understanding", first.get(0).stage());
        assertEquals("generating", first.get(1).stage());
        assertEquals(3, sink.snapshot().size(), "新快照应含新增捕获");
    }

    @Test
    @DisplayName("思考增量截取 — 同 stage 多次捕获仅保留本次增量（多图 caption 拆行不重复）")
    void capture_deltaReasoningPerCall() {
        AssistantMessageSink sink = new AssistantMessageSink();
        // 模拟两图 caption：思考经 ThinkingPusher 共享累加缓冲，全文快照逐次增长
        sink.capture("attachments", "分析图 1 内容。", "图 1 描述", List.of());
        sink.capture("attachments", "分析图 1 内容。分析图 2 结构。", "图 2 描述", List.of());

        List<AssistantMessageCapture> captures = sink.snapshot();
        assertEquals("分析图 1 内容。", captures.get(0).reasoning(), "首图实体思考 = 首图增量");
        assertEquals("分析图 2 结构。", captures.get(1).reasoning(), "次图实体思考 = 全文增量差（不重复首图）");
        assertEquals("图 1 描述", captures.get(0).text());
        assertEquals("图 2 描述", captures.get(1).text());
    }

    @Test
    @DisplayName("思考增量截取 — reasoning 与上次捕获相同/倒退时增量为 null（防御：无新思考的重复捕获）")
    void capture_reasoningNotGrowing_deltaNull() {
        AssistantMessageSink sink = new AssistantMessageSink();
        sink.capture("attachments", "思考全文", "描述", List.of());
        // 全文未增长（异常路径重复捕获）——不重复落思考
        sink.capture("attachments", "思考全文", "描述 2", List.of());

        List<AssistantMessageCapture> captures = sink.snapshot();
        assertEquals("思考全文", captures.get(0).reasoning());
        assertNull(captures.get(1).reasoning(), "全文未增长时本次思考增量应为 null");
        assertEquals("描述 2", captures.get(1).text(), "text 不受增量截取影响");
    }

    @Test
    @DisplayName("空调用（无思考/正文/工具调用）不捕获，快照保持为空")
    void capture_emptyCall_skipped() {
        AssistantMessageSink sink = new AssistantMessageSink();

        sink.capture("generating", null, null, List.of());
        sink.capture("generating", "   ", null, List.of());
        sink.capture("generating", null, "", List.of());

        assertTrue(sink.snapshot().isEmpty(), "空调用不得产生捕获");
    }

    @Test
    @DisplayName("主 agent 流式 reasoning 累积 — appendReasoning 逐片段追加，FINISHED 可回退读取全文")
    void appendReasoning_accumulatesPerStage() {
        AssistantMessageSink sink = new AssistantMessageSink();
        sink.appendReasoning("generating", "先想第一步，");
        sink.appendReasoning("generating", "再想第二步");

        assertEquals("先想第一步，再想第二步", sink.accumulatedReasoning("generating"), "流式片段应按序累积为全文");
        assertNull(sink.accumulatedReasoning("understanding"), "未累积阶段返回 null");
        sink.appendReasoning("generating", null);
        assertEquals("先想第一步，再想第二步", sink.accumulatedReasoning("generating"), "null 片段忽略");
    }

    @Test
    @DisplayName("主 agent 捕获 — FINISHED 传全文快照时增量截取与流式累积同源一致")
    void capture_generating_reasoningDeltaFromAccumulated() {
        AssistantMessageSink sink = new AssistantMessageSink();
        // 流式阶段累积（与前端已推送 THINKING 一致）
        sink.appendReasoning("generating", "思考片段一。");
        sink.appendReasoning("generating", "思考片段二。");

        // FINISHED 捕获点：reasoning 回退取累积全文，toolCalls 随调用写入
        sink.capture(
                "generating",
                sink.accumulatedReasoning("generating"),
                "最终正文",
                List.of(new AssistantToolCall("call-1", "searchKnowledge", "{\"query\":\"课程\"}")));

        List<AssistantMessageCapture> captures = sink.snapshot();
        assertEquals(1, captures.size());
        assertEquals("思考片段一。思考片段二。", captures.get(0).reasoning(), "实体思考 = 该次调用流式思考全文");
        assertEquals("最终正文", captures.get(0).text());
        assertEquals(1, captures.get(0).toolCalls().size());
        assertEquals("call-1", captures.get(0).toolCalls().get(0).id());
    }

    @Test
    @DisplayName("多模型调用（ReAct 循环）— 每次 FINISHED 捕获的思考增量互不重叠")
    void capture_multiModelCall_reasoningDeltaPerCall() {
        AssistantMessageSink sink = new AssistantMessageSink();
        // 第一轮调用：思考 + 工具调用（无正文）
        sink.appendReasoning("generating", "需要搜索资料。");
        sink.capture(
                "generating",
                sink.accumulatedReasoning("generating"),
                null,
                List.of(new AssistantToolCall("call-1", "searchKnowledge", "{}")));
        // 第二轮调用：思考 + 正文
        sink.appendReasoning("generating", "基于结果作答。");
        sink.capture("generating", sink.accumulatedReasoning("generating"), "最终回答", List.of());

        List<AssistantMessageCapture> captures = sink.snapshot();
        assertEquals("需要搜索资料。", captures.get(0).reasoning(), "首轮思考增量 = 首轮流式思考");
        assertEquals("基于结果作答。", captures.get(1).reasoning(), "次轮思考增量 = 次轮流式思考（不重叠）");
        assertNull(captures.get(0).text(), "纯工具调用轮无正文");
    }
}
