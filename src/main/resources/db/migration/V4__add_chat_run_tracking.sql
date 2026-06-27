-- 流式对话跟踪 + 上下文压缩摘要
-- chat_message: 新增 run_id（关联 Redis Stream）和 stream_status（流式状态）
-- chat_session: 新增 context_summary（上下文压缩摘要，长对话 70% 阈值触发时生成）

ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS run_id UUID;
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS stream_status VARCHAR(20);

-- run_id 索引：断线续传时通过 run_id 查询已完成的 ChatMessage
CREATE INDEX IF NOT EXISTS idx_chat_message_run_id ON chat_message (run_id)
    WHERE run_id IS NOT NULL;

ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS context_summary TEXT;
