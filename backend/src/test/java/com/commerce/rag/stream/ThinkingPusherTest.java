package com.commerce.rag.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ThinkingPusher 单元测试 —— 验证 per-run 思考事件推送通道的
 * 事件构造（payload 结构 / stage 字段）、seq 与主链路同源递增、
 * 并发调用不重号不乱序、end 事件语义。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ThinkingPusher 思考推送通道测试")
class ThinkingPusherTest {

    @Mock
    private MemoryStreamBridge bridge;

    private ObjectMapper objectMapper;
    private SseEventTransformer.RunState runState;
    private ThinkingPusher pusher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");
        pusher = new ThinkingPusher("run1", bridge, runState, objectMapper);
    }

    // ==================== push：THINKING 事件 ====================

    @Test
    @DisplayName("push → 产生 THINKING 事件入 bridge，payload 为 {delta,stage} 且 seq 从 1 递增")
    void push_emitsThinkingEventWithDeltaAndStage() {
        // When: 连续推两段思考片段
        pusher.push("understanding", "分析用户问题");
        pusher.push("understanding", "识别意图为知识问答");

        // Then: bridge.push 收到 2 个 THINKING 事件
        ArgumentCaptor<SseEvent> captor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, times(2)).push(eq("run1"), captor.capture());
        List<SseEvent> events = captor.getAllValues();

        assertEquals(SseEventType.THINKING, events.get(0).type());
        assertTrue(events.get(0).payload().contains("\"delta\":\"分析用户问题\""));
        assertTrue(events.get(0).payload().contains("\"stage\":\"understanding\""));
        assertEquals(1, events.get(0).seqId());

        // seq 递增不重号
        assertEquals(SseEventType.THINKING, events.get(1).type());
        assertEquals(2, events.get(1).seqId());
    }

    @Test
    @DisplayName("push stage=null → stage 字段按空串输出（保持 payload 字段恒存在）")
    void push_nullStage_outputsEmptyStage() {
        pusher.push(null, "片段");

        ArgumentCaptor<SseEvent> captor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge).push(eq("run1"), captor.capture());
        assertTrue(captor.getValue().payload().contains("\"stage\":\"\""));
    }

    @Test
    @DisplayName("push delta 为 null/空 → 不发事件（空思考片段无渲染意义）")
    void push_nullOrEmptyDelta_noEvent() {
        pusher.push("understanding", null);
        pusher.push("understanding", "");

        verify(bridge, never()).push(anyString(), any(SseEvent.class));
    }

    // ==================== end：THINKING_END 事件 ====================

    @Test
    @DisplayName("end → 产生 THINKING_END 事件，payload 为 {stage}，seq 延续 push 之后")
    void end_emitsThinkingEndWithStage() {
        pusher.push("attachments", "解析图片内容");
        pusher.end("attachments");

        ArgumentCaptor<SseEvent> captor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, times(2)).push(eq("run1"), captor.capture());
        SseEvent endEvent = captor.getAllValues().get(1);

        assertEquals(SseEventType.THINKING_END, endEvent.type());
        assertEquals("{\"stage\":\"attachments\"}", endEvent.payload());
        // seq 延续同一计数器递增（push 占 1，end 为 2）
        assertEquals(2, endEvent.seqId());
    }

    // ==================== seq 与主链路（transformer）同源 ====================

    @Test
    @DisplayName("seq 同源验证：transformer 取号后 pusher 取号延续递增不冲突")
    void seq_sharedWithTransformer_monotonicAcrossSources() {
        // Given: 与 worker 一致——transformer 与 pusher 共享同一 RunState 实例
        SseEventTransformer transformer = new SseEventTransformer(objectMapper);
        SseEvent metadataEvent = transformer.createMetadataEvent(runState);

        // When: 主链路已占 seq=1，回调通道再推事件
        pusher.push("understanding", "思考片段");

        ArgumentCaptor<SseEvent> captor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge).push(eq("run1"), captor.capture());

        // Then: 两来源共用计数器，序号连续（1 → 2），不重号
        assertEquals(1, metadataEvent.seqId());
        assertEquals(2, captor.getValue().seqId());
    }

    // ==================== 并发安全 ====================

    @Test
    @DisplayName("并发 push → seq 不重号且入队顺序与 seq 一致（取号+推送同锁原子）")
    void push_concurrent_seqUniqueAndOrdered() throws Exception {
        // Given: 8 线程 × 每线程 25 次并发推送
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < perThread; i++) {
                            pusher.push("understanding", "并发片段");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(10, TimeUnit.SECONDS), "并发推送应在 10s 内完成");
        } finally {
            pool.shutdownNow();
        }

        // Then: 全部事件入 bridge，seq 互不重复（不重号）
        ArgumentCaptor<SseEvent> captor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, times(threads * perThread)).push(eq("run1"), captor.capture());
        List<SseEvent> events = captor.getAllValues();
        assertEquals(threads * perThread, events.size());

        // Then: mock 捕获顺序 = 实际入 bridge 顺序，seq 严格递增（不乱序）
        for (int i = 1; i < events.size(); i++) {
            assertTrue(
                    events.get(i).seqId() > events.get(i - 1).seqId(),
                    "seq 应严格递增，实际 index=" + i + ": " + events.get(i - 1).seqId() + " -> "
                            + events.get(i).seqId());
        }
        assertEquals(threads * perThread, events.get(events.size() - 1).seqId(), "末事件 seq 应等于总数（从 1 连续）");
    }

    // ==================== accumulated：按阶段累加思考全文（2026-08-28 时间线改版，落库用） ====================

    @Test
    @DisplayName("accumulated — push 按阶段累加：全文拼接、key 为首推顺序（供 worker 落 thinking 行）")
    void accumulated_concatenatesPerStageInInsertionOrder() {
        pusher.push("understanding", "思考一");
        pusher.push("understanding", "思考二");
        pusher.push("attachments", "解析图片");

        Map<String, StringBuilder> acc = pusher.accumulated();

        assertEquals(List.of("understanding", "attachments"), List.copyOf(acc.keySet()), "阶段键按首推顺序");
        assertEquals("思考一思考二", acc.get("understanding").toString());
        assertEquals("解析图片", acc.get("attachments").toString());
    }

    @Test
    @DisplayName("accumulated — 快照 Map 只读；空/null delta 与 end 不产生累加")
    void accumulated_mapReadOnlyAndIgnoresEmptyDelta() {
        pusher.push("understanding", "A");
        pusher.push("understanding", "");
        pusher.push("attachments", null);
        pusher.end("generating");

        Map<String, StringBuilder> snapshot = pusher.accumulated();
        assertEquals(1, snapshot.size(), "仅非空 delta 进入累加缓冲（end/空片段不建行）");
        assertEquals("A", snapshot.get("understanding").toString());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", new StringBuilder()));
    }

    @Test
    @DisplayName("accumulated — 从未推送时返回空 Map（never null）")
    void accumulated_noPush_returnsEmptyMap() {
        assertTrue(pusher.accumulated().isEmpty());
    }
}
