package com.commerce.rag.stream;

import com.commerce.rag.properties.StreamProperties;
import java.io.IOException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * per-run 内存 ring buffer，存储 SSE 事件流。
 *
 * <p>核心能力：
 * <ol>
 *   <li>实时推送：事件写入 ring buffer → 唤醒订阅者（SseEmitter）→ 前端 EventSource 消费</li>
 *   <li>O(1) 回放：断线重连时根据 lastEventId 计算 offset，从 ring buffer 回放后续事件</li>
 *   <li>降级：ring buffer 分配失败 → fallback ConcurrentLinkedQueue（不终止 run）</li>
 * </ol>
 *
 * <p>设计文档 §3.2 / §3.6
 */
@Component
public class MemoryStreamBridge {

    private static final Logger log = LoggerFactory.getLogger(MemoryStreamBridge.class);

    /** ring buffer 大小（默认 256，从 StreamProperties 注入） */
    private final int bufferSize;

    /** per-run ring buffer 存储 */
    private final ConcurrentHashMap<String, Ring> rings = new ConcurrentHashMap<>();

    public MemoryStreamBridge(StreamProperties streamProperties) {
        this.bufferSize = streamProperties.ringBufferSize();
    }

    // ── 公共 API ──

    /**
     * 为指定 runId 创建 ring buffer（由 ChatRequestWorker 在 run 开始时调用）。
     * 若 ring buffer 数组分配失败（OOM），降级为 ConcurrentLinkedQueue。
     */
    public Ring createRing(String runId) {
        return rings.computeIfAbsent(runId, id -> Ring.create(id, bufferSize));
    }

    /**
     * 写入事件到 ring buffer + 推送给所有订阅者。
     * SseEmitter.send() 在各自实例上同步（Spring 内部已保证线程安全）。
     */
    public void push(String runId, SseEvent event) {
        Ring ring = rings.get(runId);
        if (ring == null) {
            log.warn("push 失败: runId={} 的 ring 不存在", runId);
            return;
        }
        ring.push(event);
    }

    /**
     * 注册订阅者（SseEmitter），后续事件会推送给此 emitter。
     *
     * <p>P1-4：对已关闭（close 清空订阅者）的 ring 返回 false——调用方需查 run 终态
     * 补发 end 事件，避免新 emitter 无事件无 end 永久"生成中"。
     *
     * @param runId   Run 唯一标识
     * @param emitter SSE 订阅者
     * @return true=注册成功；false=ring 不存在或已关闭（调用方应补发终态）
     */
    public boolean subscribe(String runId, SseEmitter emitter) {
        Ring ring = rings.get(runId);
        if (ring == null) {
            log.warn("subscribe 失败: runId={} 的 ring 不存在", runId);
            return false;
        }
        return ring.subscribe(emitter);
    }

    /**
     * 原子「回放 + 订阅」——断线重连主路径（P1-2 B5 竞态修复）。
     *
     * <p>回放 lastEventId 之后的事件并注册 emitter，与 push 并发下不丢不重。
     *
     * @param runId       Run 唯一标识
     * @param lastEventId 客户端最后收到的 eventId
     * @param emitter     SSE 订阅者
     * @return true=回放成功且已注册；false=ring 不存在或 lastEventId 已被覆盖（需降级查 PG）
     */
    public boolean replayAndSubscribe(String runId, long lastEventId, SseEmitter emitter) {
        Ring ring = rings.get(runId);
        if (ring == null) {
            log.warn("replayAndSubscribe 失败: runId={} 的 ring 不存在", runId);
            return false;
        }
        return ring.replayAndSubscribe(lastEventId, emitter);
    }

    /**
     * 移除 ring（run 结束后清理）。
     */
    public void removeRing(String runId) {
        Ring ring = rings.remove(runId);
        if (ring != null) {
            ring.close();
        }
    }

    // ── 内部类 ──

    /**
     * per-run ring buffer。
     * 正常模式：SseEvent[] 环形数组 + AtomicLong head（永不回绕的自增写入位）。
     * 降级模式：ConcurrentLinkedQueue（buffer == null, fallback != null）。
     */
    static final class Ring {

        final String runId;
        final SseEvent[] buffer; // 降级模式下为 null
        final int capacity;
        final AtomicLong head; // 下一个写入位置（自增，永不回绕）
        final List<SseEmitter> subscribers;
        volatile boolean closed;

        /** 回放/订阅与 push 写入共享的锁（回放区间与注册原子，保证不丢不重） */
        private final Object stateLock = new Object();

        /** 降级队列：非 null 表示处于降级模式 */
        final Queue<SseEvent> fallback;

        private Ring(String runId, int capacity, boolean useFallback) {
            this.runId = runId;
            this.capacity = capacity;
            this.buffer = useFallback ? null : new SseEvent[capacity];
            this.head = new AtomicLong(0);
            this.subscribers = new CopyOnWriteArrayList<>();
            this.fallback = useFallback ? new ConcurrentLinkedQueue<>() : null;
        }

