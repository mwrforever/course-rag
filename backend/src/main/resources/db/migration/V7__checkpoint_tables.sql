-- ══════════════════════════════════════════════════════════════════════════════
-- V7__checkpoint_tables.sql
-- SAA PostgresSaver Checkpoint 表（幂等版本）
--
-- 背景：SAA 1.1.2.0 PostgresSaver.initTable() 的建表 SQL 中
--   CREATE TABLE 带 IF NOT EXISTS，但 3 个 CREATE INDEX 无 IF NOT EXISTS，
--   导致 createTables(true) 在二次启动时必然报 "relation ... already exists"。
-- 方案：checkpoint DDL 交由 Flyway 管理（幂等），PostgresSaver 改为 createTables(false)。
-- 表名/列名/索引名与框架 SQL 完全一致（PG 自动小写折叠，不影响框架查询）。
-- ══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS GraphThread (
    thread_id UUID PRIMARY KEY,
    thread_name VARCHAR(255),
    is_released BOOLEAN DEFAULT FALSE NOT NULL
);

CREATE TABLE IF NOT EXISTS GraphCheckpoint (
    checkpoint_id UUID PRIMARY KEY,
    parent_checkpoint_id UUID,
    thread_id UUID NOT NULL,
    node_id VARCHAR(255),
    next_node_id VARCHAR(255),
    state_data JSONB NOT NULL,
    state_content_type VARCHAR(100) NOT NULL,
    saved_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_thread
        FOREIGN KEY(thread_id)
        REFERENCES GraphThread(thread_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id ON GraphCheckpoint(thread_id);
CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id_saved_at_desc ON GraphCheckpoint(thread_id, saved_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_lg4jthread_thread_name_unreleased ON GraphThread(thread_name) WHERE is_released = FALSE;
