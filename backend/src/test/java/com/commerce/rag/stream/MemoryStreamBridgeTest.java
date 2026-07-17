package com.commerce.rag.stream;

import com.commerce.rag.config.StreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MemoryStreamBridge 单元测试 —— 验证 ring buffer 的 push/replay/subscribe/cleanup 逻辑
 *
 * <p>注意：SseEvent 的 seqId 必须与 ring buffer 的 head 位置对齐（seqId 从 0 开始），
 * 因为 replay 逻辑用 {@code seq % capacity} 定位 slot，再校验 {@code event.seqId() == seq}。
 *
 * @author commerce-rag
 */
@DisplayName("MemoryStreamBridge ring buffer 测试")
class MemoryStreamBridgeTest {

    private MemoryStreamBridge bridge;
    private static final int BUFFER_SIZE = 256;

    @BeforeEach
    void setUp() {
        StreamProperties props = new StreamProperties(
                "chat:request", "chat-workers", 10, 2000, 300, 15, BUFFER_SIZE
        );
        bridge = new MemoryStreamBridge(props);
    }

    // ==================== 辅助方法 ====================

    /** 创建 SseEvent，seqId 从 0 开始递增 */
    private SseEvent event(long seqId) {
        return new SseEvent(SseEventType.DELTA, seqId, "{\"text\":\"msg" + seqId + "\"}", System.currentTimeMillis());
    }

    // ==================== push 测试 ====================

    @Test
    @DisplayName("push 无订阅者 — 不抛异常，事件存入 buffer")
    void push_noSubscribers_noException() {
        bridge.createRing("run1");

        // 不抛异常即可
        assertDoesNotThrow(() -> bridge.push("run1", event(0)));
    }

    @Test
    @DisplayName("push 有订阅者 — emitter.send 被调用 1 次")
    void push_withSubscriber_emitterSendCalled() throws Exception {
        // Given
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.subscribe("run1", mockEmitter);

        // When
        bridge.push("run1", event(0));

        // Then
        verify(mockEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("push 到不存在的 ring — 不抛异常（静默忽略）")
    void push_ringNotExist_noException() {
        assertDoesNotThrow(() -> bridge.push("nonexistent", event(0)));
    }

    // ==================== replay 测试 ====================

    @Test
    @DisplayName("replay 成功 — push 3 个事件后 replay(lastEventId=0) 推送 seqId=1,2")
    void replay_success_pushesEventsAfterLastEventId() throws Exception {
        // Given
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.push("run1", event(0));
        bridge.push("run1", event(1));
        bridge.push("run1", event(2));

        // When
        boolean result = bridge.replay("run1", 0, mockEmitter);

        // Then: replay 从 seqId=1 开始推送（lastEventId=0 之后），seqId=1 和 2
        assertTrue(result);
        verify(mockEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("replay 失败 — lastEventId 太旧（ring buffer 已覆盖）")
    void replay_tooOld_returnsFalse() throws Exception {
        // Given: push 257 个事件填满并覆盖 buffer
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        for (int i = 0; i < BUFFER_SIZE + 1; i++) {
            bridge.push("run1", event(i));
        }

        // When: lastEventId=0 已被覆盖（oldestSeq=1）
        boolean result = bridge.replay("run1", 0, mockEmitter);

        // Then
        assertFalse(result);
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("replay 边界 — lastEventId 恰好等于最旧的 seqId")
    void replay_boundary_lastEventIdEqualsOldest() throws Exception {
        // Given: push 256 个事件，head=256, oldestSeq=0
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        for (int i = 0; i < BUFFER_SIZE; i++) {
            bridge.push("run1", event(i));
        }

        // When: lastEventId=0 恰好等于 oldestSeq=0
        boolean result = bridge.replay("run1", 0, mockEmitter);

        // Then: 返回 true，推送 seqId=1..255（255 个事件）
        assertTrue(result);
        verify(mockEmitter, times(BUFFER_SIZE - 1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("replay lastEventId 超出当前 head — 返回 true，不推送任何事件")
    void replay_lastEventIdBeyondHead_returnsTrueNoPush() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.push("run1", event(0));

        // lastEventId=100 超出 head=1
        boolean result = bridge.replay("run1", 100, mockEmitter);

        assertTrue(result);
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("replay 不存在的 ring — 返回 false")
    void replay_ringNotExist_returnsFalse() {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        boolean result = bridge.replay("nonexistent", 0, mockEmitter);
        assertFalse(result);
    }

    // ==================== subscribe + 失败 emitter 测试 ====================

    @Test
    @DisplayName("subscribe 失败的 emitter — send 抛 IOException 后自动移除")
    void subscribe_failedEmitter_removedFromSubscribers() throws Exception {
        // Given
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.subscribe("run1", mockEmitter);

        // stub send 抛 IOException
        doThrow(new IOException("broken pipe"))
                .when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));

        // When: push 1 个事件 → send 失败 → emitter 被移除
        bridge.push("run1", event(0));

        // Then: push 第二个事件 → emitter 已移除，send 不再被调用
        bridge.push("run1", event(1));

        // send 只被调用 1 次（第一次 push），第二次 push 不会调用
        verify(mockEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("subscribe 到不存在的 ring — 不抛异常")
    void subscribe_ringNotExist_noException() {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        assertDoesNotThrow(() -> bridge.subscribe("nonexistent", mockEmitter));
    }

    // ==================== removeRing 测试 ====================

    @Test
    @DisplayName("removeRing — emitter.complete() 被调用")
    void removeRing_emitterCompleted() {
        // Given
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.subscribe("run1", mockEmitter);

        // When
        bridge.removeRing("run1");

        // Then
        verify(mockEmitter, times(1)).complete();
    }

    @Test
    @DisplayName("removeRing 后 push 不再推送事件")
    void removeRing_thenPush_noSend() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.subscribe("run1", mockEmitter);
        bridge.removeRing("run1");

        // push 后不应调用 send（ring 已移除）
        bridge.push("run1", event(0));
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("removeRing 不存在的 runId — 不抛异常")
    void removeRing_notExist_noException() {
        assertDoesNotThrow(() -> bridge.removeRing("nonexistent"));
    }

    // ==================== 多订阅者测试 ====================

    @Test
    @DisplayName("多订阅者 — push 事件时所有订阅者都收到")
    void push_multipleSubscribers_allReceive() throws Exception {
        SseEmitter mockEmitter1 = mock(SseEmitter.class);
        SseEmitter mockEmitter2 = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.subscribe("run1", mockEmitter1);
        bridge.subscribe("run1", mockEmitter2);

        bridge.push("run1", event(0));

        verify(mockEmitter1, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(mockEmitter2, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("createRing 幂等 — 同一 runId 多次 createRing 不报错")
    void createRing_idempotent() {
        bridge.createRing("run1");
        assertDoesNotThrow(() -> bridge.createRing("run1"));
    }
}
