package com.commerce.rag.bot.chat.stream;

import com.commerce.rag.bot.chat.ChatMessage;
import com.commerce.rag.bot.chat.ChatRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * SSE 桥接层
 *
 * 负责将 Redis 响应流桥接到 SseEmitter：
 * - 新请求：创建 SseEmitter + 启动异步轮询 Redis Stream
 * - 续传请求：XRANGE 重放 + 继续轮询
 * - 心跳保活（当无新事件时定期发送 comment）
 * - 客户端断连时写入取消标记
 *
 * 每个活跃连接占用一个轮询线程，通过 ScheduledExecutorService 管理。
 */
@Slf4j
@Component
public class ChatSseBridge {

    private final StreamSessionManager streamManager;
    private final ChatRepository chatRepository;

    /** 轮询线程池（核心线程数 = CPU * 2，足以应对中等并发） */
    private final ScheduledExecutorService pollerPool = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() * 2,
            r -> {
                Thread t = new Thread(r, "sse-poller");
                t.setDaemon(true);
                return t;
            });

    /** 活跃连接追踪：runId → Future（用于取消轮询） */
    private final ConcurrentHashMap<UUID, Future<?>> activePolls = new ConcurrentHashMap<>();

    /** SSE 超时时间（毫秒） */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    @Value("${stream.poll-timeout:2000}")
    private long pollTimeout;

    public ChatSseBridge(StreamSessionManager streamManager, ChatRepository chatRepository) {
        this.streamManager = streamManager;
        this.chatRepository = chatRepository;
    }

    @PreDestroy
    void shutdown() {
        pollerPool.shutdownNow();
    }

    /**
     * 创建新请求的 SseEmitter 并启动响应流轮询
     *
     * @param runId     本次流式回答的唯一标识
     * @param sessionId 会话 ID
     * @return SseEmitter 实例
     */
    public SseEmitter createStreamEmitter(UUID runId, UUID sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 客户端断连 / 超时 / 异常 → 写入取消标记 + 停止轮询
        emitter.onCompletion(() -> cancelPoll(runId));
        emitter.onTimeout(() -> cancelPoll(runId));
        emitter.onError(ex -> cancelPoll(runId));

        // 启动异步轮询
        Future<?> future = pollerPool.submit(() -> pollResponseStream(runId, "0-0", emitter));
        activePolls.put(runId, future);

        return emitter;
    }

    /**
     * 断线续传：根据 meta 状态决定重放还是返回 null
     *
     * @param runId       上次流的 runId
     * @param lastEventId 客户端已收到的最后一个 Redis Stream 事件 ID
     * @return 续传的 SseEmitter，如果流不存在或已完成则返回 null（由 Controller 决定降级策略）
     */
    public SseEmitter reconnectEmitter(UUID runId, String lastEventId) {
        Map<String, String> meta = streamManager.getSessionMeta(runId);
        if (meta == null) {
            log.warn("续传失败，meta 不存在: runId={}", runId);
            return null;
        }

        String status = meta.get("status");
        if ("completed".equals(status)) {
            // 流已完成 → 一次性推送完整消息
            return replayCompleted(runId, meta);
        }

        if ("streaming".equals(status) || "pending".equals(status)) {
            // 流仍在进行 → 重放未送达事件 + 继续轮询
            SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
            emitter.onCompletion(() -> cancelPoll(runId));
            emitter.onTimeout(() -> cancelPoll(runId));
            emitter.onError(ex -> cancelPoll(runId));

            // 重放已缓冲但未送达的事件
            List<Map<String, String>> missed = streamManager.getEventsAfter(runId, lastEventId);
            String latestId = lastEventId;
            for (Map<String, String> event : missed) {
                String eventId = sendEvent(emitter, event);
                if (eventId != null) latestId = eventId;
                if (isTerminalEvent(event.get("event"))) {
                    emitter.complete();
                    return emitter;
                }
            }

            // 继续轮询后续事件
            String fromId = latestId;
            Future<?> future = pollerPool.submit(() -> pollResponseStream(runId, fromId, emitter));
            activePolls.put(runId, future);

            log.info("续传成功: runId={}, 重放事件数={}", runId, missed.size());
            return emitter;
        }

        // error / cancelled → 不续传
        log.info("流已终态，不续传: runId={}, status={}", runId, status);
        return null;
    }

    /**
     * 异步轮询响应流，将事件桥接到 SseEmitter
     *
     * 当连续空轮询超过阈值时，检查 Redis meta 是否存在，
     * 若 meta 已过期（TTL 清理），说明流已结束，退出轮询防止线程泄漏。
     *
     * @param runId       流 ID
     * @param lastEventId 起始事件 ID
     * @param emitter     SseEmitter
     */
    private void pollResponseStream(UUID runId, String lastEventId, SseEmitter emitter) {
        String currentId = lastEventId;
        // 连续空轮询计数（Redis Stream TTL 过期后 meta 被清理，以此检测流结束）
        int consecutiveEmptyPolls = 0;
        // 每 30 次空轮询（约 60s）检查一次 meta 是否仍存在
        final int metaCheckInterval = 30;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                List<Map<String, String>> events = streamManager.readResponseStream(
                        runId, currentId, pollTimeout);

                if (events.isEmpty()) {
                    // 无新事件 → 发送心跳
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (IOException e) {
                        // 客户端已断连
                        break;
                    }
                    consecutiveEmptyPolls++;
                    // 周期性检查 meta：流已 TTL 过期则退出轮询
                    if (consecutiveEmptyPolls % metaCheckInterval == 0
                            && streamManager.getSessionMeta(runId) == null) {
                        log.info("响应流 meta 已过期，退出轮询: runId={}", runId);
                        try { emitter.complete(); } catch (Exception ignored) {}
                        return;
                    }
                    continue;
                }

                consecutiveEmptyPolls = 0;
                for (Map<String, String> event : events) {
                    String eventId = sendEvent(emitter, event);
                    if (eventId != null) currentId = eventId;

                    // 终态事件 → 完成 emitter 并退出
                    if (isTerminalEvent(event.get("event"))) {
                        emitter.complete();
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("响应流轮询退出: runId={}, reason={}", runId, e.getMessage());
            try { emitter.complete(); } catch (Exception ignored) {}
        } finally {
            activePolls.remove(runId);
        }
    }

    /**
     * 将 Redis 事件转发到 SseEmitter
     *
     * @return 事件 ID（如果发送成功）
     */
    private String sendEvent(SseEmitter emitter, Map<String, String> event) {
        String eventName = event.get("event");
        String data = event.get("data");
        String eventId = event.get("_id");
        try {
            emitter.send(SseEmitter.event()
                    .id(eventId)
                    .name(eventName)
                    .data(data));
            return eventId;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 已完成流的续传：从 PG 加载完整消息，一次性推送
     */
    private SseEmitter replayCompleted(UUID runId, Map<String, String> meta) {
        ChatMessage msg = chatRepository.findByRunId(runId);
        if (msg == null) return null;

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        try {
            emitter.send(SseEmitter.event().name("done").data(
                    Map.of("messageId", msg.getId().toString(), "content", msg.getContent())));
            emitter.complete();
        } catch (IOException e) {
            log.warn("续传已完成流发送失败: runId={}", runId);
        }
        return emitter;
    }

    /**
     * 取消轮询 + 写入取消标记
     */
    private void cancelPoll(UUID runId) {
        streamManager.setCancelFlag(runId);
        Future<?> future = activePolls.remove(runId);
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * 判断是否为终态事件
     */
    private boolean isTerminalEvent(String eventName) {
        return "done".equals(eventName)
                || "cancelled".equals(eventName)
                || "error".equals(eventName);
    }
}
