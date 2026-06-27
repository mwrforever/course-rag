-- V2: 会话、消息与用户反馈表

-- 会话表：每次用户开启一轮对话创建一个 session
CREATE TABLE chat_session (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    VARCHAR(100) NOT NULL,
    title      VARCHAR(300),                  -- 会话标题（可由第一条消息自动生成）
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / CLOSED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE chat_session IS '对话会话表，管理用户的多轮对话';

CREATE INDEX idx_session_user_id ON chat_session(user_id);
CREATE INDEX idx_session_status ON chat_session(status);

-- 消息表：记录每轮对话中用户和 AI 的消息
CREATE TABLE chat_message (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id   UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role         VARCHAR(20) NOT NULL,  -- USER / ASSISTANT / SYSTEM
    content      TEXT NOT NULL,
    intent_type  VARCHAR(20),           -- COURSE_INFO / HEURISTIC / QA（仅 ASSISTANT 消息有值）
    sources_json JSONB DEFAULT '[]',    -- RAG 引用的来源信息（chunk_id, heading_path 等）
    token_count  INT DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  chat_message IS '对话消息表，记录每条用户/AI消息及 RAG 引用来源';
COMMENT ON COLUMN chat_message.sources_json IS 'RAG 检索来源，格式: [{"chunkId":"...","headingPath":"...","score":0.85}]';

CREATE INDEX idx_message_session_id ON chat_message(session_id);
CREATE INDEX idx_message_created_at ON chat_message(created_at);

-- 用户反馈表：用户对 AI 回答的评价
CREATE TABLE user_feedback (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    message_id  UUID NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
    rating      SMALLINT NOT NULL,     -- 1-5 星评分
    comment     TEXT,                  -- 用户文字反馈（可选）
    intent_type VARCHAR(20),           -- 自动关联的意图类型
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  user_feedback IS '用户反馈表，用于持续优化 RAG 质量';
COMMENT ON COLUMN user_feedback.rating IS '评分：1=非常不满意，5=非常满意';

CREATE INDEX idx_feedback_session_id ON user_feedback(session_id);
CREATE INDEX idx_feedback_message_id ON user_feedback(message_id);
CREATE INDEX idx_feedback_rating ON user_feedback(rating);
