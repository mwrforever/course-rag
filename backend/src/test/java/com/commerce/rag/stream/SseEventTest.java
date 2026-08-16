package com.commerce.rag.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SseEvent 单元测试 —— 紧凑构造器参数校验与访问器完整覆盖
 *
 * <p>覆盖合法参数构造成功与四条非法参数（type 为 null / seqId 为负 / payload 为 null /
 * timestamp 非正）拒绝构造的分支，保证事件载体入参契约稳定。
 *
 * @author commerce-rag
 */
@DisplayName("SseEvent 事件载体测试")
class SseEventTest {

    @Test
    @DisplayName("合法参数构造成功，四个访问器返回原值")
    void validArgs_buildsEventAndAccessorsReturnValues() {
        long timestamp = System.currentTimeMillis();
        SseEvent event = new SseEvent(SseEventType.DELTA, 1L, "{}", timestamp);

        assertEquals(SseEventType.DELTA, event.type());
        assertEquals(1L, event.seqId());
        assertEquals("{}", event.payload());
        assertEquals(timestamp, event.timestamp());
    }

    @Test
    @DisplayName("type 为 null 拒绝构造")
    void nullType_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new SseEvent(null, 1L, "{}", System.currentTimeMillis()));
    }

    @Test
    @DisplayName("seqId 为负拒绝构造")
    void negativeSeqId_rejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SseEvent(SseEventType.DELTA, -1L, "{}", System.currentTimeMillis()));
    }

    @Test
    @DisplayName("payload 为 null 拒绝构造")
    void nullPayload_rejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SseEvent(SseEventType.DELTA, 1L, null, System.currentTimeMillis()));
    }

    @Test
    @DisplayName("timestamp 非正拒绝构造")
    void nonPositiveTimestamp_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new SseEvent(SseEventType.DELTA, 1L, "{}", 0L));
    }
}
