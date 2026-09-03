package com.commerce.rag.worker;

import java.util.Map;

/**
 * pre-run 快照 —— 在图执行前捕获 checkpoint 状态，用于取消后回滚。
 *
 * <p>实际 SAA 1.1.2.0 API 中 {@code PostgresSaver.get(config)} 返回
 * {@code Optional<Checkpoint>}，Checkpoint 提供 {@code getState()}、
 * {@code getNodeId()}、{@code getNextNodeId()} 等方法。
 * 本 record 对 state 做容器级浅拷贝（仅顶层 Map 独立，OverAllState.updateState
 * 用 Stream.collect 产新 Map）——messages 列表与 checkpoint 原实例共享引用、
 * 图执行期会被 AppendStrategy 原地 addAll 追加污染（T8/T10 字节码实证，与
 * ChatRequestWorker#captureSnapshot javadoc 同口径），消费方（取消/重试回滚）按
 * historyMessageCount 游标截断剥离污染；浅拷贝仍保留 Message 类型（P1-3；原
 * JSON 深拷贝经无多态注册的 ObjectMapper 会把 Message 反序列化为
 * LinkedHashMap，类型破坏）。
 *
 * @param runId               Run 唯一标识
 * @param checkpointId        Checkpoint ID
 * @param nodeId              当前节点 ID
 * @param nextNodeId          下一节点 ID
 * @param state               Checkpoint 状态的容器级拷贝（顶层 Map 独立，值对象引用共享）
 * @param historyMessageCount pre-run checkpoint 中 messages 列表长度（持久化游标：本轮只落此数之后的新增消息；无 checkpoint 为 0）
 * @param capturedAt          快照捕获时间戳（毫秒）
 */
public record RunSnapshot(
        String runId,
        String checkpointId,
        String nodeId,
        String nextNodeId,
        Map<String, Object> state,
        int historyMessageCount,
        long capturedAt) {}
