package com.commerce.rag.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * per-run LLM 调用消息捕获容器（消息实体化，2026-08-29）。
 *
 * <p>沿用 {@link com.commerce.rag.bot.graph.RetrieveNode#KEY_SOURCES_SINK} 回调容器模式：
 * worker 在 run 开始创建本容器并经 {@code RunnableConfig.metadata}
 * {@link com.commerce.rag.bot.graph.RetrieveNode#KEY_ASSISTANT_SINK} 键注入（对象引用经 SAA
 * 派生副本浅拷贝穿透，图内节点与 worker 原实例共享同一实例），三个捕获点向容器写入：
 * <ul>
 *   <li>QU：QueryUnderstandingService 流式聚合完成点（thinking 全文 + query_plan payload JSON）</li>
 *   <li>caption：ImageCaptionService 调用完成点（thinking 全文 + 描述文本）</li>
 *   <li>主 agent：SseEventTransformer AGENT_MODEL_FINISHED 转换点（thinking 全文 + 正文 + toolCalls）</li>
 * </ul>
 * run 终结时 ChatRequestWorker.persistMessages 经 {@link #snapshot()} 快照消费，
 * 每次调用落一条 {@code message_type='assistant'} 实体行。
 *
 * <p>思考增量语义：QU/caption 的思考经 ThinkingPusher 按 stage 累加全文，多次调用（如多图
 * caption）共享同一累加缓冲——本容器在 {@link #capture} 时记录各 stage 上次捕获的思考全文，
 * 以「本次全文 - 上次全文」截取增量作为本次调用的思考（保证拆行出的 thinking VO 不重复，
 * 与前端已推送的逐调用思考一致）；主 agent 的思考经 {@link #appendReasoning} 逐 chunk 累加，
 * 同源增量截取。
 *
 * <p>线程安全：图节点线程池（QU 节点线程 / 附件 caption 并行池）/ reactor 流线程与 worker
 * 落库线程跨线程共享，全部读写经 synchronized 互斥；快照返回深拷贝，锁外读取无竞态。
 * run 结束随 worker 引用释放被 GC，无跨 run 复用。
 */
public class AssistantMessageSink {

    /** 捕获列表（按捕获顺序 = 调用结束顺序，落库行序与拆行 VO 序的锚点） */
    private final List<AssistantMessageCapture> captures = new ArrayList<>();

    /** 按 stage 累积的思考全文（主 agent STREAMING chunk 的 reasoning 片段累加，capture 时截增量） */
    private final Map<String, StringBuilder> reasoningByStage = new LinkedHashMap<>();

    /** 各 stage 上次捕获时的思考全文（capture 时计算「本次调用思考」增量） */
    private final Map<String, String> lastReasoningByStage = new LinkedHashMap<>();

    /**
     * 累积一段思考片段（主 agent 模型流式 chunk 的 reasoning，transformer 推送 THINKING
     * 事件时同步写入——与前端已推送思考序列逐字一致）。
     *
     * @param stage 思考阶段键（generating）
     * @param delta 思考片段增量文本（null/空忽略）
     */
    public synchronized void appendReasoning(String stage, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        reasoningByStage.computeIfAbsent(stage, k -> new StringBuilder()).append(delta);
    }

    /**
     * 捕获一次 LLM 调用（模型输出完成点调用）。
     *
     * <p>思考增量规则：reasoning 入参为该 stage 的思考全文快照（QU/caption 取 ThinkingPusher
     * 累加缓冲、主 agent 取 metadata 或 {@link #accumulatedReasoning}），容器与上次捕获做差
     * 截取本次调用的思考增量；空调用（无思考、无正文、无工具调用）不捕获。
     *
     * @param stage     调用所属阶段键（understanding / attachments / generating）
     * @param reasoning 该 stage 的思考全文快照（可为 null）
     * @param text      调用 content 原文（可为 null）
     * @param toolCalls 工具调用列表（主 agent 非空；QU/caption 传 null 或空）
     */
    public synchronized void capture(
            String stage, String reasoning, String text, List<AssistantMessageCapture.AssistantToolCall> toolCalls) {
        boolean hasContent = (text != null && !text.isBlank())
                || (toolCalls != null && !toolCalls.isEmpty())
                || (reasoning != null && !reasoning.isBlank());
        if (!hasContent) {
            // 空调用不捕获（无任何可落库内容，与现状「空消息不落行」语义一致）
            return;
        }
        // 思考增量 = 本次全文快照 - 上次捕获时的全文（StringBuilder.append 单调追加，长度差即增量）
        String prev = lastReasoningByStage.getOrDefault(stage, "");
        String reasoningDelta = null;
        if (reasoning != null && reasoning.length() > prev.length()) {
            reasoningDelta = reasoning.substring(prev.length());
        }
        lastReasoningByStage.put(stage, reasoning == null ? "" : reasoning);
        captures.add(new AssistantMessageCapture(
                stage,
                reasoningDelta == null || reasoningDelta.isEmpty() ? null : reasoningDelta,
                text,
                toolCalls == null ? List.of() : toolCalls));
    }

    /**
     * 取指定 stage 累积的思考全文（transformer 在 FINISHED 消息 metadata 无 reasoning 时回退用）。
     *
     * @param stage 思考阶段键
     * @return 该 stage 累积思考全文；从未累积返回 null
     */
    public synchronized String accumulatedReasoning(String stage) {
        StringBuilder buf = reasoningByStage.get(stage);
        return buf == null ? null : buf.toString();
    }

    /**
     * 取捕获快照（深拷贝，落库消费用）。
     *
     * @return 捕获列表副本（按捕获顺序；从未捕获返回空列表，never null）
     */
    public synchronized List<AssistantMessageCapture> snapshot() {
        return new ArrayList<>(captures);
    }

    /**
     * 定向清除指定阶段的全部捕获与思考累积（M7 处理点 d：重试前清理陈旧 QU 捕获）。
     *
     * <p>背景：无产出失败的自动重试会重跑 QU 节点——失败尝试若恰在 QU 完成点捕获过消息
     * （QU 无 reasoning 输出的边缘形态，thinkingPusher 为空不构成产出），不清除则重试
     * 轮的新捕获与之叠加，最终落库出现重复 QU 实体行。定向按 stage 清除而非全量 reset：
     * attachments 阶段（caption 捕获发生在重试循环之前的附件编排，不重跑）与 generating
     * 阶段（有 DELTA/TOOL_CALL 即不重试）的捕获必须保留，全量清会造成真实数据丢失。
     *
     * @param stage 待清除的阶段键（understanding；null 按空串键清除，与 capture 归组口径一致）
     */
    public synchronized void clearStage(String stage) {
        String key = stage == null ? "" : stage;
        captures.removeIf(capture -> key.equals(capture.stage()));
        reasoningByStage.remove(key);
        lastReasoningByStage.remove(key);
    }
}
