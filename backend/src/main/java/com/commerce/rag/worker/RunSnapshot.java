package com.commerce.rag.worker;

import java.util.Map;

/**
 * pre-run 快照 —— 在图执行前捕获 checkpoint 状态，用于取消后回滚。
 *
 * <p>实际 SAA 1.1.2.0 API 中 {@code PostgresSaver.get(config)} 返回
 * {@code Optional<Checkpoint>}，Checkpoint 提供 {@code getState()}、
 * {@code getNodeId()}、{@code getNextNodeId()} 等方法。
 * 本 record 对 state 做容器级浅拷贝（顶层 Map 独立、值对象引用共享）——
 * SAA 图执行期不原地修改 checkpoint state（1.1.2.0 jar 实证），
 * 浅拷贝即可保证回滚快照安全且 Message 类型保留（P1-3）。
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
