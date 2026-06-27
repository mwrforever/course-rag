-- V1: 知识库核心表结构
-- 包含知识库、文档、文档分块三张核心表

-- 知识库表：管理多个独立知识库
CREATE TABLE knowledge_base (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / ARCHIVED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  knowledge_base IS '知识库表，每个知识库对应一组相关文档集合';
COMMENT ON COLUMN knowledge_base.status IS '知识库状态：ACTIVE=活跃，ARCHIVED=已归档';

-- 文档表：记录上传的每个文档及其解析状态
CREATE TABLE document (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id         UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    title         VARCHAR(500) NOT NULL,
    source_path   VARCHAR(1000),
    file_type     VARCHAR(20) NOT NULL,  -- PDF / DOCX / PPTX / MD / TXT
    file_size     BIGINT DEFAULT 0,      -- 文件大小（字节）
    parse_status  VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    -- UPLOADED → PARSING → CHUNKING → EMBEDDING → INDEXING → COMPLETED / FAILED
    chunk_count   INT DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  document IS '文档表，记录每个文档的元信息和 ETL 处理状态';
COMMENT ON COLUMN document.parse_status IS 'ETL 状态机：UPLOADED→PARSING→CHUNKING→EMBEDDING→INDEXING→COMPLETED/FAILED';

CREATE INDEX idx_document_kb_id ON document(kb_id);
CREATE INDEX idx_document_parse_status ON document(parse_status);

-- 文档分块表：每个 chunk 对应一个向量（Milvus 中也有对应记录）
CREATE TABLE document_chunk (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id        UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    kb_id         UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    chunk_index   INT NOT NULL,              -- 在文档中的顺序（从 0 开始）
    content       TEXT NOT NULL,             -- 分块的原始文本内容
    heading_path  VARCHAR(500),              -- 标题层级路径，如 "第1章 > 1.2 节"
    parent_title  VARCHAR(300),              -- 所属章节标题
    start_page    INT DEFAULT 0,
    end_page      INT DEFAULT 0,
    token_count   INT DEFAULT 0,
    active_count  INT DEFAULT 0,             -- 访问热度计数，用于热度加权
    metadata_json JSONB DEFAULT '{}',        -- 扩展元数据
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  document_chunk IS '文档分块表，每个分块与 Milvus 中的一条向量记录对应';
COMMENT ON COLUMN document_chunk.active_count IS '被检索命中的次数，用于热度加权排序';

CREATE INDEX idx_chunk_doc_id ON document_chunk(doc_id);
CREATE INDEX idx_chunk_kb_id ON document_chunk(kb_id);
CREATE INDEX idx_chunk_doc_index ON document_chunk(doc_id, chunk_index);
