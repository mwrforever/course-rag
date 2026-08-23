package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * V14 迁移文件规范性测试 —— DEFAULT 课程分片查询索引（P1-1）
 *
 * <p>背景：V6 的 idx_document_chunk_course 为 partial 索引且谓词显式排除
 * course_id='DEFAULT'，J3 通用资料库分页（eq course_id='DEFAULT'）无法命中任何索引，
 * 只能全表扫 + 按 chunk_index 排序。V14 补一个不排除 DEFAULT 的复合部分索引，
 * 同时覆盖 J2/J3 的过滤与排序。
 *
 * <p>本类为文件级断言（无 Docker 环境）；索引在真实 PG 的存在性与可用性
 * 由 DocumentChunkSchemaTest（Testcontainers + Flyway）覆盖。
 *
 * @author commerce-rag
 */
class DocumentChunkDefaultCourseIdxMigrationTest {

    /** 读取 V14 迁移文件全文（src/main/resources 随主 classpath 进入测试） */
    private String readV14() throws IOException {
        Resource resource = new ClassPathResource("db/migration/V14__document_chunk_default_course_idx.sql");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 去掉 `--` 行注释，只保留 DDL 本体（谓词约束针对索引定义而非背景注释文本） */
    private String stripLineComments(String sql) {
        return Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("V14 迁移存在且创建 (course_id, chunk_index) 复合部分索引（谓词只含 deleted=0，不排除 DEFAULT）")
    void v14_createsCompositePartialIndex_withoutExcludingDefault() throws IOException {
        String sql = stripLineComments(readV14());

        // 索引落在 document_chunk，复合列为 (course_id, chunk_index)——覆盖 J2/J3 的过滤 + 排序
        assertTrue(
                sql.matches(
                        "(?s).*CREATE\\s+INDEX\\s+idx_document_chunk_course_default\\s+ON\\s+document_chunk\\s*\\(\\s*course_id\\s*,\\s*chunk_index\\s*\\).*"),
                "V14 应包含 CREATE INDEX idx_document_chunk_course_default ON document_chunk(course_id, chunk_index)");
        // 谓词只保留 deleted=0（软删行不入索引），不得出现排除 DEFAULT 的条件——否则 J3 仍无法命中
        assertTrue(sql.contains("WHERE deleted = 0"), "索引谓词应为 WHERE deleted = 0（与 @TableLogic 软删过滤一致）");
        assertFalse(sql.contains("course_id != 'DEFAULT'"), "新索引谓词不得排除 course_id='DEFAULT'——排除后 J3 通用资料库查询仍全表扫");
    }

    @Test
    @DisplayName("V14 索引名与 V6 既有索引不冲突")
    void v14_indexName_distinctFromV6() throws IOException {
        String v14 = readV14();
        Resource v6 = new ClassPathResource("db/migration/V6__full_schema_v5.sql");
        String v6Sql;
        try (InputStream in = v6.getInputStream()) {
            v6Sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Flyway 按版本号顺序执行，V14 的索引名若与 V6 撞名会导致迁移失败（PG 同表索引名必须唯一）
        assertFalse(v6Sql.contains("idx_document_chunk_course_default"), "索引名不得与 V6 既有索引重复");
        assertEquals(1, v14.split("idx_document_chunk_course_default", -1).length - 1, "V14 内索引名应只出现一次");
    }
}
