-- V12: 用户经历记忆（spec §8.5）——一条 = 一个原子事实（同 type 可多条）
-- validity 状态机 active/superseded/merged/invalidated/archived（spec §8.6，archived 预留）
-- 软删走项目全局约定 deleted 0/1 + MP @TableLogic（物理行保留审计，原始 SQL 可追溯）
CREATE TABLE user_episodic_memory (
    id                BIGINT PRIMARY KEY,          -- 雪花主键
    user_id           BIGINT NOT NULL,             -- 所属用户（硬隔离过滤键，spec §10-6）
    type              VARCHAR(50)  NOT NULL,       -- 记忆分类（constants/EpisodicTypes 白名单）
    content           VARCHAR(2000) NOT NULL,      -- 完整记忆内容（事实源，注入用，提炼陈述）
    summary           VARCHAR(500),                -- 一句话摘要（与 content 合并做 embedding，spec §8.4）
    structured_facts  JSONB,                       -- 结构化事实（LLM 输出原文 JSON 存储，v1 不消费）
    importance        NUMERIC(4,3),                -- LLM 初判重要性 × 类型权重后的有效值（系统校正后）
    confidence        NUMERIC(4,3),                -- LLM 初判置信度 0~1
    validity          VARCHAR(20)  NOT NULL DEFAULT 'active',  -- 状态机 active/superseded/merged/invalidated/archived
    version           INT          NOT NULL DEFAULT 1,         -- 更新版本（UPDATE/MERGE 新行=旧+1，历史审计）
    source_session_id BIGINT,                      -- 来源会话（提取触发所在 run 的 session 快照，v1 落值）
    deleted           BIGINT       NOT NULL DEFAULT 0,           -- 软删 0=未删/1=已删（MP @TableLogic 全局约定）
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON COLUMN user_episodic_memory.type IS '记忆分类（learning_goal/learning_progress/resolved_question/personal_context，spec §8.2）';
COMMENT ON COLUMN user_episodic_memory.content IS '提炼后的原子事实陈述，非对话原文拷贝（spec §8.4）';
COMMENT ON COLUMN user_episodic_memory.validity IS '状态机 active/superseded/merged/invalidated/archived（archived 预留，spec §8.6）';
COMMENT ON COLUMN user_episodic_memory.importance IS '系统校正后的有效重要性 = LLM importance × typeWeight（spec §8.3）';
COMMENT ON COLUMN user_episodic_memory.deleted IS '软删 0=未删/1=已删（MP @TableLogic 全局约定）';

-- 查询路径加速（user_id 是硬隔离过滤主键；recall_history=true 时按 type 召回历史）
CREATE INDEX idx_episodic_user_type ON user_episodic_memory(user_id, type, validity, deleted);
CREATE INDEX idx_episodic_user_validity ON user_episodic_memory(user_id, validity, deleted);