        /**
         * 工厂方法：尝试创建正常 ring buffer，OOM 时降级。
         */
        static Ring create(String runId, int capacity) {
            try {
                return new Ring(runId, capacity, false);
            } catch (OutOfMemoryError e) {
                log.warn("ring buffer 分配失败 runId={}, 降级 ConcurrentLinkedQueue", runId, e);
                return new Ring(runId, capacity, true);
            }
        }

        void push(SseEvent event) {
            // buffer 写入与 head 递增在锁内（纯内存操作，无 IO）；send 在锁外（IO 不持锁）
            synchronized (stateLock) {
                if (closed) return;
                if (fallback != null) {
                    fallback.offer(event);
                } else {
                    long idx = head.getAndIncrement();
                    int slot = (int) (idx % capacity);
                    buffer[slot] = event;
                }
            }
            // 推送给所有订阅者（CopyOnWriteArrayList 线程安全）
            for (SseEmitter emitter : subscribers) {
                sendEvent(emitter, event);
            }
        }

        /**
         * 注册订阅者（P1-4：closed 检查与注册同锁——close 清空订阅者与 add 竞争时不会丢注册）
         *
         * @param emitter SSE 订阅者
         * @return true=注册成功；false=ring 已关闭（调用方应查终态补发 end）
         */
        boolean subscribe(SseEmitter emitter) {
            synchronized (stateLock) {
                if (closed) {
                    return false;
                }
                subscribers.add(emitter);
                // 配置 emitter 回调：完成/超时/错误时自动移除
                emitter.onCompletion(() -> subscribers.remove(emitter));
                emitter.onTimeout(() -> subscribers.remove(emitter));
                emitter.onError(e -> subscribers.remove(emitter));
                return true;
            }
        }

        /**
         * 原子「回放 + 订阅」：锁内收集回放区间事件、注册 emitter 并发送回放事件。
         *
         * <p>正确性：回放区间 (lastEventId, head@锁内] 与注册在同一临界区完成；
         * 锁内注册后 push 的新事件（head 之后）实时推送到已注册 emitter →
         * 并发下无丢失、无重复（对比旧的 replay+subscribe 两步之间的窗口丢失）。
         *
         * <p>P1-1 修复：回放事件在锁内发送——回放事件（旧 seq）先于任何 push 的
         * 实时事件（新 seq，push 需拿锁写 buffer 后才发送）交付，消除重连时
         * 新旧事件在 SseEmitter.send 上竞争导致的乱序（如 END 先于正文）。
         *
         * @param lastEventId 客户端最后收到的 eventId
         * @param emitter     重连的 SSE 订阅者
         * @return true=回放成功且已注册；false=lastEventId 已被覆盖（需降级查 PG）
         */
        boolean replayAndSubscribe(long lastEventId, SseEmitter emitter) {
            synchronized (stateLock) {
                if (closed) {
                    return false;
                }
                if (fallback != null) {
                    // 降级路径：遍历 queue（O(n)，降级场景可接受）
                    for (SseEvent event : fallback) {
                        if (event.seqId() > lastEventId) {
                            if (!sendEvent(emitter, event)) {
                                return false;
                            }
                        }
                    }
                } else {
                    long currentHead = head.get();
                    long oldestSeq = Math.max(0, currentHead - capacity);
                    if (lastEventId < oldestSeq) {
                        // lastEventId 太旧，ring buffer 已覆盖 → 需降级查 PG
                        log.warn(
                                "replayAndSubscribe 失败 runId={}: lastEventId={} < oldestSeq={}",
                                runId,
                                lastEventId,
                                oldestSeq);
                        return false;
                    }
                    if (lastEventId <= currentHead) {
                        for (long seq = lastEventId + 1; seq <= currentHead; seq++) {
                            int slot = (int) (seq % capacity);
                            SseEvent event = buffer[slot];
                            if (event != null && event.seqId() == seq) {
                                if (!sendEvent(emitter, event)) {
                                    return false;
                                }
                            }
                        }
                    }
                }
                // 回放事件全部发送成功后再注册：后续 push 实时推送到已注册 emitter
                subscribers.add(emitter);
                emitter.onCompletion(() -> subscribers.remove(emitter));
                emitter.onTimeout(() -> subscribers.remove(emitter));
                emitter.onError(e -> subscribers.remove(emitter));
                return true;
            }
        }

        void close() {
            closed = true;
            for (SseEmitter emitter : subscribers) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
            subscribers.clear();
        }

        /**
         * 向单个 emitter 发送事件，使用 SseEmitter.event() builder 生成标准 SSE 帧。
         * 失败时移除 emitter（客户端已断开），返回 false。
         */
        private boolean sendEvent(SseEmitter emitter, SseEvent event) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.seqId()))
                        .name(event.type().getEventName())
                        .data(event.payload()));
                return true;
            } catch (IOException e) {
                log.warn("SseEmitter.send 失败 runId={} seqId={}: {}", runId, event.seqId(), e.getMessage());
                subscribers.remove(emitter);
                return false;
            }
        }
    }
}
