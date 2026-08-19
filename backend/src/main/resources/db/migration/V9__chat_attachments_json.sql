-- 用户附件双存：chat_run（业务入口）+ chat_message（渲染/审计）
-- 结构：JSON 数组 [{"type":"image|document","url":"...","name":"...","size":123}]
ALTER TABLE chat_run    ADD COLUMN attachments_json JSONB;
ALTER TABLE chat_message ADD COLUMN attachments_json JSONB;
