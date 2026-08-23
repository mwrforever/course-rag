package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * DocumentChunkMapper.xml 投影规范性测试（P1-3，文件级断言）
 *
 * <p>背景：selectPageFilteredByTeacher（B 端教师分页，findPage/findPending 走此 SQL）
 * 原显式 SELECT 全列含 dense_vector（1024 维 float 约 4KB/行）——实体取回后转换器
 * 因 VO 无对应组件而丢弃，每页 20 条白白传输约 80KB 向量。
 * R2 复核修正：metadata_json 必须保留（DocumentChunkVO 第 13 位组件有消费），只去 dense_vector。
 *
 * <p>本类为文件级断言（无 Docker 环境）；SQL 真实执行结果由 DocumentChunkMapperXmlTest
 * （Testcontainers）覆盖。
 *
 * @author commerce-rag
 */
class DocumentChunkMapperXmlProjectionTest {

    /** DocumentChunkVO 全部 22 组件对应列（B 端分页投影的期望全集，不含 dense_vector） */
    private static final Set<String> VO_COLUMNS = Set.of(
                    "id",
                    "doc_id",
                    "kb_id",
                    "chunk_index",
                    "content",
                    "heading_path",
                    "parent_title",
                    "start_page",
                    "end_page",
                    "token_count",
                    "collection_type",
                    "course_id",
                    "metadata_json",
                    "milvus_pk",
                    "parent_chunk_id",
                    "prev_chunk_id",
                    "next_chunk_id",
                    "char_offset_start",
                    "char_offset_end",
                    "correction_status",
                    "created_at",
                    "updated_at")
            .stream()
            .collect(Collectors.toUnmodifiableSet());

    /** 提取 selectPageFilteredByTeacher 的 SELECT 列清单（SELECT 与 FROM 之间） */
    private String selectColumnList() throws IOException {
        Resource resource = new ClassPathResource("mapper/DocumentChunkMapper.xml");
        String xml;
        try (InputStream in = resource.getInputStream()) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int selectIdx = xml.indexOf("id=\"selectPageFilteredByTeacher\"");
        int selectStart = xml.indexOf("SELECT", selectIdx);
        int fromStart = xml.indexOf("FROM", selectStart);
        return xml.substring(selectStart + "SELECT".length(), fromStart);
    }

    @Test
    @DisplayName("P1-3 教师分页 XML 投影 — SELECT 列不含 dense_vector，且完整覆盖 VO 全部列（含 metadata_json）")
    void teacherPageSelect_omitsDenseVector_coversAllVoColumns() throws IOException {
        String columnList = selectColumnList();
        Set<String> columns = Arrays.stream(columnList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        // 只去 dense_vector（约 4KB/行，VO 不消费）；deleted 为软删标记列（BIGINT 8 字节）保留既有形态
        assertFalse(columns.contains("dense_vector"), "教师分页不应取回 dense_vector（每行约 4KB，VO 不消费）");
        // R2 修正：metadata_json 有 VO 消费，必须保留
        assertTrue(columns.contains("metadata_json"), "metadata_json 被 DocumentChunkVO 消费，投影必须保留");
        // 投影完整覆盖 VO 全部组件（缺列会导致 VO 对应字段恒 null）
        Set<String> expected = new HashSet<>(VO_COLUMNS);
        expected.add("deleted");
        assertEquals(expected, columns, "SELECT 列集应为 DocumentChunkVO 组件列 + deleted 软删标记列");
    }
}
