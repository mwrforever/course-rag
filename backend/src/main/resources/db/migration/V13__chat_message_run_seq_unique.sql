-- B2-4 数据层兜底：chat_message (run_id, seq) 唯一索引
-- 完成时刻 DB 故障的残余路径曾使同一 run 的消息批次重复落库（应用层已加 persisted
-- 原子标志防护 + persistMessages 幂等冲突跳过），本唯一索引为最终防线：
-- 同一 run 内 seq 唯一，任何重复插入被数据库直接拒绝。
-- 原同列普通索引 idx_chat_message_run_seq（V6）被本唯一索引取代，移除避免冗余。
DROP INDEX IF EXISTS idx_chat_message_run_seq;
CREATE UNIQUE INDEX uniq_chat_message_run_seq ON chat_message(run_id, seq) WHERE deleted = 0;
