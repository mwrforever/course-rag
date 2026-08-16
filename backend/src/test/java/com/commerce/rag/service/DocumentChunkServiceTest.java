package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.convert.DocumentChunkConverter;
import com.commerce.rag.convert.DocumentChunkConverterImpl;
import com.commerce.rag.convert.StudentConverter;
import com.commerce.rag.convert.StudentConverterImpl;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.service.impl.DocumentChunkServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.ChunkBriefVO;
import com.commerce.rag.vo.ChunkContextVO;
import com.commerce.rag.vo.ChunkVO;
import com.commerce.rag.vo.DocumentChunkVO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IDocumentChunkService 单元测试 —— Mock DocumentChunkMapper + EtlPipeline
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

    /** 学生端转换器用真实实现（MapStruct 生成类），转换行为由 StudentConverterTest 单独覆盖 */
    @Spy
    private StudentConverter studentConverter = new StudentConverterImpl();

    @InjectMocks
    private DocumentChunkServiceImpl chunkService;

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

        BizException ex = assertThrows(
                BizException.class, () -> chunkService.updateCollectionType(1L, "COURSE_INFO", "55", 200L, false));
        assertEquals(403, ex.getCode());
        verify(etlPipeline, never()).syncChunkToMilvus(any());
    }

    // ==================== C 端查询方法（J2/J3/J4 数据源，VO 出参） ====================

    /** 构造测试用分片实体（覆盖 C 端视图对象业务字段） */
    private DocumentChunk chunk(Long id, String courseId) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setCourseId(courseId);
        c.setDocId(1L);
        c.setKbId(1L);
        c.setContent("内容-" + id);
        c.setHeadingPath("第一章");
        c.setChunkIndex(1);
        c.setParentTitle("小节");
        c.setStartPage(1);
        c.setEndPage(2);
        return c;
    }

    @Test
    @DisplayName("findContext(C端) → 主分片 + parent/prev/next 组装为上下文 VO")
    void findContext_returnsContextVO() {
        DocumentChunk current = chunk(2L, "10");
        current.setParentChunkId(1L);
        current.setPrevChunkId(100L);
        current.setNextChunkId(3L);
        when(chunkMapper.selectById(2L)).thenReturn(current);
        when(chunkMapper.selectById(1L)).thenReturn(chunk(1L, "10"));
        when(chunkMapper.selectById(100L)).thenReturn(chunk(100L, "10"));
        when(chunkMapper.selectById(3L)).thenReturn(chunk(3L, "10"));

        ChunkContextVO context = chunkService.findContext(2L);

        assertEquals(2L, context.id());
        assertEquals("10", context.courseId());
        assertEquals(1L, context.parentChunkId());
        assertEquals(1L, context.parent().id());
        assertEquals(100L, context.prev().id());
        assertEquals(3L, context.next().id());
    }

    @Test
    @DisplayName("findContext(C端) → 主分片不存在返回 null")
    void findContext_chunkNotFound_returnsNull() {
        when(chunkMapper.selectById(99L)).thenReturn(null);

        assertNull(chunkService.findContext(99L));
    }

    @Test
    @DisplayName("findContext(C端) → 相邻分片指针为空/缺失时对应字段为 null")
    void findContext_missingNeighbors_omitsFields() {
        DocumentChunk current = chunk(1L, "DEFAULT");
        current.setParentChunkId(100L);
        when(chunkMapper.selectById(1L)).thenReturn(current);
        when(chunkMapper.selectById(100L)).thenReturn(null);

        ChunkContextVO context = chunkService.findContext(1L);

        assertNull(context.parent());
        assertNull(context.prev());
        assertNull(context.next());
    }

    @Test
    @DisplayName("findContext(C端) → 主分片无任何相邻指针时相邻字段为 null")
    void findContext_noPointers_neighborsNull() {
        when(chunkMapper.selectById(1L)).thenReturn(chunk(1L, "DEFAULT"));

        ChunkContextVO context = chunkService.findContext(1L);

        assertEquals(1L, context.id());
        assertNull(context.parent());
        assertNull(context.prev());
        assertNull(context.next());
    }

    @Test
    @DisplayName("findByCourseIdAsVO → 按课程 ID 查询分片（chunk_index 升序，VO 出参）")
    void findByCourseIdAsVO_returnsChunks() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1L, "10"), chunk(2L, "10")));

        List<ChunkVO> result = chunkService.findByCourseIdAsVO(55L);

        assertEquals(2, result.size());
        ChunkVO vo = result.get(0);
        assertEquals(1L, vo.id());
        assertEquals("内容-1", vo.content());
        assertEquals("第一章", vo.headingPath());
        assertEquals(1, vo.chunkIndex());
        assertEquals("小节", vo.parentTitle());
        assertEquals(1, vo.startPage());
        assertEquals(2, vo.endPage());
        verify(chunkMapper).selectList(any());
    }

    @Test
    @DisplayName("findByCourseIdDefaultAsVO → 分页查询 DEFAULT 通用库（size<=0 用默认 20，records 转简略 VO）")
    void findByCourseIdDefaultAsVO_returnsPage() {
        Page<DocumentChunk> page = new Page<>(1, 20);
        page.setRecords(List.of(chunk(1L, "DEFAULT")));
        page.setTotal(1);
        when(chunkMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<ChunkBriefVO> result = chunkService.findByCourseIdDefaultAsVO(1, 0);

        assertEquals(1, result.getRecords().size());
        ChunkBriefVO vo = result.getRecords().get(0);
        assertEquals(1L, vo.id());
        assertEquals("内容-1", vo.content());
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getCurrent());
    }

    // ==================== A2-2 补测：findById / findPage / delete / findPending / 批量权限 ====================

    /** 构造测试用文档实体（归属字段） */
    private Document doc(Long id, Long createdBy, Long kbId) {
        Document d = new Document();
        d.setId(id);
        d.setCreatedBy(createdBy);
        d.setKbId(kbId);
        return d;
    }

    /** 构造测试用知识库实体（归属字段） */
    private KnowledgeBase kb(Long id, Long createdBy) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCreatedBy(createdBy);
        return kb;
    }

    @Test
    @DisplayName("findById 分片不存在 — 返回 null")
    void findById_chunkNotFound_returnsNull() {
        when(chunkMapper.selectById(999L)).thenReturn(null);

        assertNull(chunkService.findById(999L, 1L, "SUPER_ADMIN"));
    }

    @Test
    @DisplayName("findById TEACHER 且为文档属主 — 返回 VO")
    void findById_teacherDocOwner_returnsVO() {
        DocumentChunk chunk = chunk(1L, "10");
        chunk.setDocId(5L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(5L)).thenReturn(doc(5L, 100L, null));

        DocumentChunkVO vo = chunkService.findById(1L, 100L, "TEACHER");

        assertEquals(1L, vo.id());
        assertEquals("内容-1", vo.content());
    }

    @Test
    @DisplayName("findById TEACHER 非属主 — 抛 403")
    void findById_teacherNotOwner_throws403() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(5L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(5L)).thenReturn(doc(5L, 100L, null));

        BizException ex = assertThrows(BizException.class, () -> chunkService.findById(1L, 200L, "TEACHER"));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("findPage TEACHER — 走 XML 子查询分支（pendingOnly=false）")
    void findPage_teacherBranch_usesXmlSubquery() {
        Page<DocumentChunk> page = new Page<>(1, 20);
        page.setRecords(List.of(chunk(1L, "10")));
        page.setTotal(1);
        when(chunkMapper.selectPageFilteredByTeacher(any(), any(), any(), eq(false), any()))
                .thenReturn(page);

        IPage<DocumentChunkVO> result = chunkService.findPage(10L, 7L, 1, 20, 100L, "TEACHER");

        assertEquals(1, result.getRecords().size());
        assertEquals(1L, result.getRecords().get(0).id());
        assertEquals(1, result.getTotal());
        verify(chunkMapper).selectPageFilteredByTeacher(any(), eq(10L), eq(7L), eq(false), eq(100L));
    }

    @Test
    @DisplayName("findPage TEACHER 但 userId 为空 — 回落 wrapper 分支")
    void findPage_teacherNullUserId_fallsBackToWrapper() {
        Page<DocumentChunk> page = new Page<>(1, 10);
        page.setRecords(List.of(chunk(1L, "10")));
        when(chunkMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<DocumentChunkVO> result = chunkService.findPage(null, null, 1, 10, null, "TEACHER");

        assertEquals(1, result.getRecords().size());
        verify(chunkMapper).selectPage(any(Page.class), any());
        verify(chunkMapper, never()).selectPageFilteredByTeacher(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("findPage 非 TEACHER — docId/kbId 条件过滤 + size<=0 默认 20")
    void findPage_nonTeacher_withFilters() {
        Page<DocumentChunk> page = new Page<>(1, 20);
        page.setRecords(List.of(chunk(2L, "10")));
        page.setTotal(5);
        when(chunkMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<DocumentChunkVO> result = chunkService.findPage(10L, 7L, 1, 0, 100L, "SUPER_ADMIN");

        assertEquals(2L, result.getRecords().get(0).id());
        assertEquals(5, result.getTotal());
        assertEquals(20, result.getSize());
        verify(chunkMapper).selectPage(any(Page.class), any());
    }

    @Test
    @DisplayName("updateContent 文档属主（非超管）— 允许更新并重新向量化")
    void updateContent_docOwner_ok() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(5L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(5L)).thenReturn(doc(5L, 100L, null));
        when(chunkMapper.update(any(), any())).thenReturn(1);

        chunkService.updateContent(1L, "新内容", 100L, false);

        verify(chunkMapper).update(any(), any());
        verify(etlPipeline).reEmbedAndUpsert(1L);
    }

    @Test
    @DisplayName("updateContent 非属主 — 抛 403，不触发重新向量化")
    void updateContent_notOwner_throws403() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(5L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(5L)).thenReturn(doc(5L, 100L, null));

        BizException ex = assertThrows(BizException.class, () -> chunkService.updateContent(1L, "新内容", 200L, false));
        assertEquals(403, ex.getCode());
        verify(etlPipeline, never()).reEmbedAndUpsert(any());
    }

    @Test
    @DisplayName("delete 分片不存在 — 直接返回，不触发 Milvus 清理")
    void delete_chunkNotFound_noOp() {
        when(chunkMapper.selectById(999L)).thenReturn(null);

        chunkService.delete(999L, 1L, true);

        verify(etlPipeline, never()).deleteFromMilvusByChunkId(any());
        verify(chunkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("delete 非属主 — 抛 403，不触发 Milvus 清理")
    void delete_notOwner_throws403() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(5L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(5L)).thenReturn(doc(5L, 100L, null));

        BizException ex = assertThrows(BizException.class, () -> chunkService.delete(1L, 200L, false));
        assertEquals(403, ex.getCode());
        verify(etlPipeline, never()).deleteFromMilvusByChunkId(any());
    }

    @Test
    @DisplayName("updateCollectionType 分片不存在 — 抛 404")
    void updateCollectionType_chunkNotFound_throws404() {
        when(chunkMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(
                BizException.class, () -> chunkService.updateCollectionType(999L, "COURSE_INFO", "55", 100L, false));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("updateCollectionType 文档不存在 — 抛 404")
    void updateCollectionType_docNotFound_throws404() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(10L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(10L)).thenReturn(null);

        BizException ex = assertThrows(
                BizException.class, () -> chunkService.updateCollectionType(1L, "COURSE_INFO", "55", 100L, false));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("updateCollectionType 仅传 courseId — 跳过 collectionType 更新并同步 Milvus")
    void updateCollectionType_onlyCourseId_skipsCollectionType() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(10L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(10L)).thenReturn(doc(10L, 100L, 7L));
        when(chunkMapper.update(any(), any())).thenReturn(1);

        chunkService.updateCollectionType(1L, null, "COURSE_9", 100L, false);

        verify(chunkMapper).update(any(), any());
        verify(etlPipeline).syncChunkToMilvus(1L);
    }

    @Test
    @DisplayName("updateCollectionType 仅传 collectionType — 跳过 courseId 更新（超管旁路）")
    void updateCollectionType_onlyCollectionType_skipsCourseId() {
        when(chunkMapper.update(any(), any())).thenReturn(1);

        chunkService.updateCollectionType(1L, "COURSE_INFO", null, 100L, true);

        verify(chunkMapper).update(any(), any());
        verify(etlPipeline).syncChunkToMilvus(1L);
    }

    @Test
    @DisplayName("findContext(B端) 主分片不存在 — 抛 IllegalArgumentException")
    void findContext_bend_chunkNotFound_throws() {
        when(chunkMapper.selectById(999L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> chunkService.findContext(999L, 1L, "SUPER_ADMIN"));
    }

    @Test
    @DisplayName("findContext(B端) TEACHER 非属主 — 抛 403")
    void findContext_bend_teacherNotOwner_throws403() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(5L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(5L)).thenReturn(doc(5L, 100L, null));

        BizException ex = assertThrows(BizException.class, () -> chunkService.findContext(1L, 200L, "TEACHER"));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("findContext(B端) TEACHER 且为文档属主 — 允许查看")
    void findContext_bend_teacherOwner_ok() {
        DocumentChunk current = new DocumentChunk();
        current.setId(2L);
        current.setDocId(5L);
        when(chunkMapper.selectById(2L)).thenReturn(current);
        when(documentMapper.selectById(5L)).thenReturn(doc(5L, 100L, null));

        Map<String, DocumentChunkVO> context = chunkService.findContext(2L, 100L, "TEACHER");

        assertEquals(2L, context.get("current").id());
        assertNull(context.get("parent"));
        assertNull(context.get("prev"));
        assertNull(context.get("next"));
    }

    @Test
    @DisplayName("findContext(B端) 父分片存在但前后指针为空 — 相邻字段为 null")
    void findContext_bend_parentOnly_neighborsNull() {
        DocumentChunk current = new DocumentChunk();
        current.setId(2L);
        current.setParentChunkId(1L);
        when(chunkMapper.selectById(2L)).thenReturn(current);
        when(chunkMapper.selectById(1L)).thenReturn(chunk(1L, "10"));

        Map<String, DocumentChunkVO> context = chunkService.findContext(2L, 1L, "SUPER_ADMIN");

        assertEquals(2L, context.get("current").id());
        assertEquals(1L, context.get("parent").id());
        assertNull(context.get("prev"));
        assertNull(context.get("next"));
    }

    @Test
    @DisplayName("findContext(B端) 父分片缺失 — parent 为 null，prev/next 存在")
    void findContext_bend_parentMissing_prevNextPresent() {
        DocumentChunk current = new DocumentChunk();
        current.setId(2L);
        current.setParentChunkId(99L);
        current.setPrevChunkId(1L);
        current.setNextChunkId(3L);
        when(chunkMapper.selectById(2L)).thenReturn(current);
        when(chunkMapper.selectById(99L)).thenReturn(null);
        when(chunkMapper.selectById(1L)).thenReturn(chunk(1L, "10"));
        when(chunkMapper.selectById(3L)).thenReturn(chunk(3L, "10"));

        Map<String, DocumentChunkVO> context = chunkService.findContext(2L, 1L, "SUPER_ADMIN");

        assertNull(context.get("parent"));
        assertEquals(1L, context.get("prev").id());
        assertEquals(3L, context.get("next").id());
    }

    @Test
    @DisplayName("findByCourseIdDefaultAsVO 正数 size — 使用传入分页大小")
    void findByCourseIdDefaultAsVO_positiveSize() {
        Page<DocumentChunk> page = new Page<>(2, 10);
        page.setRecords(List.of(chunk(1L, "DEFAULT")));
        page.setTotal(11);
        when(chunkMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<ChunkBriefVO> result = chunkService.findByCourseIdDefaultAsVO(2, 10);

        assertEquals(2, result.getCurrent());
        assertEquals(10, result.getSize());
        assertEquals(1, result.getRecords().size());
    }

    @Test
    @DisplayName("batchUpdate 非超管且为文档属主 — 按 docId 去重后逐文档同步 Milvus")
    void batchUpdate_nonAdmin_owner_dedupesDocSync() {
        // 分片 1、2 同属文档 10（校验去重：文档 10 只同步一次），分片 3 属文档 20
        DocumentChunk c1 = chunk(1L, "10");
        c1.setDocId(10L);
        DocumentChunk c2 = chunk(2L, "10");
        c2.setDocId(10L);
        DocumentChunk c3 = chunk(3L, "10");
        c3.setDocId(20L);
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(c1, c2, c3));
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of(doc(10L, 100L, null), doc(20L, 100L, null)));
        when(chunkMapper.update(any(), any())).thenReturn(3);

        chunkService.batchUpdate(List.of(1L, 2L, 3L), "COURSE_INFO", "COURSE_123", 100L, false);

        verify(chunkMapper).update(any(), any());
        verify(etlPipeline).syncDocToMilvus(10L);
        verify(etlPipeline).syncDocToMilvus(20L);
        verify(etlPipeline, times(2)).syncDocToMilvus(any());
    }

    @Test
    @DisplayName("batchUpdate 知识库属主旁路 — 非文档属主但为 kb 属主可操作（courseId 为空分支）")
    void batchUpdate_kbOwner_ok() {
        DocumentChunk c = chunk(1L, "10");
        c.setDocId(10L);
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(c));
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of(doc(10L, 999L, 7L)));
        when(knowledgeBaseMapper.selectById(7L)).thenReturn(kb(7L, 100L));
        when(chunkMapper.update(any(), any())).thenReturn(1);

        chunkService.batchUpdate(List.of(1L), "COURSE_INFO", null, 100L, false);

        verify(chunkMapper).update(any(), any());
        verify(etlPipeline).syncDocToMilvus(10L);
    }

    @Test
    @DisplayName("batchUpdate 任一分片不存在（批量数量不匹配）— 抛 404 且不执行更新")
    void batchUpdate_chunkMissing_throws404() {
        // 请求 2 个 id，批量查询只返回 1 条 → 判定存在不存在的 id
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(chunk(1L, "10")));

        BizException ex = assertThrows(
                BizException.class,
                () -> chunkService.batchUpdate(List.of(1L, 2L), "COURSE_INFO", "COURSE_123", 100L, false));
        assertEquals(404, ex.getCode());
        verify(chunkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("batchUpdate 文档缺失 — 抛 404")
    void batchUpdate_docMissing_throws404() {
        DocumentChunk c = chunk(1L, "10");
        c.setDocId(10L);
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(c));
        // documentMapper.selectBatchIds 无 stub → Mockito 默认空列表，docMap 不含 docId=10

        BizException ex = assertThrows(
                BizException.class,
                () -> chunkService.batchUpdate(List.of(1L), "COURSE_INFO", "COURSE_123", 100L, false));
        assertEquals(404, ex.getCode());
        verify(chunkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("batchUpdate 非属主 — 抛 403，且不执行更新")
    void batchUpdate_notOwner_throws403() {
        DocumentChunk c = chunk(1L, "10");
        c.setDocId(10L);
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(c));
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of(doc(10L, 100L, null)));

        BizException ex = assertThrows(
                BizException.class,
                () -> chunkService.batchUpdate(List.of(1L), "COURSE_INFO", "COURSE_123", 200L, false));
        assertEquals(403, ex.getCode());
        verify(chunkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("batchUpdate Milvus 同步失败 — 异常向上传播")
    void batchUpdate_syncFailure_propagates() {
        DocumentChunk c = chunk(1L, "10");
        c.setDocId(10L);
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(c));
        when(chunkMapper.update(any(), any())).thenReturn(1);
        doThrow(new RuntimeException("Milvus 不可用")).when(etlPipeline).syncDocToMilvus(10L);

        assertThrows(
                RuntimeException.class,
                () -> chunkService.batchUpdate(List.of(1L), "COURSE_INFO", "COURSE_123", 1L, true));

        verify(etlPipeline).syncDocToMilvus(10L);
    }

    @Test
    @DisplayName("batchCorrected 空列表 — 不执行任何操作")
    void batchCorrected_emptyList_noOp() {
        chunkService.batchCorrected(List.of(), 1L, true);
        verify(chunkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("batchCorrected 非属主 — 抛 403")
    void batchCorrected_notOwner_throws403() {
        DocumentChunk c = chunk(1L, "10");
        c.setDocId(10L);
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(c));
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of(doc(10L, 100L, null)));

        BizException ex = assertThrows(BizException.class, () -> chunkService.batchCorrected(List.of(1L), 200L, false));
        assertEquals(403, ex.getCode());
        verify(chunkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("findPending 非 TEACHER — PENDING + kbId/docId 条件分页（size<=0 默认 20）")
    void findPending_nonTeacher_withFilters() {
        Page<DocumentChunk> page = new Page<>(1, 20);
        page.setRecords(List.of(chunk(1L, "10")));
        page.setTotal(3);
        when(chunkMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<DocumentChunkVO> result = chunkService.findPending(7L, 10L, 1, 0, 100L, "SUPER_ADMIN");

        assertEquals(1, result.getRecords().size());
        assertEquals(1L, result.getRecords().get(0).id());
        assertEquals(3, result.getTotal());
        assertEquals(20, result.getSize());
    }

    @Test
    @DisplayName("findPending 无筛选条件 — 仅 PENDING + chunk_index 排序")
    void findPending_noFilters() {
        Page<DocumentChunk> page = new Page<>(1, 20);
        page.setRecords(List.of());
        when(chunkMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<DocumentChunkVO> result = chunkService.findPending(null, null, 1, 20, 100L, "SUPER_ADMIN");

        assertTrue(result.getRecords().isEmpty());
        verify(chunkMapper).selectPage(any(Page.class), any());
    }

    @Test
    @DisplayName("findPending TEACHER — 走 XML 子查询分支（pendingOnly=true）")
    void findPending_teacherBranch() {
        Page<DocumentChunk> page = new Page<>(1, 20);
        page.setRecords(List.of(chunk(2L, "10")));
        page.setTotal(1);
        when(chunkMapper.selectPageFilteredByTeacher(any(), any(), any(), eq(true), any()))
                .thenReturn(page);

        IPage<DocumentChunkVO> result = chunkService.findPending(7L, null, 1, 20, 100L, "TEACHER");

        assertEquals(2L, result.getRecords().get(0).id());
        verify(chunkMapper).selectPageFilteredByTeacher(any(), any(), eq(7L), eq(true), eq(100L));
    }
}
