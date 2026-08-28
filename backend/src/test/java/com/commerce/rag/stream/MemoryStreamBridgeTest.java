package com.commerce.rag.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commerce.rag.properties.StreamProperties;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MemoryStreamBridge 单元测试 —— 验证 ring buffer 的 push/replay/subscribe/cleanup 逻辑
 *
 * <p>注意：测试构造的 seqId 与生产语义一致——{@code RunState.nextSeq()} 为
 * {@code incrementAndGet()}，seq 从 1 起（1-based）。ring 内部 slot 数学必须对齐
 * 1-based seq（push 写 {@code (seq-1) % capacity}，replay 查同位并校验
 * {@code event.seqId() == seq}），否则断线重连回放会静默丢事件（task-2b 缺陷）。
 *
 * <p>H-1 改造后投递为异步（独立投递线程 + 有界队列）：send 断言需经
 * {@link #awaitSendCount} 轮询等待（投递线程逐条 FIFO 发送），
 * 不再依赖同步发送的时序。
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

    /** 创建 SseEvent，seqId 与生产 RunState.nextSeq() 同语义（1-based 递增） */
    private SseEvent event(long seqId) {
        return new SseEvent(SseEventType.DELTA, seqId, "{\"text\":\"msg" + seqId + "\"}", System.currentTimeMillis());
    }

    /** 轮询等待 mock emitter 的 send 调用次数达到 expected（投递异步，3s 超时） */
    private void awaitSendCount(SseEmitter emitter, int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            long sent = Mockito.mockingDetails(emitter).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("send"))
                    .count();
            if (sent >= expectedCount) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等待 send 调用次数超时: 期望 >= " + expectedCount);
    }

    /** 轮询等待投递队列排空（3s 超时） */
    private void awaitOutboxDrained(String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            int pending = bridge.outboxPending(runId);
            if (pending <= 0) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等待投递队列排空超时: runId=" + runId);
    }

    // ==================== push 测试 ====================

    @Test
    @DisplayName("push 无订阅者 — 不抛异常，事件存入 buffer")
    void push_noSubscribers_noException() {
        bridge.createRing("run1");

        // 不抛异常即可
        assertDoesNotThrow(() -> bridge.push("run1", event(1)));
    }

    @Test
    @DisplayName("push 有订阅者 — 投递线程异步送达（send 被调用 1 次）")
    void push_withSubscriber_emitterSendCalled() throws Exception {
        // Given
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.subscribe("run1", mockEmitter);

        // When
        bridge.push("run1", event(1));

        // Then: 投递线程异步发送
        awaitSendCount(mockEmitter, 1);
        verify(mockEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("H-1 push 异步投递 — 订阅者 send 阻塞时 push 立即返回（生成线程不被慢客户端拖停）")
    void push_slowSubscriber_doesNotBlockCaller() throws Exception {
        // Given: 订阅者 send 阻塞（模拟慢客户端 TCP 缓冲满）
        SseEmitter slowEmitter = mock(SseEmitter.class);
        CountDownLatch sendBlocked = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        doAnswer(inv -> {
                    sendBlocked.countDown();
                    releaseSend.await();
                    return null;
                })
                .when(slowEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        bridge.createRing("run1");
        bridge.subscribe("run1", slowEmitter);

        // When: push 必须立即返回（投递在线程中异步执行，而非调用线程同步 send）
        long start = System.currentTimeMillis();
        bridge.push("run1", event(1));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 1000, "push 应异步返回（生成线程不被慢客户端拖停），实际耗时 " + elapsed + "ms");

        // 投递线程确实在发送（阻塞在 send 上）
        assertTrue(sendBlocked.await(3, TimeUnit.SECONDS), "投递线程应已开始发送");
        releaseSend.countDown();
        awaitOutboxDrained("run1");
    }

    @Test
    @DisplayName("push 到不存在的 ring — 不抛异常（静默忽略）")
    void push_ringNotExist_noException() {
        assertDoesNotThrow(() -> bridge.push("nonexistent", event(1)));
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

        // When: push 1 个事件 → send 失败 → emitter 被移除；push 第二个事件不再发送
        bridge.push("run1", event(1));
        bridge.push("run1", event(2));
        awaitOutboxDrained("run1");

        // Then: send 只被调用 1 次（第一次 push），第二次 push 不会调用
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
        bridge.push("run1", event(1));
        awaitOutboxDrained("run1");
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("removeRing 不存在的 runId — 不抛异常")
    void removeRing_notExist_noException() {
        assertDoesNotThrow(() -> bridge.removeRing("nonexistent"));
    }

    @Test
    @DisplayName("B2-1 close 前入队事件仍被投递（drain 语义）— push 后紧邻 removeRing，outbox 积压事件不因 close 被吞")
    void removeRing_afterPush_pendingOutboxEventsStillDelivered() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        // 首个 send 阻塞：确保 close 发生时投递线程卡在 send、事件 1 仍积压在 outbox
        // （复现「push→removeRing 紧邻时序」：终态事件入队后立即清理 ring）
        CountDownLatch sendBlocked = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        doAnswer(inv -> {
                    if (sendBlocked.getCount() > 0) {
                        sendBlocked.countDown();
                        releaseSend.await();
                    }
                    return null;
                })
                .when(mockEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        bridge.createRing("run1");
        bridge.subscribe("run1", mockEmitter);
        bridge.push("run1", event(1)); // 投递线程取走并阻塞在首个 send
        assertTrue(sendBlocked.await(3, TimeUnit.SECONDS), "投递线程应已阻塞在首个 send");
        bridge.push("run1", event(2)); // 积压在 outbox（投递线程被卡未消费）

        // 异步线程 close：等待 closed 置位（closed 后 subscribe 必返回 false）再放行首个 send，
        // 确保「事件 1 仍积压时 close 语义已生效」的确定性时序
        Thread closer = new Thread(() -> bridge.removeRing("run1"));
        closer.start();
        SseEmitter probe = mock(SseEmitter.class);
        long deadline = System.currentTimeMillis() + 3000;
        while (bridge.subscribe("run1", probe) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        releaseSend.countDown();
        closer.join(5000);

        // seq=1 + 积压的 seq=2 均送达订阅者（close 前入队的事件不丢——drain 语义），随后 complete
        awaitSendCount(mockEmitter, 2);
        verify(mockEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(mockEmitter, timeout(3000).times(1)).complete();
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

        bridge.push("run1", event(1));

        awaitSendCount(mockEmitter1, 1);
        awaitSendCount(mockEmitter2, 1);
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
        // Given: 生产语义 1-based seq（RunState.nextSeq 从 1 起）
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.push("run1", event(1));
        bridge.push("run1", event(2));
        bridge.push("run1", event(3));
        awaitOutboxDrained("run1");

        // When: 客户端已收到 seq=1 → lastEventId=1 回放 seqId=2,3
        boolean result = bridge.replayAndSubscribe("run1", 1, mockEmitter);

        // Then: 回放 2 个事件（投递线程异步送达）
        assertTrue(result);
        awaitSendCount(mockEmitter, 2);
        // 注册后新事件实时推送（第 3 次 send）
        bridge.push("run1", event(4));
        awaitSendCount(mockEmitter, 3);
        verify(mockEmitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("回归（task-2b off-by-one）— 生产 1-based seq：replay(lastEventId=k) 快照完整含 k+1..N（修复前恒为空快照）")
    void replayAndSubscribe_oneBasedSeq_replaysFullSnapshot() throws Exception {
        bridge.createRing("run1");
        int n = 10;
        for (long seq = 1; seq <= n; seq++) {
            bridge.push("run1", event(seq));
        }
        awaitOutboxDrained("run1");

        // 客户端收到 seq=6 后断线重连 → ring 命中应完整回放 seq 7..10 共 4 个事件；
        // 修复前 push 的 slot 与 seq 恒差 1，快照为空但仍返回 true（不降级 PG，静默丢事件）
        SseEmitter reconnected = mock(SseEmitter.class);
        assertTrue(bridge.replayAndSubscribe("run1", 6, reconnected), "ring 命中应返回 true（不降级 PG）");
        awaitSendCount(reconnected, 4);
        verify(reconnected, times(4)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("回归 — lastEventId=0（客户端从未收到事件）：回放 ring 起始全量事件 1..N")
    void replayAndSubscribe_zeroLastEventId_replaysAll() throws Exception {
        bridge.createRing("run1");
        for (long seq = 1; seq <= 5; seq++) {
            bridge.push("run1", event(seq));
        }
        awaitOutboxDrained("run1");

        SseEmitter reconnected = mock(SseEmitter.class);
        assertTrue(bridge.replayAndSubscribe("run1", 0, reconnected));
        awaitSendCount(reconnected, 5);
        verify(reconnected, times(5)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("回归 — capacity 驱逐环绕后回放中段：slot 数学跨环绕段仍一致（seq 251..300 完整）")
    void replayAndSubscribe_afterEvictionWrap_midRange() throws Exception {
        bridge.createRing("run1");
        for (long seq = 1; seq <= 300; seq++) {
            bridge.push("run1", event(seq));
        }
        awaitOutboxDrained("run1");

        // head=300，oldestSeq=300-256=44，lastEventId=250 在保留区间内 →
        // 回放 seq 251..300 共 50 个事件，其中 seq 257..300 的 slot 已环绕（(seq-1)%256 回卷到 0..43）
        SseEmitter reconnected = mock(SseEmitter.class);
        assertTrue(bridge.replayAndSubscribe("run1", 250, reconnected));
        awaitSendCount(reconnected, 50);
        verify(reconnected, times(50)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("replayAndSubscribe 边界 — lastEventId 超出 head：回放空事件但注册成功")
    void replayAndSubscribe_lastEventIdBeyondHead_registersOnly() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        bridge.push("run1", event(1));
        awaitOutboxDrained("run1");

        boolean result = bridge.replayAndSubscribe("run1", 100, mockEmitter);

        assertTrue(result);
        // 空回放批次：无任何 send
        verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        // 注册生效：新事件实时到达
        bridge.push("run1", event(2));
        awaitSendCount(mockEmitter, 1);
    }

    @Test
    @DisplayName("replayAndSubscribe 覆盖 — lastEventId 太旧（含 seq=1 已被环绕驱逐）返回 false 且不注册")
    void replayAndSubscribe_tooOld_returnsFalseAndNotRegister() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        bridge.createRing("run1");
        // 推入 BUFFER_SIZE+1 个事件（seq 1..257）：head=257，最旧可回放 lastEventId=257-256=1，
        // seq=1 已被 seq=257 环绕覆盖
        for (int i = 1; i <= BUFFER_SIZE + 1; i++) {
            bridge.push("run1", event(i));
        }
        awaitOutboxDrained("run1");

        // lastEventId=0 需回放 seq=1 起 → 已被驱逐 → false（降级 PG）
        boolean result = bridge.replayAndSubscribe("run1", 0, mockEmitter);

        assertFalse(result);
        // 未注册：后续 push 不送达
        bridge.push("run1", event(BUFFER_SIZE + 2));
        awaitOutboxDrained("run1");
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
    @DisplayName("回放定位未命中（slot null）→ 快照跳过该事件且记 warn 观测（2B deferred② 收口）")
    void replayAndSubscribe_slotMiss_skipsEventAndLogsWarn() throws Exception {
        // Given: logback ListAppender 捕获 MemoryStreamBridge 日志（静默跳过 → 可观测）
        ch.qos.logback.classic.Logger bridgeLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MemoryStreamBridge.class);
        ListAppender<ILoggingEvent> watcher = new ListAppender<>();
        watcher.start();
        bridgeLogger.addAppender(watcher);
        try {
            MemoryStreamBridge.Ring ring = bridge.createRing("run1");
            bridge.push("run1", event(1));
            bridge.push("run1", event(2));
            bridge.push("run1", event(3));
            awaitOutboxDrained("run1");
            // 人为破坏 seq=2 的 slot（null）：模拟 ring 数据异常/竞态——修复前该事件被静默跳过
            ring.buffer[(2 - 1) % BUFFER_SIZE] = null;

            // When: 从头回放（区间 seq 1..3）
            SseEmitter reconnected = mock(SseEmitter.class);
            boolean result = bridge.replayAndSubscribe("run1", 0, reconnected);

            // Then: 回放整体成功但缺失事件被跳过（仅送达 seq 1/3），且缺失以 warn 暴露（含期望 seq）
            assertTrue(result, "单事件缺失不构成整体失败（快照继续，客户端按 seqId 断口感知）");
            awaitSendCount(reconnected, 2);
            verify(reconnected, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            assertTrue(
                    watcher.list.stream()
                            .anyMatch(e -> e.getLevel() == Level.WARN
                                    && e.getFormattedMessage().contains("回放定位未命中")
                                    && e.getFormattedMessage().contains("seq=2")),
                    "slot 未命中应记 warn 观测（含期望 seq），实际日志: "
                            + watcher.list.stream()
                                    .map(ILoggingEvent::getFormattedMessage)
                                    .toList());
        } finally {
            bridgeLogger.detachAppender(watcher);
            bridge.removeRing("run1");
        }
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
                for (int i = 1; i <= total; i++) {
                    bridge.push("run1", event(i));
                }
            } catch (Throwable t) {
                pushError.set(t);
            }
        });
        pusher.start();
        start.countDown();

        // When: 与 push 并发重连（lastEventId=0 回放全部已推送事件，生产语义 seq 从 1 起）
        boolean result = bridge.replayAndSubscribe("run1", 0, mockEmitter);
        pusher.join(5000);

        // Then: 回放区间 (0, head@锁内] 与注册后实时区间互斥覆盖全部事件 → 总数恰为 total
        assertTrue(result);
        assertNull(pushError.get());
        awaitSendCount(mockEmitter, total);
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
    @DisplayName("P1-1/M-3 回放事件先于实时事件送达（回放批次入队先于其后广播，投递线程 FIFO）")
    void replayAndSubscribe_replayEventsDeliveredBeforeLiveEvents() throws Exception {
        bridge.createRing("run1");
        SseEmitter first = mock(SseEmitter.class);
        bridge.subscribe("run1", first);
        // 先推入 3 个历史事件（生产语义 1-based seq 1-3，客户端已收到 seq=1）
        for (long seq = 1; seq <= 3; seq++) {
            bridge.push("run1", event(seq));
        }
        awaitSendCount(first, 3);
        // 重连 emitter：回放 seq 2-3
        SseEmitter reconnected = mock(SseEmitter.class);
        assertTrue(bridge.replayAndSubscribe("run1", 1L, reconnected));

        // 阶段 1：在推入任何实时事件之前，回放事件（2 个）已全部送达——
        // 若回放与实时乱序（实时先到），此处 await 会等到实时事件混入而数量超 2
        awaitSendCount(reconnected, 2);

        // 阶段 2：实时事件（seq 4）随后到达，总数为 3（2 回放 + 1 实时）
        bridge.push("run1", event(4));
        awaitSendCount(reconnected, 3);
        verify(reconnected, times(3)).send(any(SseEmitter.SseEventBuilder.class));
        // 首订阅者共收到 4 个实时事件（seq 1-4）
        awaitSendCount(first, 4);
    }

    // ==================== H-1 慢客户端与队列满降级 ====================

    /** 构造阻塞在 send 上的慢订阅者（模拟客户端 TCP 缓冲满），返回阻塞信号 latch */
    private SseEmitter slowEmitter(CountDownLatch sendBlocked) throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(inv -> {
                    sendBlocked.countDown();
                    // 永久阻塞：模拟慢客户端 send 一直不返回
                    new CountDownLatch(1).await();
                    return null;
                })
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        return emitter;
    }

    @Test
    @DisplayName("H-1 投递队列积满（慢客户端卡住投递线程）→ 摘除全部订阅者（complete，重连后回放补偿）")
    void push_outboxFull_dropsAllSubscribers() throws Exception {
        CountDownLatch sendBlocked = new CountDownLatch(1);
        SseEmitter slowEmitter = slowEmitter(sendBlocked);
        bridge.createRing("run1");
        bridge.subscribe("run1", slowEmitter);
        // 首个事件交给投递线程（阻塞在 send 上，模拟慢客户端）
        bridge.push("run1", event(1));
        assertTrue(sendBlocked.await(3, TimeUnit.SECONDS), "投递线程应已开始发送（阻塞）");

        // 投递队列容量 = BUFFER_SIZE：投递线程卡在首个 send，后续事件堆积至队列满
        for (int i = 2; i <= BUFFER_SIZE + 2; i++) {
            bridge.push("run1", event(i));
        }

        // 队列满 → 摘除全部订阅者（complete 触发浏览器 EventSource 自动重连）
        verify(slowEmitter, timeout(3000).times(1)).complete();
    }

    @Test
    @DisplayName("H-1 投递队列已满时 replayAndSubscribe → 返回 false（调用方降级查 PG）")
    void replayAndSubscribe_outboxFull_returnsFalse() throws Exception {
        CountDownLatch sendBlocked = new CountDownLatch(1);
        SseEmitter slowEmitter = slowEmitter(sendBlocked);
        bridge.createRing("run1");
        bridge.subscribe("run1", slowEmitter);
        bridge.push("run1", event(1));
        assertTrue(sendBlocked.await(3, TimeUnit.SECONDS), "投递线程应已开始发送（阻塞）");
        for (int i = 2; i <= BUFFER_SIZE + 2; i++) {
            bridge.push("run1", event(i));
        }
        verify(slowEmitter, timeout(3000).times(1)).complete(); // 队列满已摘除

        // lastEventId 取未被环绕驱逐的区间（head=BUFFER_SIZE+2，可回放下限=head-capacity=2），
        // 确保命中的是「投递队列满」分支而非「lastEventId 太旧」分支
        SseEmitter reconnected = mock(SseEmitter.class);
        boolean result = bridge.replayAndSubscribe("run1", BUFFER_SIZE, reconnected);

        assertFalse(result, "投递队列满时应返回 false（PG 降级）");
    }

    @Test
    @DisplayName("send 抛 RuntimeException（与 complete 并发）→ 视为断连移除订阅者，投递线程继续")
    void send_runtimeException_removesSubscriber() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(mockEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        bridge.createRing("run1");
        bridge.subscribe("run1", mockEmitter);

        bridge.push("run1", event(1));
        bridge.push("run1", event(2));
        awaitOutboxDrained("run1");

        // 第一次 send 抛异常 → 移除；第二次事件不再发送
        verify(mockEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("回放发送失败（断连）→ 不注册订阅者，后续实时事件不送达")
    void replayAndSubscribe_sendFailure_notRegistered() throws Exception {
        bridge.createRing("run1");
        bridge.push("run1", event(1));
        bridge.push("run1", event(2));
        awaitOutboxDrained("run1");

        SseEmitter reconnected = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(reconnected).send(any(SseEmitter.SseEventBuilder.class));
        // lastEventId=1 → 快照非空（seq=2），首个回放事件即发送失败
        assertTrue(bridge.replayAndSubscribe("run1", 1, reconnected));

        // 首个回放事件发送失败 → 中止回放且不再注册（投递线程静默放弃）
        awaitOutboxDrained("run1");
        verify(reconnected, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        bridge.push("run1", event(3));
        awaitOutboxDrained("run1");
        verify(reconnected, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
