package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.controller.vo.DocumentChunkVO;
import com.commerce.rag.entity.DocumentChunk;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DocumentChunkConverter 转换器测试 —— 实体到 VO 字段映射正确性 + 敏感字段不泄露 */
@DisplayName("DocumentChunkConverter 转换器测试")
class DocumentChunkConverterTest {

    private final DocumentChunkConverter converter = new DocumentChunkConverterImpl();

    @Test
    @DisplayName("实体全部业务字段完整映射到 VO")
    void toVO_mapsAllBusinessFields() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(10L);
        chunk.setKbId(2L);
        chunk.setChunkIndex(3);
        chunk.setContent("分片文本内容");
        chunk.setHeadingPath("第一章 > 1.1 概述");
        chunk.setParentTitle("第一章");
        chunk.setStartPage(1);
        chunk.setEndPage(2);
        chunk.setTokenCount(128);
        chunk.setCollectionType("TECHNICAL_QA");
        chunk.setCourseId("COURSE_1");
        chunk.setMetadataJson("{}");
        chunk.setMilvusPk("1");
        chunk.setParentChunkId(0L);
        chunk.setPrevChunkId(0L);
        chunk.setNextChunkId(2L);
        chunk.setCharOffsetStart(100);
        chunk.setCharOffsetEnd(250);
        chunk.setCorrectionStatus("PENDING");
        chunk.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        chunk.setUpdatedAt(LocalDateTime.of(2026, 8, 2, 11, 30));
        // 敏感字段：PG 冗余 embedding 字节（VO 应忽略）
        chunk.setDenseVector(new byte[] {1, 2, 3});

        DocumentChunkVO vo = converter.toVO(chunk);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.docId()).isEqualTo(10L);
        assertThat(vo.kbId()).isEqualTo(2L);
        assertThat(vo.chunkIndex()).isEqualTo(3);
        assertThat(vo.content()).isEqualTo("分片文本内容");
        assertThat(vo.headingPath()).isEqualTo("第一章 > 1.1 概述");
        assertThat(vo.parentTitle()).isEqualTo("第一章");
        assertThat(vo.startPage()).isEqualTo(1);
        assertThat(vo.endPage()).isEqualTo(2);
        assertThat(vo.tokenCount()).isEqualTo(128);
        assertThat(vo.collectionType()).isEqualTo("TECHNICAL_QA");
        assertThat(vo.courseId()).isEqualTo("COURSE_1");
        assertThat(vo.metadataJson()).isEqualTo("{}");
        assertThat(vo.milvusPk()).isEqualTo("1");
        assertThat(vo.parentChunkId()).isEqualTo(0L);
        assertThat(vo.prevChunkId()).isEqualTo(0L);
        assertThat(vo.nextChunkId()).isEqualTo(2L);
        assertThat(vo.charOffsetStart()).isEqualTo(100);
        assertThat(vo.charOffsetEnd()).isEqualTo(250);
        assertThat(vo.correctionStatus()).isEqualTo("PENDING");
        assertThat(vo.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
        assertThat(vo.updatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 2, 11, 30));
    }

    @Test
    @DisplayName("DocumentChunkVO 不含向量密文 denseVector 与逻辑删除标记 deleted（敏感字段不泄露）")
    void toVO_omitsDenseVectorAndDeleted() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDenseVector(new byte[] {9, 9, 9});
        chunk.setDeleted(0L);
        DocumentChunkVO vo = converter.toVO(chunk);

        // record 编译期已固定字段集合，此处断言字段集合无泄露访问器
        assertThat(vo).isNotNull();
        String[] componentNames = Arrays.stream(vo.getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);
        assertThat(componentNames).doesNotContain("denseVector", "deleted");
        // VO 字段集合与实体业务字段（剔除 denseVector/deleted）一一对应
        assertThat(componentNames)
                .containsExactlyInAnyOrder(
                        "id",
                        "docId",
                        "kbId",
                        "chunkIndex",
                        "content",
                        "headingPath",
                        "parentTitle",
                        "startPage",
                        "endPage",
                        "tokenCount",
                        "collectionType",
                        "courseId",
                        "metadataJson",
                        "milvusPk",
                        "parentChunkId",
                        "prevChunkId",
                        "nextChunkId",
                        "charOffsetStart",
                        "charOffsetEnd",
                        "correctionStatus",
                        "createdAt",
                        "updatedAt");
    }
}
