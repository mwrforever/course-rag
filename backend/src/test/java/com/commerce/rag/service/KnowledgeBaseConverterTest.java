package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.vo.KnowledgeBaseVO;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KnowledgeBaseConverter 转换器测试 —— 实体到 VO 字段映射正确性 + 敏感字段不泄露 */
@DisplayName("KnowledgeBaseConverter 转换器测试")
class KnowledgeBaseConverterTest {

    private final KnowledgeBaseConverter converter = new KnowledgeBaseConverterImpl();

    @Test
    @DisplayName("实体全部业务字段完整映射到 VO")
    void toVO_mapsAllBusinessFields() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setName("RAG 课程知识库");
        kb.setDescription("课程资料集合");
        kb.setStatus("ACTIVE");
        kb.setCreatedBy(100L);
        kb.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        kb.setUpdatedAt(LocalDateTime.of(2026, 8, 2, 11, 30));

        KnowledgeBaseVO vo = converter.toVO(kb);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.name()).isEqualTo("RAG 课程知识库");
        assertThat(vo.description()).isEqualTo("课程资料集合");
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.createdBy()).isEqualTo(100L);
        assertThat(vo.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
        assertThat(vo.updatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 2, 11, 30));
    }

    @Test
    @DisplayName("KnowledgeBaseVO 不含逻辑删除标记 deleted（内部字段不泄露）")
    void toVO_omitsDeleted() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setDeleted(0L);
        KnowledgeBaseVO vo = converter.toVO(kb);

        // record 编译期已固定字段集合，此处断言字段集合无泄露访问器
        assertThat(vo).isNotNull();
        String[] componentNames = Arrays.stream(vo.getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);
        assertThat(componentNames).doesNotContain("deleted");
        // VO 字段集合与实体业务字段（剔除 deleted）一一对应
        assertThat(componentNames)
                .containsExactlyInAnyOrder(
                        "id", "name", "description", "status", "createdBy", "createdAt", "updatedAt");
    }
}
