package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.commerce.rag.test.IntegrationTestBase;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

/**
 * document_chunk 多模态列 schema 测试（Testcontainers 真实 PG + Flyway V6 迁移）
 *
 * <p>验证 S1 §12 直改 V6 后的三新列：content_type 默认 'text'、image_url/sha256 可写可读。
 *
 * @author commerce-rag
 */
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class DocumentChunkSchemaTest extends IntegrationTestBase {

    private final JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("多模态列存在 — content_type/image_url/sha256 可写可读")
    void multimodalColumns_writable() {
        long chunkId = 500001L;
        String hash = "a".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, doc_id, kb_id, chunk_index, content, content_type, image_url, sha256)"
                        + " VALUES (?, 1, 1, 0, '图片描述内容', 'image', '10/abc.png', ?)",
                chunkId,
                hash);
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT content_type, image_url, sha256 FROM document_chunk WHERE id = ?", chunkId);
            assertEquals("image", row.get("content_type"));
            assertEquals("10/abc.png", row.get("image_url"));
            assertEquals(hash, row.get("sha256"));
        } finally {
            jdbcTemplate.update("DELETE FROM document_chunk WHERE id = ?", chunkId);
        }
    }

    @Test
    @DisplayName("content_type 默认值 — 不显式赋值时为 text")
    void contentType_defaultsToText() {
        long chunkId = 500002L;
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, doc_id, kb_id, chunk_index, content) VALUES (?, 1, 1, 0, '正文内容')",
                chunkId);
        try {
            String type = jdbcTemplate.queryForObject(
                    "SELECT content_type FROM document_chunk WHERE id = ?", String.class, chunkId);
            assertEquals("text", type);
        } finally {
            jdbcTemplate.update("DELETE FROM document_chunk WHERE id = ?", chunkId);
        }
    }
}
