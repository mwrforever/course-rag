-- ═══════════════════════════════════════════════════════════════════════════
-- V6__full_schema_v5.sql
-- 全量建表脚本（DB Schema v5 前端驱动重构版）
-- 15 张业务表 + 55 个索引 + pg_trgm 扩展
-- 设计文档: docs/plans/2026-07-15-db-schema.md (v5)
-- ═══════════════════════════════════════════════════════════════════════════

-- 扩展
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ═══════════════════════════════════════════════════════════════════════════
-- 一、B 端知识库管理（3 表）
-- ═══════════════════════════════════════════════════════════════════════════

-- 1. knowledge_base ── 知识库
CREATE TABLE knowledge_base (
    id          BIGINT       PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  DEFAULT 'ACTIVE',
    created_by  BIGINT       NOT NULL,
    deleted     BIGINT       DEFAULT 0,
    created_at  TIMESTAMP  DEFAULT now(),
    updated_at  TIMESTAMP  DEFAULT now()
);
CREATE UNIQUE INDEX uniq_knowledge_base_name    ON knowledge_base(name) WHERE deleted = 0;
CREATE INDEX        idx_knowledge_base_status   ON knowledge_base(status) WHERE deleted = 0;
CREATE INDEX        idx_knowledge_base_created_by ON knowledge_base(created_by) WHERE deleted = 0;

-- 2. document ── 文档
CREATE TABLE document (
    id             BIGINT       PRIMARY KEY,
    kb_id          BIGINT       NOT NULL,
    title          VARCHAR(500) NOT NULL,
    source_path    VARCHAR(1000),
    file_type      VARCHAR(20),
    file_size      BIGINT,
    parse_status   VARCHAR(20)  DEFAULT 'PENDING',
    chunk_count    INT          DEFAULT 0,
    error_message  TEXT,
    metadata_json  TEXT        DEFAULT '{}',
    course_id      VARCHAR(64) DEFAULT 'DEFAULT',
    created_by     BIGINT       NOT NULL,
    deleted        BIGINT       DEFAULT 0,
    created_at     TIMESTAMP  DEFAULT now(),
    updated_at     TIMESTAMP  DEFAULT now()
);
CREATE INDEX idx_document_kb_id           ON document(kb_id) WHERE deleted = 0;
CREATE INDEX idx_document_parse_status    ON document(parse_status) WHERE deleted = 0;
CREATE INDEX idx_document_kb_type_status  ON document(kb_id, file_type, parse_status) WHERE deleted = 0;
CREATE INDEX idx_document_created_by      ON document(created_by) WHERE deleted = 0;
CREATE INDEX idx_document_course_id       ON document(course_id) WHERE deleted = 0;
CREATE INDEX idx_document_title_trgm      ON document USING gin(title gin_trgm_ops) WHERE deleted = 0;

-- 3. document_chunk ── 文档分片（核心表）
CREATE TABLE document_chunk (
    id                BIGINT       PRIMARY KEY,
    doc_id            BIGINT       NOT NULL,
    kb_id             BIGINT       NOT NULL,
    chunk_index       INT          NOT NULL,
    content           TEXT         NOT NULL,
    heading_path      VARCHAR(500),
    parent_title      VARCHAR(300),
    start_page        INT,
    end_page          INT,
    token_count       INT          DEFAULT 0,
    collection_type   VARCHAR(20)  NOT NULL DEFAULT 'TECHNICAL_QA',
    course_id         VARCHAR(64)  DEFAULT 'DEFAULT',
    metadata_json     TEXT        DEFAULT '{}',
    milvus_pk         VARCHAR(64),
    parent_chunk_id   BIGINT,
    prev_chunk_id     BIGINT,
    next_chunk_id     BIGINT,
    char_offset_start INT,
    char_offset_end   INT,
    correction_status VARCHAR(20)  DEFAULT 'PENDING',
    dense_vector      BYTEA,
    deleted           BIGINT       DEFAULT 0,
    created_at        TIMESTAMP  DEFAULT now(),
    updated_at        TIMESTAMP  DEFAULT now()
);
CREATE INDEX idx_document_chunk_doc_index    ON document_chunk(doc_id, chunk_index) WHERE deleted = 0;
CREATE INDEX idx_document_chunk_kb_id        ON document_chunk(kb_id) WHERE deleted = 0;
CREATE INDEX idx_document_chunk_type_kb       ON document_chunk(collection_type, kb_id) WHERE deleted = 0;
CREATE INDEX idx_document_chunk_course        ON document_chunk(course_id) WHERE course_id != 'DEFAULT' AND deleted = 0;
CREATE INDEX idx_document_chunk_course_type   ON document_chunk(course_id, collection_type) WHERE course_id != 'DEFAULT' AND deleted = 0;
CREATE INDEX idx_document_chunk_doc_type      ON document_chunk(doc_id, collection_type) WHERE deleted = 0;
CREATE INDEX idx_document_chunk_parent        ON document_chunk(parent_chunk_id) WHERE parent_chunk_id IS NOT NULL AND deleted = 0;
CREATE INDEX idx_document_chunk_prev           ON document_chunk(prev_chunk_id) WHERE prev_chunk_id IS NOT NULL AND deleted = 0;
CREATE INDEX idx_document_chunk_next           ON document_chunk(next_chunk_id) WHERE next_chunk_id IS NOT NULL AND deleted = 0;
CREATE INDEX idx_document_chunk_correction    ON document_chunk(correction_status, kb_id) WHERE deleted = 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- 二、用户体系（1 表）
-- ═══════════════════════════════════════════════════════════════════════════

