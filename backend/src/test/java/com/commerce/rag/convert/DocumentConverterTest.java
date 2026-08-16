package com.commerce.rag.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.Document;
import com.commerce.rag.vo.DocumentVO;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DocumentConverter 转换器测试 —— 实体到 VO 字段映射正确性 + 敏感字段不泄露 */
@DisplayName("DocumentConverter 转换器测试")
class DocumentConverterTest {

    private final DocumentConverter converter = new DocumentConverterImpl();

    @Test
    @DisplayName("实体全部业务字段完整映射到 VO")
    void toVO_mapsAllBusinessFields() {
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(2L);
        doc.setTitle("RAG 课程资料");
        doc.setFileType("pdf");
        doc.setFileSize(1024L);
        doc.setParseStatus("INDEXED");
        doc.setChunkCount(5);
        doc.setErrorMessage(null);
        doc.setMetadataJson("{\"author\":\"张三\"}");
        doc.setCourseId("COURSE_1");
        doc.setCreatedBy(100L);
        doc.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        doc.setUpdatedAt(LocalDateTime.of(2026, 8, 2, 11, 30));
        // 敏感字段：源文件路径（VO 应忽略）
        doc.setSourcePath("2/9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c.pdf");

        DocumentVO vo = converter.toVO(doc);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.kbId()).isEqualTo(2L);
        assertThat(vo.title()).isEqualTo("RAG 课程资料");
        assertThat(vo.fileType()).isEqualTo("pdf");
        assertThat(vo.fileSize()).isEqualTo(1024L);
        assertThat(vo.parseStatus()).isEqualTo("INDEXED");
        assertThat(vo.chunkCount()).isEqualTo(5);
        assertThat(vo.errorMessage()).isNull();
        assertThat(vo.metadataJson()).isEqualTo("{\"author\":\"张三\"}");
        assertThat(vo.courseId()).isEqualTo("COURSE_1");
        assertThat(vo.createdBy()).isEqualTo(100L);
        assertThat(vo.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
        assertThat(vo.updatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 2, 11, 30));
    }

    @Test
    @DisplayName("DocumentVO 不含内部路径 sourcePath 与逻辑删除标记 deleted（敏感字段不泄露）")
    void toVO_omitsSourcePathAndDeleted() {
        Document doc = new Document();
        doc.setSourcePath("/minio/internal/secret.pdf");
        doc.setDeleted(0L);
        DocumentVO vo = converter.toVO(doc);

        // record 编译期已固定字段集合，此处断言字段集合无泄露访问器
        assertThat(vo).isNotNull();
        String[] componentNames = Arrays.stream(vo.getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);
        assertThat(componentNames).doesNotContain("sourcePath", "deleted");
        // VO 字段集合与实体业务字段（剔除 sourcePath/deleted）一一对应
        assertThat(componentNames)
                .containsExactlyInAnyOrder(
                        "id",
                        "kbId",
                        "title",
                        "fileType",
                        "fileSize",
                        "parseStatus",
                        "chunkCount",
                        "errorMessage",
                        "metadataJson",
                        "courseId",
                        "createdBy",
                        "createdAt",
                        "updatedAt");
    }
}
