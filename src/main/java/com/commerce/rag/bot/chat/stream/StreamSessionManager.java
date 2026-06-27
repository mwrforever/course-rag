package com.commerce.rag.bot.chat.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis Streams 会话与队列管理器
 *
 * 三段解耦架构的核心 Redis 操作层：
 * 1. 请求队列：chat:request（Consumer Group，Worker XREADGROUP 消费）
 * 2. 响应流：chat:response:{runId}（Worker XADD 写入，Bridge XREAD 读取）
 * 3. 元数据：chat:meta:{runId}（Hash，status/messageId/sessionId）
 * 4. 取消标记：chat:cancel:{runId}（String flag，EX 30s）
 */
@Slf4j
@Component
public class StreamSessionManager {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 响应流 + meta 完成后的 TTL（秒） */
    @Value("${stream.response-ttl:300}")
    private int responseTtlSeconds;

    /** 请求队列 Stream Key */
    @Value("${stream.request-stream:chat:request}")
    private String requestStreamKey;

    /** Consumer Group 名称 */
    @Value("${stream.consumer-group:chat-workers}")
    private String consumerGroup;

    /** 取消标记存活时间（秒） */
    private static final int CANCEL_FLAG_TTL = 30;

    private static final String RESPONSE_PREFIX = "chat:response:";
    private static final String META_PREFIX = "chat:meta:";
    private static final String CANCEL_PREFIX = "chat:cancel:";

    public StreamSessionManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ══════════════════ 请求队列操作 ══════════════════