-- 4. sys_user ── 系统用户（10 字段）
-- created_by：创建者用户 ID（超管/种子用户为 NULL）
CREATE TABLE sys_user (
    id            BIGINT       PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  DEFAULT 'ACTIVE',
    created_by    BIGINT,
    deleted       BIGINT       DEFAULT 0,
    created_at    TIMESTAMP  DEFAULT now(),
    updated_at    TIMESTAMP  DEFAULT now()
);
CREATE UNIQUE INDEX uniq_sys_user_username   ON sys_user(username) WHERE deleted = 0;
CREATE UNIQUE INDEX uniq_sys_user_super_admin ON sys_user((1)) WHERE role = 'SUPER_ADMIN' AND deleted = 0;
CREATE INDEX        idx_sys_user_role_status  ON sys_user(role, status) WHERE deleted = 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- 二点五、认证安全表 v5 新增（2 表）
-- ═══════════════════════════════════════════════════════════════════════════

-- 5. sys_login_record ── 登录记录（会话注册表）
CREATE TABLE sys_login_record (
    id          BIGINT       PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    jti_at      VARCHAR(64)  NOT NULL,
    jti_rt      VARCHAR(64)  NOT NULL,
    device_type VARCHAR(30)  NOT NULL,
    device_info VARCHAR(500),
    ip_address  VARCHAR(45),
    expires_at  TIMESTAMP  NOT NULL,
    status      VARCHAR(20)  DEFAULT 'ACTIVE',
    deleted     BIGINT       DEFAULT 0,
    created_at  TIMESTAMP  DEFAULT now(),
    updated_at  TIMESTAMP  DEFAULT now()
);
CREATE INDEX idx_login_record_jti_rt        ON sys_login_record(jti_rt) WHERE deleted = 0;
CREATE INDEX idx_login_record_user_device    ON sys_login_record(user_id, device_type, status) WHERE deleted = 0;
CREATE INDEX idx_login_record_user_status   ON sys_login_record(user_id, status) WHERE deleted = 0;

-- 6. sys_token_blacklist ── Token 黑名单
CREATE TABLE sys_token_blacklist (
    id             BIGINT       PRIMARY KEY,
    jti            VARCHAR(64)  NOT NULL,
    token_type     VARCHAR(10)  NOT NULL,
    user_id        BIGINT       NOT NULL,
    blacklisted_by BIGINT,
    reason         VARCHAR(200),
    expires_at     TIMESTAMP  NOT NULL,
    deleted        BIGINT       DEFAULT 0,
    created_at     TIMESTAMP  DEFAULT now()
);
CREATE UNIQUE INDEX uniq_token_blacklist_jti ON sys_token_blacklist(jti) WHERE deleted = 0;
CREATE INDEX        idx_token_blacklist_user ON sys_token_blacklist(user_id) WHERE deleted = 0;
CREATE INDEX        idx_token_blacklist_expires ON sys_token_blacklist(expires_at) WHERE deleted = 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- 三、课程业务表（5 表）
-- ═══════════════════════════════════════════════════════════════════════════

-- 7. course_info ── 课程信息（Header，一个课程一行）
CREATE TABLE course_info (
    id              BIGINT       PRIMARY KEY,
    title           VARCHAR(300) NOT NULL,
    description     VARCHAR(500),
    cover_image     VARCHAR(1000),
    category        VARCHAR(50),
    instructor_name VARCHAR(100),
    price           DECIMAL(10,2),
    duration        VARCHAR(50),
    tags            TEXT        DEFAULT '[]',
    rating          DECIMAL(2,1) DEFAULT 0,
    learning_count  INT          DEFAULT 0,
    enrollment_link VARCHAR(1000),
    status          VARCHAR(20)  DEFAULT 'ACTIVE',
    created_by      BIGINT       NOT NULL,
    deleted         BIGINT       DEFAULT 0,
    created_at      TIMESTAMP  DEFAULT now(),
    updated_at      TIMESTAMP  DEFAULT now()
);
CREATE INDEX idx_course_info_created_by    ON course_info(created_by) WHERE deleted = 0;
CREATE INDEX idx_course_info_category_status ON course_info(category, status) WHERE deleted = 0;
CREATE INDEX idx_course_info_status_rating  ON course_info(status, rating DESC) WHERE deleted = 0;
CREATE INDEX idx_course_info_status_created ON course_info(status, created_at DESC) WHERE deleted = 0;
CREATE INDEX idx_course_info_title_trgm     ON course_info USING gin(title gin_trgm_ops) WHERE deleted = 0;

