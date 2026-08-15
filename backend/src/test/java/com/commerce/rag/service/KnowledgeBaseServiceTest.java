package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commerce.rag.controller.vo.KnowledgeBaseVO;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * KnowledgeBaseService 单元测试 —— Mock Mapper + EtlPipeline
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMapper chunkMapper;

    @Mock
    private EtlPipeline etlPipeline;

    @Mock
    private MinioStorageService minioStorageService;

    /** 转换器用真实实现（MapStruct 生成类），转换行为由 KnowledgeBaseConverterTest 单独覆盖 */
    @Spy
    private KnowledgeBaseConverter knowledgeBaseConverter = new KnowledgeBaseConverterImpl();

    @InjectMocks
    private KnowledgeBaseService knowledgeBaseService;

    @Test
    @DisplayName("create 创建知识库")
    void create_insertsAndReturns() {
        when(knowledgeBaseMapper.insert(any(KnowledgeBase.class))).thenReturn(1);

        KnowledgeBaseVO result = knowledgeBaseService.create("测试知识库", "描述", 1L);

        assertNotNull(result);
        assertEquals("测试知识库", result.name());
        assertEquals("ACTIVE", result.status());
        assertEquals(1L, result.createdBy());
        verify(knowledgeBaseMapper).insert(any(KnowledgeBase.class));
    }

    @Test
    @DisplayName("delete 级联删除 — Milvus + MinIO 对象 + chunk + document + kb 全部调用")
    void delete_cascadeAll() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        // KB 下两个文档（source_path 待删）
        Document doc1 = new Document();
        doc1.setId(10L);
        doc1.setSourcePath("1/10.pdf");
        Document doc2 = new Document();
        doc2.setId(11L);
        doc2.setSourcePath("1/11.pdf");
        when(documentMapper.selectList(any())).thenReturn(List.of(doc1, doc2));

        knowledgeBaseService.delete(1L, 1L, true);

        // 验证 Milvus 清理
        verify(etlPipeline).deleteFromMilvusByKbId(1L);
        // 验证 MinIO 对象批量删除（P1-4 Bug 4 + perf P1-2：一次请求删全部对象，失败上抛阻断）
        verify(minioStorageService).deleteFiles(List.of("1/10.pdf", "1/11.pdf"));
        InOrder inOrder = inOrder(minioStorageService, documentMapper);
        inOrder.verify(minioStorageService).deleteFiles(anyList());
        inOrder.verify(documentMapper).update(any(), any());
        // 验证 chunk 软删 + kb 软删
        verify(chunkMapper).update(any(), any());
        verify(knowledgeBaseMapper).update(any(), any());
    }

    @Test
    @DisplayName("delete 权限校验 — 非创建者抛出 SecurityException")
    void delete_wrongUser_throwsSecurityException() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        assertThrows(ResponseStatusException.class, () -> knowledgeBaseService.delete(1L, 2L, false));
    }

    @Test
    @DisplayName("delete 知识库不存在 — 不执行任何操作")
    void delete_notFound_noOp() {
        when(knowledgeBaseMapper.selectById(999L)).thenReturn(null);

        knowledgeBaseService.delete(999L, 1L, true);

        verify(etlPipeline, never()).deleteFromMilvusByKbId(any());
        verify(knowledgeBaseMapper, never()).update(any(), any());
    }
}
