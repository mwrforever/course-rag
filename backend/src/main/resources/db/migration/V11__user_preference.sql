-- V11: 用户偏好记忆（spec §7.2）——一行 = (user_id, key, value)
-- 软删走项目全局约定 deleted 0/1 + MP @TableLogic（物理行保留审计，原始 SQL 可追溯）
CREATE TABLE user_preference (
    id                BIGINT PRIMARY KEY,          -- 雪花主键
    user_id           BIGINT NOT NULL,             -- 所属用户（硬隔离过滤键）
    key               VARCHAR(50)  NOT NULL,       -- 偏好维度（constants/PreferenceKeys 枚举约束）
    value             VARCHAR(100) NOT NULL,       -- 偏好取值（一行一个 value；多值 key 可多行）
    scope             VARCHAR(50),                 -- 适用场景（预留，可空）
    explicitness      NUMERIC(4,3),                -- LLM 初判语义明确度 0~1
    stability         NUMERIC(4,3),                -- 系统计算稳定性 0~1（min(1, 0.1+count*0.15)）
    confidence        NUMERIC(4,3),                -- LLM 初判置信度 0~1
    write_score       NUMERIC(4,3),                -- 综合写入分 0.4e+0.4s+0.2c
    status            VARCHAR(20)  NOT NULL DEFAULT 'active',   -- 业务状态 active/observing（软删统一走 deleted）
    observation_count INT          NOT NULL DEFAULT 1,          -- 观察计数（隐式晋升）
    version           INT          NOT NULL DEFAULT 1,          -- 单值 key 冲突更新 +1（历史审计）
    source            VARCHAR(20)  NOT NULL DEFAULT 'explicit', -- explicit=直接表达 / implicit=观察晋升
    deleted           BIGINT       NOT NULL DEFAULT 0,           -- 软删 0=未删/1=已删
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON COLUMN user_preference.key IS '偏好维度（response_language/response_verbosity/explain_depth/course_direction/tech_stack/response_style）';
COMMENT ON COLUMN user_preference.status IS '业务状态 active/observing（spec §7.2；软删统一走 deleted）';
COMMENT ON COLUMN user_preference.source IS '来源 explicit=直接表达 / implicit=观察晋升';
COMMENT ON COLUMN user_preference.deleted IS '软删 0=未删/1=已删（MP @TableLogic 全局约定）';

-- 查询路径加速（user_id + key 是读写过滤主键）
CREATE INDEX idx_user_pref_user_key ON user_preference(user_id, key, deleted);

-- 单值 key：同一 user+key 仅一行 active（response_language/response_verbosity/explain_depth）
CREATE UNIQUE INDEX uk_user_pref_single_active
    ON user_preference(user_id, key)
    WHERE deleted = 0 AND status = 'active'
      AND key IN ('response_language', 'response_verbosity', 'explain_depth');

-- 多值 key：同 user+key+value 仅一行 active（course_direction/tech_stack/response_style 并列不冲突）
CREATE UNIQUE INDEX uk_user_pref_value_active
    ON user_preference(user_id, key, value)
    WHERE deleted = 0 AND status = 'active';
