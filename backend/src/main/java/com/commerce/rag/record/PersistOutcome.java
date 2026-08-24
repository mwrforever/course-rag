package com.commerce.rag.record;

/**
 * 消息落库结果 —— ChatRequestWorker.persistMessages 的返回值（R2 补口 B）
 *
 * <p>doOnComplete 时序：先落库（batchInsert=saveBatch，返回后实体列表雪花 ID 已回填）、
 * 再推 END 事件——因此落库成功分支可携带最终 assistant 正文行（role=ASSISTANT 且
 * messageType==null）的 ID，供 END 事件 payload 的 messageId 字段下发（前端反馈接口必需）。
 *
 * <p>各分支取值：
 * <ul>
 *   <li>落库成功：persisted=true + assistantMessageId=正文行回填雪花 ID（无正文行为 null）</li>
 *   <li>幂等跳过（(run_id,seq) 唯一索引冲突）：persisted=true + assistantMessageId=null
 *       （本批未新落库、无回填 ID，END 事件 messageId 显式 null 降级）</li>
 *   <li>落库失败：persisted=false + assistantMessageId=null（调用方可重试补落库）</li>
 * </ul>
 *
 * @param persisted         true=已落库（含唯一索引冲突的幂等跳过，调用方不得重试）；
 *                          false=落库失败且未确认写入（调用方可重试）
 * @param assistantMessageId 最终 assistant 正文行的落库回填雪花 ID（无正文行/幂等跳过/失败时为 null）
 * @author commerce-rag
 */
public record PersistOutcome(boolean persisted, Long assistantMessageId) {}
