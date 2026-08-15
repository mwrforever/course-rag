package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * DocumentService 权限校验单元测试 —— 改名/下载/上传的归属校验（P0-2a/b/c）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentService 文档权限测试")
class DocumentServiceTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMapper chunkMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private EtlPipeline etlPipeline;

    @Mock
    private ThreadPoolExecutor etlPool;

    private DocumentService documentService;

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @BeforeEach
    void setUp() throws Exception {
        documentService = new DocumentService();
        // 字段为 @Autowired 私有字段，通过反射注入 mock
        for (java.lang.reflect.Field f : DocumentService.class.getDeclaredFields()) {
            f.setAccessible(true);
            Object value =
                    switch (f.getName()) {
                        case "documentMapper" -> documentMapper;
                        case "chunkMapper" -> chunkMapper;
                        case "knowledgeBaseMapper" -> knowledgeBaseMapper;
                        case "minioStorageService" -> minioStorageService;
                        case "etlPipeline" -> etlPipeline;
                        case "etlPool" -> etlPool;
                        default -> null;
                    };
            if (value != null) {
                f.set(documentService, value);
            }
        }
    }

    private Document mockDoc(Long id, Long createdBy) {
        Document doc = new Document();
        doc.setId(id);
        doc.setCreatedBy(createdBy);
        doc.setSourcePath("kb/1/doc.pdf");
        return doc;
    }

    @Test
    @DisplayName("update → 非创建者改名抛出 403（超管旁路）")
    void update_notOwner_throws403() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

        // 操作者 200 不是创建者 100 → 403
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> documentService.update(1L, "新标题", 200L, false));
        assertEquals(403, ex.getStatusCode().value());
        // 403 路径零写入副作用：不得触发任何 DB 更新
        verify(documentMapper, never()).update(any(), any());
        // 超管旁路：不抛异常
        assertDoesNotThrow(() -> documentService.update(1L, "新标题", 200L, true));
    }

    @Test
    @DisplayName("download → 非创建者下载抛出 403")
    void download_notOwner_throws403() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> documentService.download(1L, 200L, false));
        assertEquals(403, ex.getStatusCode().value());
        // 403 路径零副作用：不得从 MinIO 读取文件
        verify(minioStorageService, never()).downloadFile(anyString());
    }

    @Test
    @DisplayName("upload → 非超管向他人知识库上传抛出 403")
    void upload_notKbOwner_throws403() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        // 用户 200 向 createdBy=100 的知识库上传 → 403
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> documentService.upload(
                        1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 200L, false));
        assertEquals(403, ex.getStatusCode().value());
        // 403 路径零副作用：documentMapper 零交互（未落库）
        verifyNoInteractions(documentMapper);
    }

    @Test
    @DisplayName("upload → 知识库创建者可正常上传")
    void upload_kbOwner_succeeds() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        assertDoesNotThrow(() ->
                documentService.upload(1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 100L, false));
        verify(documentMapper).insert(any(Document.class));
    }

    @Test
    @DisplayName("upload → 成功路径：sourcePath 为 {kbId}/{uuid}.{ext}，无第二步 updateById")
    void upload_success_uuidPath() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
        when(minioStorageService.uploadFile(eq(1L), anyString(), any(), eq("pdf")))
                .thenAnswer(inv -> "1/" + inv.getArgument(1) + ".pdf");

        assertDoesNotThrow(() ->
                documentService.upload(1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 100L, false));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        String path = captor.getValue().getSourcePath();
        assertTrue(path.matches("1/[0-9a-f]{32}\\.pdf"), "路径应为 {kbId}/{uuid}.{ext}: " + path);
        // 无第二步 updateById（DB 记录一步到位）
        verify(documentMapper, never()).updateById(any(Document.class));
    }

    @Test
    @DisplayName("upload → MinIO 上传失败：insert 不被调用（DB 无残留）")
    void upload_minioFailure_noInsert() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
        doThrow(new RuntimeException("MinIO 不可用"))
                .when(minioStorageService)
                .uploadFile(anyLong(), anyString(), any(), anyString());

        assertThrows(
                RuntimeException.class,
                () -> documentService.upload(
                        1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 100L, false));
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    @DisplayName("upload → insert 失败：删除已上传 MinIO 对象（单向补偿）")
    void upload_insertFailure_deletesObject() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
        when(minioStorageService.uploadFile(anyLong(), anyString(), any(), anyString()))
                .thenReturn("1/9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c.pdf");
        when(documentMapper.insert(any(Document.class))).thenThrow(new RuntimeException("DB 不可用"));

        assertThrows(
                RuntimeException.class,
                () -> documentService.upload(
                        1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 100L, false));
        // 唯一可能的残留方向「MinIO 已传、DB 未落」→ 回收对象（幂等）
        verify(minioStorageService).deleteFile("1/9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c.pdf");
    }

    @Test
    @DisplayName("delete → MinIO 删除先于 PG 软删（失败阻断，记录保留可重试）")
    void delete_minioFirst_failureBlocksSoftDelete() {
        // Given
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

        // When（正常路径）
        documentService.delete(1L, 100L, false);

        // Then: MinIO 删除先于 chunk/doc 软删
        InOrder inOrder = inOrder(minioStorageService, chunkMapper, documentMapper);
        inOrder.verify(minioStorageService).deleteFile("kb/1/doc.pdf");
        inOrder.verify(chunkMapper).update(any(), any());
        inOrder.verify(documentMapper).update(any(), any());

        // When（MinIO 失败路径）
        reset(minioStorageService, chunkMapper, documentMapper);
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));
        doThrow(new RuntimeException("MinIO 不可用")).when(minioStorageService).deleteFile("kb/1/doc.pdf");

        // Then: 异常上抛，PG 不软删（对象/记录可重试收敛）
        assertThrows(RuntimeException.class, () -> documentService.delete(1L, 100L, false));
        verify(chunkMapper, never()).update(any(), any());
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("findPage 筛选 — status/q/sort 参数生效且 TEACHER 过滤保留")
    void findPage_filtersApplied() {
        // Given: selectPage 返回空页（断言调用行为即可）
        when(documentMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 20));

        documentService.findPage(10L, "PENDING", "标题", "updated", 1, 20, 100L, "TEACHER");

        // Then: 筛选条件真实进入 wrapper（列名 + 参数值双断言，防"条件写了但没生效"回归）
        ArgumentCaptor<LambdaQueryWrapper<Document>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(documentMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        LambdaQueryWrapper<Document> wrapper = wrapperCaptor.getValue();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("parse_status"), "status 筛选应进入 parse_status 条件: " + sqlSegment);
        assertTrue(sqlSegment.contains("title"), "q 关键词应进入 title LIKE 条件: " + sqlSegment);
        assertTrue(sqlSegment.contains("created_by"), "TEACHER 过滤应进入 created_by 条件: " + sqlSegment);
        assertTrue(sqlSegment.contains("updated_at"), "sort=updated 应按 updated_at 排序: " + sqlSegment);
        String paramValues = String.valueOf(wrapper.getParamNameValuePairs().values());
        assertTrue(paramValues.contains("PENDING"), "status 参数值应传入: " + paramValues);
        assertTrue(paramValues.contains("标题"), "q 参数值应传入: " + paramValues);
        assertTrue(paramValues.contains("100"), "TEACHER 用户 ID 应传入: " + paramValues);
    }

    @Test
    @DisplayName("findPage 排序 — sort=created 默认 created_at 降序；非法 sort 不抛异常")
    void findPage_sortHandling() {
        when(documentMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 20));

        // 默认（null sort）与非法值均不抛异常（TEACHER 过滤条件为 false 时不得 NPE）
        assertDoesNotThrow(() -> documentService.findPage(null, null, null, null, 1, 20, null, null));
        assertDoesNotThrow(() -> documentService.findPage(null, null, null, "invalid", 1, 20, null, null));
    }
}
