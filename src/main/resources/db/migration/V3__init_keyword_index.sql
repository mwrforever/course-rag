-- V3: 关键字检索索引
-- 为 document_chunk.content 创建 tsvector 列和 GIN 索引
-- 作为 hybrid search 的 sparse 通道（关键字检索）

-- 添加 tsvector 列，存储分词后的内容向量
ALTER TABLE document_chunk ADD COLUMN content_tsv tsvector;

COMMENT ON COLUMN document_chunk.content_tsv IS '内容的 tsvector 分词向量，用于关键字检索（hybrid search 的 sparse 通道）';

-- 创建 GIN 索引加速全文检索
CREATE INDEX idx_chunk_content_tsv ON document_chunk USING GIN(content_tsv);

-- 创建触发器函数：自动在 insert/update 时更新 tsvector 列
-- 使用 simple 配置（不依赖中文分词扩展），后续可升级为 zhparser/jieba
CREATE OR REPLACE FUNCTION update_content_tsv() RETURNS TRIGGER AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_update_content_tsv
    BEFORE INSERT OR UPDATE OF content ON document_chunk
    FOR EACH ROW
    EXECUTE FUNCTION update_content_tsv();

-- 回填已有数据的 tsvector
UPDATE document_chunk SET content_tsv = to_tsvector('simple', COALESCE(content, ''))
WHERE content_tsv IS NULL;
