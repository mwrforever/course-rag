package com.commerce.rag.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.commerce.rag.stream.SseEvent;
import com.commerce.rag.stream.SseEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DeltaAccumulator 单元测试 —— 取消/错误路径落库事实源的全分支直测（2026-08-28 批出口门禁补齐）。
 *
 * <p>背景：此前该类仅经 ChatRequestWorkerTest 主路径间接覆盖（DELTA/THINKING 正常累积），
 * payload 解析失败降级、字段缺失跳过、objectMapper=null 恒空、stage 空串归组、
 * 日志截断等异常/边界分支无直接用例，JaCoCo 单类行覆盖 0.66 低于 0.80 门禁。
 *
 * <p>断言口径：面向业务结果（累积内容与快照值），不绑定 StringBuilder/锁等实现细节。
 * 已知不可达分支：truncateForLog 的 payload==null 防御分支——SseEvent 构造器强制
 * payload 非空且该方法仅经 catch 路径调用，逻辑上不可触发，如实记录不做强行覆盖。
 */
@DisplayName("DeltaAccumulator 流式 delta 累加器（取消/错误路径落库事实源）")
class DeltaAccumulatorTest {

    /** 事件 payload 构造/序列化用解析器（与生产注入同款 Jackson ObjectMapper） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("正文与思考片段按推送顺序累积，快照可分别读取")
    void accumulates_textAndThinkingInPushOrder() throws Exception {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);

        acc.accumulate(deltaEvent("你"));
        acc.accumulate(thinkingEvent("先想一步，", "generating"));
        acc.accumulate(deltaEvent("好，世界"));

        assertEquals("你好，世界", acc.text(), "正文快照必须是 DELTA 片段按推送顺序的拼接");
        assertEquals("先想一步，", acc.thinking("generating"), "思考快照必须按 stage 归组读取");
    }

    @Test
    @DisplayName("null 事件与 null 解析器均直接忽略，累加器恒空且不抛异常")
    void nullEventOrNullMapper_accumulatorStaysEmpty() throws Exception {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);
        acc.accumulate(null);

        DeltaAccumulator accWithoutMapper = new DeltaAccumulator(null);
        accWithoutMapper.accumulate(deltaEvent("片段"));
        accWithoutMapper.accumulate(thinkingEvent("片段", "generating"));

        assertEquals("", acc.text(), "null 事件不得进入累加缓冲");
        assertEquals("", accWithoutMapper.text(), "objectMapper 为 null 时正文恒为空（落库回退 state 汇总）");
        assertNull(accWithoutMapper.thinking("generating"), "objectMapper 为 null 时思考恒为空");
    }

    @Test
    @DisplayName("非正文/思考类事件（STAGE/SOURCES/END）不参与落库累积")
    void nonDeltaNonThinkingEvents_ignored() {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);

        acc.accumulate(event(SseEventType.STAGE, "{}"));
        acc.accumulate(event(SseEventType.SOURCES, "{}"));
        acc.accumulate(event(SseEventType.END, "{}"));

        assertEquals("", acc.text(), "进度/来源/终态事件不得混入正文落库语义");
        assertNull(acc.thinking("generating"), "非 THINKING 事件不得产生思考内容");
    }

    @Test
    @DisplayName("DELTA 缺失 text 字段或 text 为空串的片段跳过")
    void deltaEvent_missingOrEmptyText_skipped() throws Exception {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);

        acc.accumulate(event(SseEventType.DELTA, "{}"));
        acc.accumulate(event(SseEventType.DELTA, "{\"text\":\"\"}"));
        acc.accumulate(deltaEvent("有效片段"));

        assertEquals("有效片段", acc.text(), "无效片段必须跳过，不得在正文中产生空拼接");
    }

    @Test
    @DisplayName("THINKING 缺失 delta 字段或 delta 为空串的片段跳过")
    void thinkingEvent_missingOrEmptyDelta_skipped() throws Exception {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);

        acc.accumulate(event(SseEventType.THINKING, "{}"));
        acc.accumulate(event(SseEventType.THINKING, "{\"delta\":\"\"}"));
        acc.accumulate(thinkingEvent("有效思考", "generating"));

        assertEquals("有效思考", acc.thinking("generating"), "无效思考片段必须跳过");
    }

    @Test
    @DisplayName("THINKING stage 缺失按空串归组，与 thinking(null) 取值口径一致")
    void thinkingEvent_missingStage_groupedAsEmptyKey() {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);

        acc.accumulate(event(SseEventType.THINKING, "{\"delta\":\"无阶段思考\"}"));

        assertEquals("无阶段思考", acc.thinking(""), "stage 缺失必须归组到空串键（与 ThinkingPusher 口径一致）");
        assertEquals("无阶段思考", acc.thinking(null), "thinking(null) 必须等价于空串键取值");
        assertNull(acc.thinking("generating"), "空串归组不得与显式 stage 键混淆");
    }

    @Test
    @DisplayName("payload 非法 JSON 时降级跳过该片段，不中断后续正常累积")
    void invalidPayload_skippedAndFollowingFragmentsStillAccumulated() throws Exception {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);

        acc.accumulate(event(SseEventType.DELTA, "{broken"));
        acc.accumulate(deltaEvent("正常片段"));

        assertEquals("正常片段", acc.text(), "单片段解析失败只跳过自身，不得中断流或吞掉后续片段");
    }

    @Test
    @DisplayName("超长非法 payload 仅截断摘要记 warn，片段跳过且不抛异常")
    void longInvalidPayload_truncatedForLogAndSkipped() {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);

        acc.accumulate(event(SseEventType.THINKING, "x".repeat(80)));

        assertEquals("", acc.text(), "超长非法片段必须整体跳过");
        assertNull(acc.thinking("generating"), "非法思考片段不得进入任何 stage 分组");
    }

    @Test
    @DisplayName("快照为取值时点的深拷贝，后续累积不影响已取快照")
    void snapshot_isolatedFromSubsequentAccumulation() throws Exception {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);

        acc.accumulate(deltaEvent("第一段"));
        String snapshot = acc.text();
        acc.accumulate(deltaEvent("第二段"));

        assertEquals("第一段", snapshot, "已取快照必须是取值时点的值，不受后续累积影响");
        assertEquals("第一段第二段", acc.text(), "再次取快照反映最新累积全文");
    }

    @Test
    @DisplayName("从未推送时正文为空串、未推送阶段思考为 null")
    void neverAccumulated_emptyTextAndNullThinking() {
        DeltaAccumulator acc = new DeltaAccumulator(objectMapper);

        assertEquals("", acc.text(), "从未推送 DELTA 时正文快照为空串（never null）");
        assertNull(acc.thinking("generating"), "从未推送的 stage 思考返回 null（调用方按空处理）");
    }

    /** 构造指定类型与原始 payload 的事件（seq/timestamp 与累加器消费无关，占位合法值） */
    private SseEvent event(SseEventType type, String payload) {
        return new SseEvent(type, 1, payload, System.currentTimeMillis());
    }

    /** 构造 DELTA 事件（payload {text}） */
    private SseEvent deltaEvent(String text) throws Exception {
        return event(SseEventType.DELTA, objectMapper.writeValueAsString(Map.of("text", text)));
    }

    /** 构造 THINKING 事件（payload {delta, stage}） */
    private SseEvent thinkingEvent(String delta, String stage) throws Exception {
        return event(SseEventType.THINKING, objectMapper.writeValueAsString(Map.of("delta", delta, "stage", stage)));
    }
}
