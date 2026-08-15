package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * DocumentChunkService 单元测试 —— Mock DocumentChunkMapper + EtlPipeline
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class DocumentChunkServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private DocumentChunkMapper chunkMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private EtlPipeline etlPipeline;

    /** 转换器用真实实现（MapStruct 生成类），转换行为由 DocumentChunkConverterTest 单独覆盖 */
    @Spy
    private DocumentChunkConverter chunkConverter = new DocumentChunkConverterImpl();

    @InjectMocks
    private DocumentChunkService chunkService;

    @Test
    @DisplayName("updateContent 更新内容 — 验证重新向量化被调用")
    void updateContent_triggersReEmbed() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setContent("旧内容");
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(chunkMapper.update(any(), any())).thenReturn(1);

        // 验证不抛异常
        assertDoesNotThrow(() -> chunkService.updateContent(1L, "新内容", 1L, true));

        // 验证 PG 更新
        verify(chunkMapper).update(any(), any());
        // 验证重新向量化被触发
        verify(etlPipeline).reEmbedAndUpsert(1L);
    }

    @Test
    @DisplayName("updateContent 分片不存在 — 抛出 IllegalArgumentException")
    void updateContent_notFound_throws() {
        when(chunkMapper.selectById(999L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> chunkService.updateContent(999L, "新内容", 1L, true));
        verify(etlPipeline, never()).reEmbedAndUpsert(any());
    }

    @Test
    @DisplayName("delete 删除分片 — 验证 Milvus 清理")
    void delete_cleansMilvus() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);

        chunkService.delete(1L, 1L, true);

        verify(etlPipeline).deleteFromMilvusByChunkId("1");
        verify(chunkMapper).update(any(), any());
    }

    @Test
    @DisplayName("batchUpdate 批量更新标量字段 — 不触发重新向量化")
    void batchUpdate_noReEmbed() {
        when(chunkMapper.update(any(), any())).thenReturn(2);

        chunkService.batchUpdate(List.of(1L, 2L), "COURSE_INFO", "COURSE_123", 1L, true);

        verify(chunkMapper).update(any(), any());
        verify(etlPipeline, never()).reEmbedAndUpsert(any());
    }

    @Test
    @DisplayName("batchCorrected 批量标记已修正")
    void batchCorrected_setsCorrected() {
        when(chunkMapper.update(any(), any())).thenReturn(3);

        chunkService.batchCorrected(List.of(1L, 2L, 3L), 1L, true);

        verify(chunkMapper).update(any(), any());
    }

    @Test
    @DisplayName("findContext 返回 prev/current/next")
    void findContext_returnsAllThree() {
        DocumentChunk current = new DocumentChunk();
        current.setId(2L);
        current.setPrevChunkId(1L);
        current.setNextChunkId(3L);

        DocumentChunk prev = new DocumentChunk();
        prev.setId(1L);

        DocumentChunk next = new DocumentChunk();
        next.setId(3L);

        when(chunkMapper.selectById(2L)).thenReturn(current);
        when(chunkMapper.selectById(1L)).thenReturn(prev);
        when(chunkMapper.selectById(3L)).thenReturn(next);

        var context = chunkService.findContext(2L, 1L, "SUPER_ADMIN");

        assertNotNull(context.get("current"));
        assertNotNull(context.get("prev"));
        assertNotNull(context.get("next"));
        assertEquals(2L, context.get("current").id());
        assertEquals(1L, context.get("prev").id());
        assertEquals(3L, context.get("next").id());
    }

    @Test
    @DisplayName("batchUpdate 空列表 — 不执行任何操作")
    void batchUpdate_emptyList_noOp() {
        chunkService.batchUpdate(List.of(), "COURSE_INFO", "COURSE_123", 1L, true);
        verify(chunkMapper, never()).update(any(), any());
    }

    // ==================== P2-1 知识库属主旁路 + P0-1 标注同步 ====================

    @Test
    @DisplayName("P2-1 updateCollectionType → 非文档属主但为知识库属主的教师可操作，且同步 Milvus（P0-1）")
    void updateCollectionType_kbOwnerTeacher_okAndSyncsMilvus() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(10L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        Document doc = new Document();
        doc.setId(10L);
        doc.setKbId(7L);
        doc.setCreatedBy(999L); // 超管代传
        when(documentMapper.selectById(10L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        kb.setCreatedBy(100L); // kb 属主 = 教师 100
        when(knowledgeBaseMapper.selectById(7L)).thenReturn(kb);
        when(chunkMapper.update(any(), any())).thenReturn(1);

        assertDoesNotThrow(() -> chunkService.updateCollectionType(1L, "COURSE_INFO", "55", 100L, false));
        // P0-1: 标注同步 Milvus（delete-then-insert 重建行）
        verify(etlPipeline).syncChunkToMilvus(1L);
    }

    @Test
    @DisplayName("P2-1 updateCollectionType → 非文档/知识库属主仍 403")
    void updateCollectionType_notOwner_throws403() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(10L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        Document doc = new Document();
        doc.setId(10L);
        doc.setKbId(7L);
        doc.setCreatedBy(100L);
        when(documentMapper.selectById(10L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(7L)).thenReturn(kb);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> chunkService.updateCollectionType(1L, "COURSE_INFO", "55", 200L, false));
        assertEquals(403, ex.getStatusCode().value());
        verify(etlPipeline, never()).syncChunkToMilvus(any());
    }

    // ==================== C 端查询方法（J2/J3 数据源） ====================

    @Test
    @DisplayName("findById(无参) → 直接按 ID 查询实体（C 端 J4 数据源）")
    void findById_noPermission_returnsChunk() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);

        DocumentChunk result = chunkService.findById(1L);

        assertEquals(1L, result.getId());
    }

    @Test
    @DisplayName("findByCourseId → 按课程 ID 查询分片（chunk_index 升序）")
    void findByCourseId_returnsChunks() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(new DocumentChunk()));

        List<DocumentChunk> result = chunkService.findByCourseId(55L);

        assertEquals(1, result.size());
        verify(chunkMapper).selectList(any());
    }

    @Test
    @DisplayName("findByCourseIdDefault → 分页查询 DEFAULT 通用库（size<=0 用默认 20）")
    void findByCourseIdDefault_returnsPage() {
        Page<DocumentChunk> page = new Page<>(1, 20);
        when(chunkMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<DocumentChunk> result = chunkService.findByCourseIdDefault(1, 0);

        assertSame(page, result);
    }
}
