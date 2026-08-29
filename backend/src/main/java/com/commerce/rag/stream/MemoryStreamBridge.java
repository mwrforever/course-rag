package com.commerce.rag.stream;

import com.commerce.rag.properties.StreamProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
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
 *   <li>实时推送：事件写入 ring buffer → 有界投递队列 → 独立投递线程唤醒订阅者（SseEmitter）→ 前端 EventSource 消费</li>
 *   <li>O(1) 回放：断线重连时根据 lastEventId 计算 offset，从 ring buffer 回放后续事件</li>
 *   <li>降级：ring buffer 分配失败 → fallback ConcurrentLinkedQueue（不终止 run）</li>
 * </ol>
 *
 * <p>H-1（2026-08-16 性能报告）：事件投递与生成线程解耦——每 run 一个「有界投递队列 +
 * 独立投递线程」，SseEmitter.send 的阻塞网络 IO 不再发生在生成线程（doOnNext/blockLast）上，
 * 慢客户端只阻塞自己的投递线程；投递队列满（投递线程被慢客户端卡住）时摘除全部订阅者
 * （complete → EventSource 自动重连 → 经 ring 回放补偿，事件不丢——ring 是恢复的事实来源）。
 *
 * <p>M-3：断线重连回放不再持 stateLock 逐条 send——锁内仅收集回放快照（引用列表）并入队
 * 回放批次，实际发送由投递线程执行；回放批次与广播事件在同一把锁内入队，FIFO 保证
 * 「回放事件（旧 seq）先于其后推送的实时事件（新 seq）送达」，顺序语义与 P1-1 一致。
 *
 * <p>seq 契约（task-2b 修复 2026-08-28）：事件 seqId 为生产 1-based 语义
 * （{@code RunState.nextSeq()} = incrementAndGet，首事件 seq=1），ring 内部 slot 数学与
 * seq 对齐（seq 写 slot (seq-1)%capacity，replay 同位定位校验 seqId）；对外契约不变——
 * 前端 lastEventId、SSE id: 行、PG 降级回放均为 1-based seq。修复前 push 按 0-based 写入位
 * 定位 slot，与 1-based seq 恒差 1，重连回放快照恒空且误报命中（不降级 PG，静默丢事件）。
 *
 * <p>线程模型：生成线程 → push（锁内写 ring + 入队，纯内存 O(1)，永不阻塞网络 IO）；
 * 投递线程（每 run 一个，daemon）→ take 队列逐条发送。
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
     * 写入事件到 ring buffer + 投递队列（异步送达订阅者）。
     *
     * <p>H-1：本方法只做「锁内写 ring + 有界队列入队」（O(1) 纯内存），
     * 实际 SseEmitter.send 由投递线程执行——慢客户端不再拖停生成线程。
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
     * 本方法返回时回放尚未发送（投递线程异步执行）——顺序保证为结构性：
     * 回放批次在锁内先于其后广播事件入队，单投递线程 FIFO 逐条发送，
     * 回放事件（旧 seq）必然先于任何实时事件（新 seq）送达（M-3）。
     *
     * @param runId       Run 唯一标识
     * @param lastEventId 客户端最后收到的 eventId
     * @param emitter     SSE 订阅者
     * @return true=回放已入队（投递线程将发送并注册）；false=ring 不存在、lastEventId 已被覆盖
     *         或投递队列满（需降级查 PG）
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

    /**
     * 测试/诊断辅助：指定 runId 的投递队列积压数（-1 = ring 不存在）
     */
    int outboxPending(String runId) {
        Ring ring = rings.get(runId);
        return ring == null ? -1 : ring.outbox.size();
    }

    // ── 内部类 ──

    /**
     * per-run ring buffer。
     * 正常模式：SseEvent[] 环形数组 + AtomicLong head（2026-08-29 收口①后 = 已见最大事件的
     * 1-based seqId，push 直取产生方事件号经 accumulateAndGet(seq, Math::max) 写入，永不回绕）。
     * slot 坐标系与 seq 对齐：seq 写入 slot (seq-1)%capacity，replay 按同一定位校验 seqId。
     * 降级模式：ConcurrentLinkedQueue（buffer == null, fallback != null）。
     */
    static final class Ring {

        final String runId;
        final SseEvent[] buffer; // 降级模式下为 null
        final int capacity;
        /**
         * 已见最大事件 seqId（2026-08-29 ring 收口①：push 直取产生方事件号后 head 语义
         * 收敛为「已见最大号」，作为回放区间上界；初值 0 = 尚无事件 → 回放空）。
         * accumulateAndGet(seq, Math::max) 写入，对乱序/跳号入队保持 max 语义，
         * 与生产 seq（RunState.nextSeq 1-based 递增）同一坐标系。
         */
        final AtomicLong head;

        final List<SseEmitter> subscribers;
        volatile boolean closed;

        /** B2-1: close 等待投递线程排空 outbox 的上限（毫秒）——超时兜底防慢客户端 send 卡死调用方 */
        private static final long CLOSE_DRAIN_TIMEOUT_MS = 3000;

        /** 回放/订阅与 push 写入共享的锁（回放区间收集与广播入队原子，保证不丢不重 + 顺序） */
        private final Object stateLock = new Object();

        /** 降级队列：非 null 表示处于降级模式 */
        final Queue<SseEvent> fallback;

        /** H-1: 有界投递队列（容量 = ring capacity）；投递线程逐条发送 */
        final LinkedBlockingQueue<Deliverable> outbox;

        /** H-1: 独立投递线程（每 run 一个，daemon，阻塞网络 IO 不触碰生成线程） */
        private volatile Thread deliveryThread;

        private Ring(String runId, int capacity, boolean useFallback) {
            this.runId = runId;
            this.capacity = capacity;
            this.buffer = useFallback ? null : new SseEvent[capacity];
            this.head = new AtomicLong(0);
            this.subscribers = new CopyOnWriteArrayList<>();
            this.fallback = useFallback ? new ConcurrentLinkedQueue<>() : null;
            this.outbox = new LinkedBlockingQueue<>(capacity);
        }

        /**
         * 工厂方法：尝试创建正常 ring buffer，OOM 时降级；随后启动投递线程。
         */
        static Ring create(String runId, int capacity) {
            Ring ring;
            try {
                ring = new Ring(runId, capacity, false);
            } catch (OutOfMemoryError e) {
                log.warn("ring buffer 分配失败 runId={}, 降级 ConcurrentLinkedQueue", runId, e);
                ring = new Ring(runId, capacity, true);
            }
            ring.startDeliveryThread();
            return ring;
        }

        /** 启动投递线程（每 run 一个，daemon） */
        private void startDeliveryThread() {
            Thread t = new Thread(this::deliveryLoop, "bridge-delivery-" + runId);
            t.setDaemon(true);
            t.start();
            deliveryThread = t;
        }

        /**
         * 投递线程主循环：FIFO 逐条处理（单线程天然有序——回放批次先于其后广播事件）。
         *
         * <p>B2-1 drain 语义：{@code closed} 置位后转入非阻塞排空模式——继续投递 outbox 中
         * 已入队事件（push 在锁内先于 close 检查 closed，close 后不再有新入队，排空即终态），
         * 排空完毕才退出线程；{@link #close()} 由此保证「push 紧邻 removeRing 的时序下，
         * 已入队的终态事件仍投递给订阅者后才 complete」，事件不因 close 被吞。
         */
        private void deliveryLoop() {
            while (true) {
                Deliverable item;
                if (closed) {
                    // drain 模式：非阻塞取剩余事件，队列排空即退出
                    item = outbox.poll();
                    if (item == null) {
                        break;
                    }
                } else {
                    try {
                        item = outbox.take();
                    } catch (InterruptedException e) {
                        // close() 的中断唤醒（仅 close 会中断本线程）：不恢复中断标志、
                        // 不直接退出——回到循环头按 closed 转入 drain 分支排空剩余事件
                        continue;
                    }
                }
                try {
                    if (item.replay) {
                        deliverReplay(item.emitter, item.replayEvents);
                    } else {
                        deliverBroadcast(item.event);
                    }
                } catch (Exception e) {
                    // 单条投递异常不终止线程（防御：complete 并发导致的 IllegalStateException 等）；
                    // drain 模式下同样继续排空剩余事件
                    log.warn("投递线程处理异常（跳过该事件）: runId={}, err={}", runId, e.getMessage());
                }
            }
        }

        void push(SseEvent event) {
            // buffer 写入与投递入队在锁内（纯内存操作，无 IO）；send 由投递线程执行
            synchronized (stateLock) {
                if (closed) return;
                if (fallback != null) {
                    fallback.offer(event);
                } else {
                    // 2026-08-29 ring 收口①：直取产生方事件号（event.seqId()，RunState.nextSeq
                    // 的 incrementAndGet 结果）写入 slot，消除「ring 自增计数器」与生产 seq 的
                    // 双计数器同步不变式——head 语义随之收敛为「已见最大号」（回放区间上界），
                    // accumulateAndGet 对乱序/跳号入队同样保持 max 语义；slot 仍按
                    // (seq-1)%capacity 定位，replay 同位定位 + seqId 校验命中不变
                    long seq = event.seqId();
                    int slot = (int) ((seq - 1) % capacity);
                    buffer[slot] = event;
                    head.accumulateAndGet(seq, Math::max);
                }
                // 广播入队失败（投递线程被慢客户端卡住、队列积满）→ 摘除全部订阅者，
                // 客户端经 EventSource 自动重连 + ring 回放补偿（事件在 ring 中不丢）
                if (!outbox.offer(Deliverable.broadcast(event))) {
                    dropAllSubscribers();
                }
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
         * 原子「回放 + 订阅」：锁内收集回放区间事件快照并入队回放批次（M-3：锁内不再发送）。
         *
         * <p>正确性：回放区间 (lastEventId, head@锁内] 与回放批次入队在同一临界区完成；
         * 入队后 push 的广播事件（head 之后）必然排在回放批次之后 → 单投递线程 FIFO 发送，
         * 回放事件（旧 seq）先于实时事件（新 seq）交付，并发下无丢失、无重复、无乱序。
         *
         * <p>回放批次实际发送在投递线程：先逐条发送回放事件，全部成功后注册 emitter
         * （注册前 push 的广播事件不送达该 emitter——回放覆盖 ring 中全部旧事件）。
         *
         * @param lastEventId 客户端最后收到的 eventId
         * @param emitter     重连的 SSE 订阅者
         * @return true=回放已入队；false=lastEventId 已被覆盖 / 投递队列满（需降级查 PG）
         */
        boolean replayAndSubscribe(long lastEventId, SseEmitter emitter) {
            List<SseEvent> snapshot = new ArrayList<>();
            synchronized (stateLock) {
                if (closed) {
                    return false;
                }
                if (fallback != null) {
                    // 降级路径：遍历 queue（O(n)，降级场景可接受）
                    for (SseEvent event : fallback) {
                        if (event.seqId() > lastEventId) {
                            snapshot.add(event);
                        }
                    }
                } else {
                    // head = 已见最大事件 seqId（2026-08-29 ring 收口①：push 直取产生方事件号，
                    // 初值 0=尚无事件），回放区间上界
                    long currentHead = head.get();
                    // evictFloor（驱逐下限，2026-08-29 收口④命名）：ring 内最旧保底序号 - 1——
                    // lastEventId 小于它即该事件已被环形覆盖驱逐，ring 无法回放需降级 PG
                    long evictFloor = Math.max(0, currentHead - capacity);
                    if (lastEventId < evictFloor) {
                        // lastEventId 太旧，ring buffer 已覆盖 → 需降级查 PG
                        log.warn(
                                "replayAndSubscribe 失败 runId={}: lastEventId={} < evictFloor={}",
                                runId,
                                lastEventId,
                                evictFloor);
                        return false;
                    }
                    if (lastEventId <= currentHead) {
                        // 回放区间 seq∈(lastEventId, currentHead]，slot 定位与 push 对齐：
                        // seq 写于 slot (seq-1)%capacity（1-based seq 减 1 映射到 0-based 数组下标）
                        for (long seq = lastEventId + 1; seq <= currentHead; seq++) {
                            int slot = (int) ((seq - 1) % capacity);
                            SseEvent event = buffer[slot];
                            if (event != null && event.seqId() == seq) {
                                snapshot.add(event);
                            } else {
                                // 2B deferred② 收口：slot 未命中（null 或 seqId 不匹配）此前静默跳过，
                                // ring 数据异常/竞态导致的事件丢失对观测不可见——记 warn 暴露丢失事实
                                // （快照继续，缺事件由客户端按 seqId 断口感知并可重连降级 PG 补偿）
                                log.warn(
                                        "回放定位未命中，事件缺失: runId={}, 期望 seq={}, slot内={}",
                                        runId,
                                        seq,
                                        event == null ? "null" : event.seqId());
                            }
                        }
                    }
                }
                // 回放批次入队（锁内与 push 的广播入队互斥——顺序保证的关键）；
                // 队列满（投递线程被慢客户端卡住）→ 返回 false，调用方降级查 PG
                if (!outbox.offer(Deliverable.replay(emitter, snapshot))) {
                    log.warn("replayAndSubscribe 失败 runId={}: 投递队列已满", runId);
                    return false;
                }
                return true;
            }
        }

        /**
         * 回放批次投递（投递线程执行）：逐条发送成功后注册 emitter。
         */
        private void deliverReplay(SseEmitter emitter, List<SseEvent> events) {
            for (SseEvent event : events) {
                if (!sendEvent(emitter, event)) {
                    // 发送失败：emitter 已在 sendEvent 内移除，不再注册
                    return;
                }
            }
            // 回放全部成功后再注册：注册后 push 的实时事件才送达该 emitter（顺序保证）
            synchronized (stateLock) {
                if (closed) {
                    return;
                }
                subscribers.add(emitter);
                emitter.onCompletion(() -> subscribers.remove(emitter));
                emitter.onTimeout(() -> subscribers.remove(emitter));
                emitter.onError(e -> subscribers.remove(emitter));
            }
        }

        /**
         * 广播投递（投递线程执行）：发送给当前全部订阅者。
         */
        private void deliverBroadcast(SseEvent event) {
            // CopyOnWriteArrayList 线程安全：send 失败移除不影响迭代
            for (SseEmitter emitter : subscribers) {
                sendEvent(emitter, event);
            }
        }

        /**
         * H-1: 投递队列积满（投递线程被慢客户端阻塞）→ 摘除全部订阅者。
         *
         * <p>complete 后浏览器 EventSource 自动重连（带 Last-Event-ID），经
         * {@link #replayAndSubscribe} 从 ring buffer 回放补偿，事件不丢。
         */
        private void dropAllSubscribers() {
            log.warn("投递队列已满（慢客户端阻塞投递线程），摘除全部订阅者（客户端重连后经 ring 回放补偿）: runId={}", runId);
            for (SseEmitter emitter : new ArrayList<>(subscribers)) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
                subscribers.remove(emitter);
            }
        }

        /**
         * 关闭 ring（B2-1 drain 语义改造）。
         *
         * <p>顺序保证：置 closed（此后 push/replayAndSubscribe 在锁内检查 closed，不再入队）
         * → 中断唤醒阻塞在 take() 的投递线程并<b>等待其排空 outbox 中已入队事件</b>
         * （有界等待，防慢客户端 send 永久卡住调用方）→ 排空完毕后再 complete 全部订阅者。
         * 由此「push 终态事件 → 紧邻 removeRing」的时序（如 runPool 拒绝快速失败分支）
         * 下，事件在订阅者被 complete 前完成投递，不再被吞。
         *
         * <p>等待超时兜底：投递线程阻塞在 socket send 超过 {@link #CLOSE_DRAIN_TIMEOUT_MS}
         * 时不再等待、直接 complete（该订阅者的连接已实质卡死，complete 触发前端重连后
         * 经 PG/重连降级补终态；与既有超时清理行为一致）。
         */
        void close() {
            synchronized (stateLock) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            // 唤醒阻塞在 take() 的投递线程促使其转入 drain 分支；阻塞在 socket send 上时
            // 中断不打断发送，由其完成本次 send 后回到循环头继续排空
            Thread thread = deliveryThread;
            if (thread != null) {
                thread.interrupt();
                try {
                    // 等待 drain 完毕（正常路径毫秒级：close 时 outbox 常已排空，
                    // take() 被中断唤醒后 poll 到空即退出）
                    thread.join(CLOSE_DRAIN_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
            } catch (RuntimeException e) {
                // 与 complete() 并发导致的 IllegalStateException 等（发送失败视同断连）
                log.warn("SseEmitter.send 运行时异常 runId={} seqId={}: {}", runId, event.seqId(), e.getMessage());
                subscribers.remove(emitter);
                return false;
            }
        }

        /**
         * 投递队列元素：广播事件（全体订阅者）或回放批次（单订阅者）。
         */
        static final class Deliverable {
            final SseEvent event; // BROADCAST 用
            final SseEmitter emitter; // REPLAY 用
            final List<SseEvent> replayEvents; // REPLAY 用
            final boolean replay;

            private Deliverable(SseEvent event, SseEmitter emitter, List<SseEvent> replayEvents, boolean replay) {
                this.event = event;
                this.emitter = emitter;
                this.replayEvents = replayEvents;
                this.replay = replay;
            }

            static Deliverable broadcast(SseEvent event) {
                return new Deliverable(event, null, null, false);
            }

            static Deliverable replay(SseEmitter emitter, List<SseEvent> events) {
                return new Deliverable(null, emitter, events, true);
            }
        }
    }
}
