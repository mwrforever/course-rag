-- =====================================================================
-- V16: chat_message 增加 thinking_stage 列 —— 支撑思考内容分阶段持久化
-- ---------------------------------------------------------------------
-- 1. 思考流式改造后，thinking 行按产出阶段归属持久化，前端历史回放时
--    可将思考内容挂回对应阶段卡片（understanding/attachments/generating）；
-- 2. 列可空：仅 message_type='thinking' 行回填阶段，其余行（普通消息 /
--    TOOL_CALL / TOOL_RESULT）保持 NULL，存量行不强制回填；
-- 3. VARCHAR(20) 与 message_type 同宽，枚举值短，无需 CHECK 约束
--    （阶段值扩容时免改表，口径以服务端常量为准）。
-- =====================================================================

ALTER TABLE chat_message ADD COLUMN thinking_stage VARCHAR(20);

COMMENT ON COLUMN chat_message.thinking_stage IS '思考来源阶段 understanding/attachments/generating（仅 message_type=thinking 行有值，其余行 NULL）';
