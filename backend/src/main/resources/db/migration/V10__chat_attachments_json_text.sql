-- 附件 JSON 列类型修正：JSONB → TEXT
-- 背景：V9 以 JSONB 定义 attachments_json，但本工程 JSON 列（chat_run.meta_json / chat_message.sources_json）
-- 统一为 TEXT——MyBatis-Plus 将 Java String 字段绑定为 varchar，PG 对 JSONB 列拒绝 varchar 绑定
-- （BadSqlGrammarException: column "attachments_json" is of type jsonb but expression is of type character varying），
-- 导致双存写入（chat_run UPDATE 与 chat_message INSERT）整体失败（ChatFlowIntegrationTest 实证）。
-- 修正为 TEXT 与既有 JSON 列一致，写入走默认 varchar 绑定即可；jsonb→text 隐式转换保留历史数据。
ALTER TABLE chat_run    ALTER COLUMN attachments_json TYPE TEXT;
ALTER TABLE chat_message ALTER COLUMN attachments_json TYPE TEXT;
