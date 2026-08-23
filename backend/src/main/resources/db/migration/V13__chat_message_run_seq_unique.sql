-- B2-4 数据层兜底：chat_message (run_id, seq) 唯一索引
-- 完成时刻 DB 故障的残余路径曾使同一 run 的消息批次重复落库（应用层已加 persisted
-- 原子标志防护 + persistMessages 幂等冲突跳过），本唯一索引为最终防线：
-- 同一 run 内 seq 唯一，任何重复插入被数据库直接拒绝。
-- 原同列普通索引 idx_chat_message_run_seq（V6）被本唯一索引取代，移除避免冗余。
DROP INDEX IF EXISTS idx_chat_message_run_seq;

-- B2-4 漏洞存续期重复数据兼容（2026-08-23 审核补充）：漏洞修复上线前生产库可能已积累
-- 重复的 (run_id, seq) 活跃行，直接建唯一索引会因键重复失败、阻断部署。
-- 建索引前先按组软删历史重复行：每组保留 id 最大的一条（最后一次落库的结果，
-- 与 persistMessages 幂等跳过语义一致——后落库批次视为已生效），其余置
-- deleted = 迁移执行时刻毫秒时间戳（与业务代码软删写 System.currentTimeMillis() 同语义，
-- 本表 deleted 为 BIGINT，0=活跃、非 0=已删，部分索引 WHERE deleted = 0 自动排除）。
UPDATE chat_message
SET deleted = (extract(epoch FROM now()) * 1000)::bigint
WHERE deleted = 0
  AND id NOT IN (
      SELECT max(id) FROM chat_message
      WHERE deleted = 0
      GROUP BY run_id, seq
  );

CREATE UNIQUE INDEX uniq_chat_message_run_seq ON chat_message(run_id, seq) WHERE deleted = 0;
