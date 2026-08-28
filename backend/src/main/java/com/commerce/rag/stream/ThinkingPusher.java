package com.commerce.rag.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * per-run 思考事件推送通道（2026-08-28 对话流式时间线改版）。
 *
 * <p>职责：让图内节点（QU 意图理解 / 附件 caption 等阻塞 LLM 阶段）产出的 reasoning 片段
 * 实时进入 SSE——经 {@code RunnableConfig.metadata} 中
 * {@link com.commerce.rag.bot.graph.RetrieveNode#KEY_THINKING_CALLBACK} 键携带的本回调
 * 构造 THINKING / THINKING_END 事件推入 {@link MemoryStreamBridge}。
 * 回调对模型不可见：metadata 是瞬时 Java 引用通道（与 KEY_RETRIEVAL_SOURCES 同通道，
 * 不写 State、不进 checkpoint），永不经过任何 LLM 可见的序列化。
 *
 * <p>依赖关系：per-run 一个实例，由 ChatRequestWorker 在 run 开始时创建并注册，
 * 持有 runId（ring 键）、bridge（事件投递）、RunState（与 SseEventTransformer 同源的
 * seq 计数器，保证主链路与回调链路事件序号全局单调不重号）、objectMapper（payload 序列化）。
 * run 结束 ring 移除后，后续 push 由 bridge 以「ring 不存在」warn 拒绝，本类无需反注册；
 * 实例随 run 结束失去引用被 GC。
 *
 * <p>线程安全：图节点可能在自己的线程池上并发调用本回调。seq 取自 RunState.seqCounter
 * （AtomicLong 自增，天然不重号）；「取号 + bridge.push」置于同一把锁内原子执行——
 * bridge 投递线程按入队 FIFO 发送，若不锁则可能先取号后入队造成送达乱序。
 * 与主链路（reactor doOnNext 线程经 transformer 取号推送）之间无跨来源锁，
 * 极端并发下两来源相邻序号存在毫秒级入队交错窗口（序号仍单调，前端按 seqId 幂等配对）。
 *
 * <p>事件 payload 契约：THINKING = {@code {delta, stage}}、THINKING_END = {@code {stage}}；
 * stage 区分思考来源阶段（understanding / attachments / generating），
 * 主 agent 生成阶段的 THINKING 由 {@link SseEventTransformer} 发送（固定 stage=generating）。
 */
public class ThinkingPusher {

    private static final Logger log = LoggerFactory.getLogger(ThinkingPusher.class);

    /** 思考片段日志截断长度（日志禁止打印完整思考内容，仅记截断摘要） */
    private static final int LOG_PREVIEW_MAX_LENGTH = 60;

    /** run 唯一标识（ring 键，字符串形态与 bridge.push / RunState.runId 一致） */
    private final String runId;
    /** SSE 事件投递桥（per-run ring 的写入方，自身线程安全） */
    private final MemoryStreamBridge bridge;
    /** run 级 SSE 序号状态（与 transformer 共享同一实例，seq 同源不乱号） */
    private final SseEventTransformer.RunState runState;
    /** payload JSON 序列化器（Spring 单例注入） */
    private final ObjectMapper objectMapper;

    /** 「取号 + 推送」临界区锁：保证并发调用下 seq 序与入队（送达）序一致 */
    private final Object pushLock = new Object();

    /**
     * 构造 per-run 思考推送通道。
     *
     * @param runId       run 唯一标识（字符串，bridge ring 键）
     * @param bridge      SSE 事件投递桥
     * @param runState    run 级 SSE 状态（seq 计数器与主链路同源，必须传 worker 的同一实例）
     * @param objectMapper payload 序列化器
     */
    public ThinkingPusher(
            String runId, MemoryStreamBridge bridge, SseEventTransformer.RunState runState, ObjectMapper objectMapper) {
        this.runId = runId;
        this.bridge = bridge;
        this.runState = runState;
        this.objectMapper = objectMapper;
    }

    /**
     * 推送一段思考片段（THINKING 事件，payload {@code {delta, stage}}）。
     *
     * <p>适用场景：图节点（QU / caption）流式产出 reasoning 时逐片段调用。
     * delta 为 null/空时跳过不发（空思考片段无渲染意义，避免噪声事件）。
     * 并发安全：取号与入队同锁原子，多线程并发调用不重号、不乱序。
     *
     * @param stage 思考来源阶段键（如 understanding / attachments；null 按空串输出，保持字段恒存在）
     * @param delta 思考片段增量文本（非空才有事件）
     */
    public void push(String stage, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delta", delta);
        payload.put("stage", stage == null ? "" : stage);
        // 取号与入队同锁原子：锁外取号会出现「先取号后入队」的乱序窗口
        SseEvent event;
        synchronized (pushLock) {
            event = buildEvent(SseEventType.THINKING, payload);
            bridge.push(runId, event);
        }
        log.debug(
                "推送 THINKING 事件: runId={}, stage={}, seqId={}, delta预览={}",
                runId,
                stage,
                event.seqId(),
                truncateForLog(delta));
    }

    /**
     * 推送指定阶段的思考结束标记（THINKING_END 事件，payload {@code {stage}}）。
     *
     * <p>适用场景：图节点某阶段 reasoning 流结束时调用，前端据此退出该阶段"思考中"态。
     * 与主链路 THINKING_END 的区别：主链路由 RunState CAS 全局去重仅一次，
     * 本回调按 stage 各自成对（understanding 的 end 不影响 attachments），去重责任在调用方节点。
     *
     * @param stage 思考来源阶段键（null 按空串输出，保持字段恒存在）
     */
    public void end(String stage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage == null ? "" : stage);
        // 取号与入队同锁原子（与 push 同理，两方法共锁保证 THINKING/THINKING_END 相对有序）
        SseEvent event;
        synchronized (pushLock) {
            event = buildEvent(SseEventType.THINKING_END, payload);
            bridge.push(runId, event);
        }
        log.debug("推送 THINKING_END 事件: runId={}, stage={}, seqId={}", runId, stage, event.seqId());
    }

    /**
     * 构建 SSE 事件：从共享 RunState 取号（与主链路同一 AtomicLong，序号全局单调）。
     */
    private SseEvent buildEvent(SseEventType type, Map<String, Object> payload) {
        long seqId = runState.nextSeq();
        return new SseEvent(type, seqId, toJson(payload), System.currentTimeMillis());
    }

    /**
     * 使用 Jackson 将 payload 序列化为 JSON 字符串。
     * 序列化失败时返回空 JSON 对象 "{}"，保证不中断流（与 transformer 同款降级）。
     */
    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("思考事件 payload 序列化失败（降级空对象）: runId={}, err={}", runId, e.getMessage());
            return "{}";
        }
    }

    /**
     * 思考片段日志截断：仅保留前 {@value #LOG_PREVIEW_MAX_LENGTH} 字符摘要，禁止落完整思考内容。
     */
    private String truncateForLog(String delta) {
        if (delta.length() <= LOG_PREVIEW_MAX_LENGTH) {
            return delta;
        }
        return delta.substring(0, LOG_PREVIEW_MAX_LENGTH) + "...";
    }
}