-- 8. course_content ── 课程内容（Body，一个课程多行，按 Tab 分）
CREATE TABLE course_content (
    id          BIGINT       PRIMARY KEY,
    course_id   BIGINT       NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    content     TEXT         NOT NULL,
    sort_order  INT          DEFAULT 0,
    deleted     BIGINT       DEFAULT 0,
    created_at  TIMESTAMP  DEFAULT now(),
    updated_at  TIMESTAMP  DEFAULT now()
);
CREATE UNIQUE INDEX uniq_course_content_type ON course_content(course_id, content_type) WHERE deleted = 0;
CREATE INDEX        idx_course_content_course ON course_content(course_id, sort_order) WHERE deleted = 0;

-- 9. course_schedule ── 课程排期
CREATE TABLE course_schedule (
    id              BIGINT       PRIMARY KEY,
    course_id       BIGINT       NOT NULL,
    start_date      DATE         NOT NULL,
    end_date        DATE         NOT NULL,
    schedule_type   VARCHAR(20)  NOT NULL,
    location        VARCHAR(300),
    instructor_name VARCHAR(100),
    capacity        INT          DEFAULT 0,
    enrolled        INT          DEFAULT 0,
    status          VARCHAR(20)  DEFAULT 'UPCOMING',
    created_by      BIGINT       NOT NULL,
    deleted         BIGINT       DEFAULT 0,
    created_at      TIMESTAMP  DEFAULT now(),
    updated_at      TIMESTAMP  DEFAULT now()
);
CREATE INDEX idx_course_schedule_course_start ON course_schedule(course_id, start_date) WHERE deleted = 0;
CREATE INDEX idx_course_schedule_status_start  ON course_schedule(status, start_date) WHERE deleted = 0;
CREATE INDEX idx_course_schedule_created_by    ON course_schedule(created_by) WHERE deleted = 0;

-- 10. course_teacher ── 老师-课程关联（多对多）
CREATE TABLE course_teacher (
    id         BIGINT       PRIMARY KEY,
    course_id  BIGINT       NOT NULL,
    teacher_id BIGINT       NOT NULL,
    deleted    BIGINT       DEFAULT 0,
    created_at TIMESTAMP  DEFAULT now()
);
CREATE UNIQUE INDEX uniq_course_teacher       ON course_teacher(course_id, teacher_id) WHERE deleted = 0;
CREATE INDEX        idx_course_teacher_teacher_id ON course_teacher(teacher_id) WHERE deleted = 0;

-- 11. course_enrollment ── 学生选课关联（多对多）
CREATE TABLE course_enrollment (
    id         BIGINT       PRIMARY KEY,
    course_id  BIGINT       NOT NULL,
    student_id BIGINT       NOT NULL,
    enrolled_at TIMESTAMP DEFAULT now(),
    status     VARCHAR(20)  DEFAULT 'ACTIVE',
    deleted    BIGINT       DEFAULT 0
);
CREATE UNIQUE INDEX uniq_course_enrollment       ON course_enrollment(course_id, student_id) WHERE deleted = 0;
CREATE INDEX        idx_enrollment_student_status ON course_enrollment(student_id, status) WHERE deleted = 0;
CREATE INDEX        idx_enrollment_course_status  ON course_enrollment(course_id, status) WHERE deleted = 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- 四、C 端会话 & 对话（3 表）
-- ═══════════════════════════════════════════════════════════════════════════

-- 12. chat_session ── 会话
CREATE TABLE chat_session (
    id             BIGINT       PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    title          VARCHAR(300),
    status         VARCHAR(20)  DEFAULT 'ACTIVE',
    last_message_at TIMESTAMP,
    model          VARCHAR(50),
    deleted        BIGINT       DEFAULT 0,
    created_at     TIMESTAMP  DEFAULT now(),
    updated_at     TIMESTAMP  DEFAULT now()
);
CREATE INDEX idx_chat_session_user_last ON chat_session(user_id, last_message_at DESC) WHERE deleted = 0;

