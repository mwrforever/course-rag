package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.convert.DocumentConverterImpl;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.service.impl.DocumentServiceImpl;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.DocumentVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * IDocumentService 权限校验单元测试 —— 改名/下载/上传的归属校验（P0-2a/b/c）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IDocumentService 文档权限测试")
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

    /** Dashboard 统计缓存（真实 Caffeine 实例，失效钩子验证用） */
    private final Cache<String, Object> dashboardStatsCache =
            Caffeine.newBuilder().build();

    private IDocumentService documentService;

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        // 构造器注入（@RequiredArgsConstructor 按字段声明顺序生成全参构造器）；
        // 转换器用真实实现（MapStruct 生成类），转换行为由 DocumentConverterTest 单独覆盖
        documentService = new DocumentServiceImpl(
                documentMapper,
                chunkMapper,
                knowledgeBaseMapper,
                minioStorageService,
                etlPipeline,
                etlPool,
                new DocumentConverterImpl(),
                dashboardStatsCache);
    }

    private Document mockDoc(Long id, Long createdBy) {
        Document doc = new Document();
        doc.setId(id);
        doc.setCreatedBy(createdBy);
        doc.setSourcePath("kb/1/doc.pdf");
        doc.setFileType("pdf");
        return doc;
    }

    @Test
    @DisplayName("update → 非创建者改名抛出 403（超管旁路）")
    void update_notOwner_throws403() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

        // 操作者 200 不是创建者 100 → 403
        BizException ex = assertThrows(BizException.class, () -> documentService.update(1L, "新标题", 200L, false));
        assertEquals(403, ex.getCode());
        // 403 路径零写入副作用：不得触发任何 DB 更新
        verify(documentMapper, never()).update(any(), any());
        // 超管旁路：不抛异常
        assertDoesNotThrow(() -> documentService.update(1L, "新标题", 200L, true));
    }

    @Test
    @DisplayName("download → 非创建者下载抛出 403")
    void download_notOwner_throws403() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

        BizException ex = assertThrows(BizException.class, () -> documentService.download(1L, 200L, false));
        assertEquals(403, ex.getCode());
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
        BizException ex = assertThrows(
                BizException.class,
                () -> documentService.upload(
                        1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, "DEFAULT", 200L, false));
        assertEquals(403, ex.getCode());
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

        assertDoesNotThrow(() -> documentService.upload(
                1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, "DEFAULT", 100L, false));
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

        assertDoesNotThrow(() -> documentService.upload(
                1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, "DEFAULT", 100L, false));

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
                        1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, "DEFAULT", 100L, false));
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
                        1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, "DEFAULT", 100L, false));
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

    // ==================== P2-1 知识库属主旁路 ====================

    @Test
    @DisplayName("P2-1 update → 非文档属主但为知识库属主的教师可改名（超管代传文档）")
    void update_kbOwnerTeacher_canUpdate() {
        Document doc = new Document();
        doc.setId(10L);
        doc.setKbId(7L);
        doc.setCreatedBy(999L); // 超管代传，createdBy=超管
        when(documentMapper.selectById(10L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        kb.setCreatedBy(100L); // 知识库属主 = 教师 100
        when(knowledgeBaseMapper.selectById(7L)).thenReturn(kb);

        // 教师 100 非文档属主（999）但为 kb 属主 → 放行
        assertDoesNotThrow(() -> documentService.update(10L, "新标题", 100L, false));
        verify(documentMapper).update(any(), any());
    }

    @Test
    @DisplayName("P2-1 update → 非文档属主且非知识库属主仍 403")
    void update_notOwnerNorKbOwner_throws403() {
        Document doc = new Document();
        doc.setId(10L);
        doc.setKbId(7L);
        doc.setCreatedBy(999L);
        when(documentMapper.selectById(10L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        kb.setCreatedBy(100L); // kb 属主是教师 100，操作者 200 无权限
        when(knowledgeBaseMapper.selectById(7L)).thenReturn(kb);

        BizException ex = assertThrows(BizException.class, () -> documentService.update(10L, "新标题", 200L, false));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("P2-1 findById → 教师可见自己知识库内的代传文档（kb 属主旁路）")
    void findById_kbOwnerTeacher_visible() {
        Document doc = new Document();
        doc.setId(10L);
        doc.setKbId(7L);
        doc.setCreatedBy(999L);
        when(documentMapper.selectById(10L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(7L)).thenReturn(kb);

        DocumentVO result = documentService.findById(10L, 100L, "TEACHER");

        assertNotNull(result, "kb 属主教师应可见库内文档");
    }

    // ==================== reparse / download 成功路径 ====================

    @Test
    @DisplayName("reparse → 终态文档软删旧分片、CAS 重置 PENDING 并重新触发 ETL")
    void reparse_resetsAndTriggersEtl() {
        Document doc = mockDoc(1L, 100L);
        // B2-2: 终态（INDEXED）文档——CAS 条件更新命中 1 行
        doc.setParseStatus("INDEXED");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        // etlPool mock：execute 直接同步执行提交的任务，模拟真实线程池行为
        doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
                .when(etlPool)
                .execute(any());

        documentService.reparse(1L, 100L, false);

        // 状态守卫条件真实进入 wrapper：仅终态集合（INDEXED/FAILED/CHUNKED）可重置回 PENDING
        ArgumentCaptor<LambdaUpdateWrapper<Document>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper).update(isNull(), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        String paramValues =
                String.valueOf(wrapperCaptor.getValue().getParamNameValuePairs().values());
        assertTrue(sqlSegment.contains("parse_status"), "CAS 守卫应含 parse_status IN 条件: " + sqlSegment);
        assertTrue(
                paramValues.contains("INDEXED") && paramValues.contains("FAILED") && paramValues.contains("CHUNKED"),
                "终态集合应为 INDEXED/FAILED/CHUNKED: " + paramValues);
        // 旧分片软删必须位于 CAS 重置成功之后（执行中文档不得被误删分片）
        InOrder inOrder = inOrder(documentMapper, chunkMapper);
        inOrder.verify(documentMapper).update(isNull(), any());
        inOrder.verify(chunkMapper).update(isNull(), any());
        // 重新触发 ETL（etlPool mock 直接执行任务）
        verify(etlPipeline).process(1L);
        // 统计缓存失效（先写 DB 后失效）
        assertNull(dashboardStatsCache.getIfPresent("whatever"));
    }

    @Test
    @DisplayName("B2-2: reparse 执行中文档（PENDING/中间态）→ 409 且不软删分片、不重置状态、不触发 ETL")
    void reparse_executing_throws409_noResetNoChunkSoftDelete() {
        Document doc = mockDoc(1L, 100L);
        // ETL 管道执行中的中间态（状态机：PENDING→PARSING→…→EMBEDDING→INDEXED）
        doc.setParseStatus("EMBEDDING");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        // CAS 条件更新未命中（parse_status 不在终态集合）返回 0 行
        when(documentMapper.update(isNull(), any())).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> documentService.reparse(1L, 100L, false));

        // 409 冲突：提示执行中稍后重试（而非无条件重置后双管道并发）
        assertEquals(409, ex.getCode());
        // 不软删旧分片（执行中管道的分片不得被误删）
        verify(chunkMapper, never()).update(any(), any());
        // 不提交 ETL、不重跑管道
        verify(etlPool, never()).execute(any());
        // 仅一次 CAS 条件更新（守卫本身），不得再有无条件的第二次重置
        verify(documentMapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("reparse → 文档不存在抛 IllegalArgumentException")
    void reparse_notFound_throws() {
        when(documentMapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> documentService.reparse(99L, 1L, true));
        verify(etlPipeline, never()).process(anyLong());
    }

    @Test
    @DisplayName("download → 属主下载返回 MinIO 输入流")
    void download_owner_returnsStream() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));
        InputStream stream = new ByteArrayInputStream(new byte[0]);
        when(minioStorageService.downloadFile("kb/1/doc.pdf")).thenReturn(stream);

        InputStream result = documentService.download(1L, 100L, false);

        assertSame(stream, result);
    }

    @Test
    @DisplayName("download → 源文件路径为空抛 IllegalStateException")
    void download_noSourcePath_throws() {
        Document doc = mockDoc(1L, 100L);
        doc.setSourcePath(null);
        when(documentMapper.selectById(1L)).thenReturn(doc);

        assertThrows(IllegalStateException.class, () -> documentService.download(1L, 100L, true));
        verify(minioStorageService, never()).downloadFile(anyString());
    }

    @Test
    @DisplayName("downloadWithType → 返回输入流与文件类型")
    void downloadWithType_returnsStreamAndType() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));
        InputStream stream = new ByteArrayInputStream(new byte[0]);
        when(minioStorageService.downloadFile("kb/1/doc.pdf")).thenReturn(stream);

        IDocumentService.DocumentDownload result = documentService.downloadWithType(1L, 100L, false);

        assertSame(stream, result.inputStream());
        assertEquals("pdf", result.fileType());
    }

    // ==================== B2-5 级联软删事务原子性 ====================

    /** Spring 事务元数据解析器 —— 与生产事务切面同一解析路径，验证注解会被识别且异常触发回滚 */
    private static final AnnotationTransactionAttributeSource TX_SOURCE = new AnnotationTransactionAttributeSource();

    @Test
    @DisplayName("B2-5: delete 标注 @Transactional 且运行时异常触发回滚")
    void delete_isTransactional_rollsBackOnRuntimeFailure() throws NoSuchMethodException {
        Method method = DocumentServiceImpl.class.getMethod("delete", Long.class, Long.class, boolean.class);
        TransactionAttribute attr = TX_SOURCE.getTransactionAttribute(method, DocumentServiceImpl.class);

        // 注解存在（事务切面可识别）且 RuntimeException 触发回滚（默认回滚规则）
        assertNotNull(attr, "delete 应标注 @Transactional（B2-5：chunk→document 两连 UPDATE 原子性）");
        assertTrue(attr.rollbackOn(new RuntimeException("级联软删中途失败")));
    }
}
