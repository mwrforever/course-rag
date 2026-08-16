package com.commerce.rag.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commerce.rag.properties.StreamProperties;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
        StreamProperties props = new StreamProperties("chat:request", "chat-workers", 10, 2000, 300, 15, BUFFER_SIZE);
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

    // ==================== subscribe + 失败 emitter 测试 ====================

    @Test
    @DisplayName("subscribe 失败的 emitter — send 抛 IOException 后自动移除")
    void subscribe_failedEmitter_removedFromSubscribers() throws Exception {
        // Given
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.subscribe("run1", mockEmitter);

        // stub send 抛 IOException
        doThrow(new IOException("broken pipe")).when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));

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

    // ==================== replayAndSubscribe 测试 ====================

    @Test
    @DisplayName("replayAndSubscribe — 回放 (lastEventId, head] 区间并注册订阅者，新事件实时到达")
    void replayAndSubscribe_replaysAndRegisters() throws Exception {
        // Given
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.push("run1", event(0));
        bridge.push("run1", event(1));
        bridge.push("run1", event(2));

        // When: lastEventId=0 → 回放 seqId=1,2
        boolean result = bridge.replayAndSubscribe("run1", 0, mockEmitter);

        // Then: 回放 2 个事件
        assertTrue(result);
        verify(mockEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        // 注册后新事件实时推送（第 3 次 send）
        bridge.push("run1", event(3));
        verify(mockEmitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("replayAndSubscribe 边界 — lastEventId 超出 head：回放空事件但注册成功")
    void replayAndSubscribe_lastEventIdBeyondHead_registersOnly() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.push("run1", event(0));

        boolean result = bridge.replayAndSubscribe("run1", 100, mockEmitter);

        assertTrue(result);
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        // 注册生效：新事件实时到达
        bridge.push("run1", event(1));
        verify(mockEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("replayAndSubscribe 覆盖 — lastEventId 太旧返回 false 且不注册")
    void replayAndSubscribe_tooOld_returnsFalseAndNotRegister() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        for (int i = 0; i < BUFFER_SIZE + 1; i++) {
            bridge.push("run1", event(i));
        }

        boolean result = bridge.replayAndSubscribe("run1", 0, mockEmitter);

        assertFalse(result);
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        // 未注册：后续 push 不送达
        bridge.push("run1", event(BUFFER_SIZE + 1));
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("replayAndSubscribe 不存在的 ring — 返回 false")
    void replayAndSubscribe_ringNotExist_returnsFalse() {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        boolean result = bridge.replayAndSubscribe("nonexistent", 0, mockEmitter);
        assertFalse(result);
    }

    @Test
    @DisplayName("replayAndSubscribe 并发 — 与 push 并发执行，emitter 收到事件总数不丢不重")
    void replayAndSubscribe_concurrentWithPush_noLossNoDuplicate() throws Exception {
        // Given
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        int total = 200;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> pushError = new AtomicReference<>();
        Thread pusher = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < total; i++) {
                    bridge.push("run1", event(i));
                }
            } catch (Throwable t) {
                pushError.set(t);
            }
        });
        pusher.start();
        start.countDown();

        // When: 与 push 并发重连（lastEventId=0 回放全部已推送事件）
        boolean result = bridge.replayAndSubscribe("run1", 0, mockEmitter);
        pusher.join(5000);

        // Then: 回放区间 (0, head@锁内] 与注册后实时区间互斥覆盖全部事件 → 总数恰为 total
        assertTrue(result);
        assertNull(pushError.get());
        verify(mockEmitter, times(total)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ==================== P1-4 / P1-1 修复测试 ====================

    @Test
    @DisplayName("P1-4 subscribe 已关闭的 ring → 返回 false（调用方补发终态，防永久生成中）")
    void subscribe_closedRing_returnsFalse() {
        bridge.createRing("run1");
        bridge.removeRing("run1"); // close 清空订阅者

        SseEmitter emitter = mock(SseEmitter.class);
        assertFalse(bridge.subscribe("run1", emitter), "已关闭 ring 的 subscribe 应返回 false");
    }

    @Test
    @DisplayName("P1-1 回放事件在 replayAndSubscribe 返回前已全部发送（锁内发送，实时事件不可能插队）")
    void replayAndSubscribe_replayEventsDeliveredBeforeLiveEvents() throws Exception {
        bridge.createRing("run1");
        SseEmitter first = mock(SseEmitter.class);
        bridge.subscribe("run1", first);
        // 先推入 3 个历史事件（seqId 0-2 与 ring slot 对齐，客户端已收到 seq 0）
        for (long seq = 0; seq <= 2; seq++) {
            bridge.push("run1", event(seq));
        }
        // 重连 emitter：回放 seq 1-2
        SseEmitter reconnected = mock(SseEmitter.class);
        assertTrue(bridge.replayAndSubscribe("run1", 0L, reconnected));

        // 阶段 1：replayAndSubscribe 返回时回放事件（2 个）必须已全部发送——
        // 若回放 send 在锁外延迟执行，此处 verify 即失败（回放先于任何实时事件的机制保证）
        verify(reconnected, times(2)).send(any(SseEmitter.SseEventBuilder.class));

        // 阶段 2：实时事件（seq 3）随后到达，总数为 3（2 回放 + 1 实时），顺序由阶段划分保证
        bridge.push("run1", event(3));
        verify(reconnected, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
