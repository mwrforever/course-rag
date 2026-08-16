-- L-11 核对（2026-08-16）：dashboard 周期/趋势统计（feedbackStats/feedbackTrend）按 created_at
-- 过滤分组，user_feedback 既有索引（idx_user_feedback_intent_liked / idx_user_feedback_session）
-- 不覆盖该列，补单列部分索引（deleted=0 风格与同表其它索引一致）。
CREATE INDEX idx_user_feedback_created_at ON user_feedback(created_at) WHERE deleted = 0;