-- 13. chat_run ── Run 生命周期
CREATE TABLE chat_run (
    id            BIGINT       PRIMARY KEY,
    session_id    BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    status        VARCHAR(20)  DEFAULT 'QUEUED',
    model_calls   INT          DEFAULT 0,
    trace_id      VARCHAR(64),
    error_message TEXT,
    meta_json     TEXT        DEFAULT '{}',
    deleted       BIGINT       DEFAULT 0,
    started_at    TIMESTAMP,
    ended_at      TIMESTAMP,
    created_at    TIMESTAMP  DEFAULT now()
);
-- 并发守卫：同一 session 同时只能有一个 QUEUED 或 ACTIVE run
CREATE UNIQUE INDEX uniq_active_run_per_session ON chat_run(session_id) WHERE status IN ('QUEUED', 'ACTIVE') AND deleted = 0;
CREATE INDEX idx_chat_run_session_created ON chat_run(session_id, created_at DESC) WHERE deleted = 0;
CREATE INDEX idx_chat_run_user_status    ON chat_run(user_id, status) WHERE deleted = 0;
CREATE INDEX idx_chat_run_status_created  ON chat_run(status, created_at DESC) WHERE deleted = 0;

-- 14. chat_message ── 消息渲染表（与 SAA checkpoint 分离）
CREATE TABLE chat_message (
    id           BIGINT       PRIMARY KEY,
    session_id   BIGINT       NOT NULL,
    role         VARCHAR(20)  NOT NULL,
    content      TEXT         NOT NULL,
    intent_type  VARCHAR(20),
    sources_json TEXT        DEFAULT '[]',
    token_count  INT          DEFAULT 0,
    run_id       BIGINT       NOT NULL,
    seq          INT          NOT NULL,
    confidence   NUMERIC(3,2),
    trace_id     VARCHAR(64),
    message_type VARCHAR(20),
    deleted      BIGINT       DEFAULT 0,
    created_at   TIMESTAMP  DEFAULT now()
);
CREATE INDEX idx_chat_message_run_seq     ON chat_message(run_id, seq) WHERE deleted = 0;
CREATE INDEX idx_chat_message_session_seq  ON chat_message(session_id, seq) WHERE deleted = 0;
CREATE INDEX idx_chat_message_trace_id     ON chat_message(trace_id) WHERE trace_id IS NOT NULL AND deleted = 0;
CREATE INDEX idx_chat_message_run_type     ON chat_message(run_id, message_type) WHERE deleted = 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- 五、反馈（1 表）
-- ═══════════════════════════════════════════════════════════════════════════

-- 15. user_feedback ── 用户反馈
CREATE TABLE user_feedback (
    id         BIGINT       PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    session_id BIGINT       NOT NULL,
    message_id BIGINT       NOT NULL,
    is_liked   BOOLEAN,
    intent_type VARCHAR(20),
    deleted    BIGINT       DEFAULT 0,
    created_at TIMESTAMP  DEFAULT now()
);

-- 同一用户对同一消息只允许一条反馈（P0-2h：加 user_id 归属）
CREATE UNIQUE INDEX uniq_feedback_message      ON user_feedback(user_id, message_id) WHERE deleted = 0;
CREATE INDEX        idx_user_feedback_intent_liked ON user_feedback(intent_type, is_liked) WHERE deleted = 0;
CREATE INDEX        idx_user_feedback_session      ON user_feedback(session_id) WHERE deleted = 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- 超级管理员初始化（D4：设计文档要求应用首次启动时执行）
-- 凭证从配置文件或环境变量读取，此处使用默认密码的 BCrypt 哈希
-- 默认密码明文：admin123
-- 该密码仅在初次迁移（无人为覆盖配置）时生效，生产环境应通过配置覆盖
-- id=1 为硬编码迁移降级值（fallback），正常流程应通过 ApplicationRunner 动态注入
-- BCrypt("admin123") = $2a$10$4Tr8GR4XD98OTopP6/vK5eYsK8yRsRPOjdYzBgK9eahMJDo6KpL8.
-- ═══════════════════════════════════════════════════════════════════════════
INSERT INTO sys_user (id, username, password_hash, display_name, role, status)
SELECT 1, 'admin', '$2a$10$4Tr8GR4XD98OTopP6/vK5eYsK8yRsRPOjdYzBgK9eahMJDo6KpL8.',
       '系统管理员', 'SUPER_ADMIN', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_user WHERE role = 'SUPER_ADMIN' AND deleted = 0
);

-- ═══════════════════════════════════════════════════════════════════════════
-- 说明：SAA Checkpoint 表（checkpoints / checkpoint_writes / checkpoint_blobs）
-- 由 PostgreSqlSaver 自动 DDL，无需手写。
-- ═══════════════════════════════════════════════════════════════════════════
-- 验证：15 张表 + 55 个索引（sys_user 3 个索引，与设计文档一致）
-- SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';
-- SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname IS NOT NULL;
-- ═══════════════════════════════════════════════════════════════════════════