    /**
     * 幂等初始化 Consumer Group
     * 在 Worker 启动时调用，如果 Group 已存在则忽略。
     */
    public void initConsumerGroup() {
        try {
            // MKSTREAM=true 自动创建 Stream（不存在时）
            redisTemplate.opsForStream().createGroup(requestStreamKey, consumerGroup);
            log.info("Consumer Group 已就绪: stream={}, group={}", requestStreamKey, consumerGroup);
        } catch (Exception e) {
            // BUSYGROUP 表示 Group 已存在，忽略
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer Group 已存在: group={}", consumerGroup);
            } else {
                log.error("初始化 Consumer Group 失败", e);
                throw e;
            }
        }
    }

    /**
     * 发布用户请求到全局队列
     *
     * @param runId       本次流式回答的唯一标识
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     */
    public void publishRequest(UUID runId, UUID sessionId, String userMessage) {
        MapRecord<String, String, String> record = StreamRecords.string(
                Map.of("runId", runId.toString(),
                        "sessionId", sessionId.toString(),
                        "userMessage", userMessage)
        ).withStreamKey(requestStreamKey);

        redisTemplate.opsForStream().add(record);
        log.info("请求已入队: runId={}, sessionId={}", runId, sessionId);
    }

    /**
     * 获取新请求（XREADGROUP ">"，BLOCK 长轮询）
     *
     * @param consumerId 当前 Worker 实例的消费者标识
     * @param count      最大批次大小
     * @param blockMs    阻塞等待时间（毫秒），0 表示不阻塞
     * @return 待处理的请求记录列表
     */
    public List<MapRecord<String, Object, Object>> getPendingRequests(String consumerId, int count, long blockMs) {
        try {
            return redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerId),
                    StreamReadOptions.empty().count(count).block(Duration.ofMillis(blockMs)),
                    StreamOffset.create(requestStreamKey, ReadOffset.lastConsumed())
            );
        } catch (Exception e) {
            log.warn("读取请求队列异常: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取崩溃未 ACK 的 pending 消息（XREADGROUP "0"）
     * Worker 重启后优先处理这些消息，保证不丢。
     *
     * @param consumerId 当前 Worker 实例的消费者标识
     * @return 需要重试的请求记录列表
     */
    public List<MapRecord<String, Object, Object>> getRetryPending(String consumerId) {
        try {
            return redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerId),
                    StreamReadOptions.empty().count(100),
                    StreamOffset.create(requestStreamKey, ReadOffset.from("0"))
            );
        } catch (Exception e) {
            log.warn("读取 pending 消息异常: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * ACK 已处理的请求（从 pending 列表移除）
     *
     * @param recordId Redis Stream 记录 ID
     */
    public void ackRequest(String recordId) {
        redisTemplate.opsForStream().acknowledge(requestStreamKey, consumerGroup, recordId);
    }

    // ══════════════════ 响应流操作 ══════════════════

    /**
     * 写入响应事件到 chat:response:{runId}
     *
     * @param runId     流 ID
     * @param eventType 事件类型
     * @param data      事件数据（序列化为 JSON 存储）
     * @return Redis Stream 记录 ID
     */
    public String publishResponseEvent(UUID runId, SseEventType eventType, Object data) {
        String streamKey = RESPONSE_PREFIX + runId;
        String jsonData;
        try {
            jsonData = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("响应事件序列化失败: eventType={}", eventType, e);
            return null;
        }

        MapRecord<String, String, String> record = StreamRecords.string(
                Map.of("event", eventType.eventName, "data", jsonData)
        ).withStreamKey(streamKey);

        RecordId recordId = redisTemplate.opsForStream().add(record);
        return recordId != null ? recordId.getValue() : null;
    }

    /**
     * 阻塞读取响应流新事件（Bridge 轮询用）
     *
     * @param runId       流 ID
     * @param lastEventId 上次已读到的 Redis Stream ID（"0-0" 从头开始）
     * @param blockMs     阻塞超时（毫秒）
     * @return 新事件列表，每个元素是 {event, data, _id}
     */
    public List<Map<String, String>> readResponseStream(UUID runId, String lastEventId, long blockMs) {
        String streamKey = RESPONSE_PREFIX + runId;
        String afterId = (lastEventId == null || lastEventId.isEmpty()) ? "0-0" : lastEventId;

        List<MapRecord<String, Object, Object>> records;
        try {
            records = redisTemplate.opsForStream().read(
                    StreamReadOptions.empty().block(Duration.ofMillis(blockMs)),
                    StreamOffset.create(streamKey, ReadOffset.from(afterId))
            );
        } catch (Exception e) {
            log.debug("读取响应流超时或异常: runId={}", runId);
            return List.of();
        }

        if (records == null || records.isEmpty()) return List.of();

        return records.stream()
                .map(r -> {
                    Map<String, String> result = new HashMap<>();
                    r.getValue().forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
                    result.put("_id", r.getId().getValue());
                    return result;
                })
                .collect(Collectors.toList());
    }

    /**
     * 读取响应流中某个 ID 之后的所有事件（断线续传重放用，非阻塞）
     *
     * @param runId       流 ID
     * @param lastEventId 客户端已收到的最后一个 Redis Stream ID
     * @return 事件列表
     */
    public List<Map<String, String>> getEventsAfter(UUID runId, String lastEventId) {
        String streamKey = RESPONSE_PREFIX + runId;
        String startId = (lastEventId == null || lastEventId.isEmpty()) ? "0-0" : lastEventId;

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(streamKey, Range.open(startId, "+"));

        if (records == null) return List.of();

        return records.stream()
                .map(r -> {
                    Map<String, String> result = new HashMap<>();
                    r.getValue().forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
                    result.put("_id", r.getId().getValue());
                    return result;
                })
                .collect(Collectors.toList());
    }

    // ══════════════════ 元数据管理 ══════════════════

    /**
     * 创建响应流会话元数据（Controller 在发布请求前调用）
     *
     * @param runId     流 ID
     * @param sessionId 会话 ID
     */
    public void createStreamSession(UUID runId, UUID sessionId) {
        String metaKey = META_PREFIX + runId;
        Map<String, String> meta = Map.of(
                "status", "pending",
                "sessionId", sessionId.toString(),
                "createdAt", String.valueOf(System.currentTimeMillis())
        );
        redisTemplate.opsForHash().putAll(metaKey, meta);
        log.info("创建流会话: runId={}, sessionId={}", runId, sessionId);
    }

    /**
     * 更新 meta 中的 messageId（Worker 创建 ASSISTANT 消息后调用）
     */
    public void updateMessageId(UUID runId, UUID messageId) {
        String metaKey = META_PREFIX + runId;
        redisTemplate.opsForHash().put(metaKey, "messageId", messageId.toString());
        redisTemplate.opsForHash().put(metaKey, "status", "streaming");
    }

    /**
     * 标记流正常完成，设置响应流 + meta TTL
     */
    public void markCompleted(UUID runId) {
        String metaKey = META_PREFIX + runId;
        redisTemplate.opsForHash().put(metaKey, "status", "completed");
        Duration ttl = Duration.ofSeconds(responseTtlSeconds);
        redisTemplate.expire(metaKey, ttl);
        redisTemplate.expire(RESPONSE_PREFIX + runId, ttl);
        log.info("流完成: runId={}, TTL={}s", runId, responseTtlSeconds);
    }

    /**
     * 标记流被取消
     */
    public void markCancelled(UUID runId) {
        String metaKey = META_PREFIX + runId;
        redisTemplate.opsForHash().put(metaKey, "status", "cancelled");
        Duration ttl = Duration.ofSeconds(responseTtlSeconds);
        redisTemplate.expire(metaKey, ttl);
        redisTemplate.expire(RESPONSE_PREFIX + runId, ttl);
        log.info("流被取消: runId={}", runId);
    }

    /**
     * 标记流异常
     */
    public void markError(UUID runId, String errorMessage) {
        String metaKey = META_PREFIX + runId;
        redisTemplate.opsForHash().put(metaKey, "status", "error");
        redisTemplate.opsForHash().put(metaKey, "errorMessage", errorMessage);
        Duration ttl = Duration.ofSeconds(responseTtlSeconds);
        redisTemplate.expire(metaKey, ttl);
        redisTemplate.expire(RESPONSE_PREFIX + runId, ttl);
        log.info("流异常: runId={}, error={}", runId, errorMessage);
    }

    /**
     * 获取流的元数据状态
     *
     * @param runId 流 ID
     * @return 元数据 map（status, messageId, sessionId），不存在返回 null
     */
    public Map<String, String> getSessionMeta(UUID runId) {
        String metaKey = META_PREFIX + runId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(metaKey);
        if (entries.isEmpty()) return null;

        Map<String, String> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    // ══════════════════ 取消标记管理 ══════════════════

    /**
     * 设置取消标记（前端调用取消端点 / 客户端断连时触发）
     *
     * @param runId 流 ID
     */
    public void setCancelFlag(UUID runId) {
        String cancelKey = CANCEL_PREFIX + runId;
        redisTemplate.opsForValue().set(cancelKey, "1", Duration.ofSeconds(CANCEL_FLAG_TTL));
        log.info("设置取消标记: runId={}", runId);
    }

    /**
     * 检查流是否被取消
     *
     * @param runId 流 ID
     * @return true 表示前端已请求取消
     */
    public boolean isCancelled(UUID runId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(CANCEL_PREFIX + runId));
    }
}
