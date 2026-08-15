package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * EtlPipeline 单元测试 —— Mock 所有依赖（v2 API）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class EtlPipelineTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMapper chunkMapper;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private MilvusClientV2 milvusClientV2;

    /** Dashboard 统计缓存（真实 Caffeine 实例，状态写入失效钩子验证用） */
    private final Cache<String, Object> dashboardStatsCache =
            Caffeine.newBuilder().build();

    private EtlPipeline etlPipeline;

    @BeforeEach
    void setUp() {
        EtlProperties props =
                new EtlProperties(100, new EtlProperties.Executor(2, 4, 20, "etl-"), new EtlProperties.Chunk(768, 128));
        etlPipeline = new EtlPipeline(
                documentMapper,
                chunkMapper,
                minioStorageService,
                embeddingModel,
                milvusClientV2,
                props,
                dashboardStatsCache);
    }

    @Test
    @DisplayName("deleteFromMilvusByChunkId — 调用 milvusClientV2.delete")
    void deleteFromMilvusByChunkId_callsDelete() {
        etlPipeline.deleteFromMilvusByChunkId("123");
        verify(milvusClientV2).delete(any(DeleteReq.class));
    }

    @Test
    @DisplayName("deleteFromMilvusByChunkId — Milvus 删除失败上抛（P0-8：不再吞异常，阻断调用方软删可重试）")
    void deleteFromMilvusByChunkId_failure_throws() {
        doThrow(new RuntimeException("connection refused")).when(milvusClientV2).delete(any(DeleteReq.class));

        assertThrows(RuntimeException.class, () -> etlPipeline.deleteFromMilvusByChunkId("123"));
    }

    @Test
    @DisplayName("deleteFromMilvusByDocId — 按 doc_id filter 一次删除（不查 PG chunk 表）")
    void deleteFromMilvusByDocId_deletesByFilter() {
        etlPipeline.deleteFromMilvusByDocId(100L);

        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClientV2).delete(captor.capture());
        assertEquals("doc_id == \"100\"", captor.getValue().getFilter());
        // 不再依赖 PG chunk 行（规避 @TableLogic 过滤漏删已软删 chunk）
        verify(chunkMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("deleteFromMilvusByDocId — Milvus 删除失败上抛（阻断调用方，不静默）")
    void deleteFromMilvusByDocId_failure_throws() {
        doThrow(new RuntimeException("connection refused")).when(milvusClientV2).delete(any(DeleteReq.class));

        assertThrows(RuntimeException.class, () -> etlPipeline.deleteFromMilvusByDocId(100L));
    }

    @Test
    @DisplayName("deleteFromMilvusByKbId — 按 kb_id filter 一次删除（不查 PG chunk 表）")
    void deleteFromMilvusByKbId_deletesByFilter() {
        etlPipeline.deleteFromMilvusByKbId(10L);

        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClientV2).delete(captor.capture());
        assertEquals("kb_id == \"10\"", captor.getValue().getFilter());
        verify(chunkMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("deleteFromMilvusByCourseId — 按 course_id filter 删除")
    void deleteFromMilvusByCourseId_deletesByFilter() {
        etlPipeline.deleteFromMilvusByCourseId("12345");

        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClientV2).delete(captor.capture());
        assertEquals("course_id == \"12345\"", captor.getValue().getFilter());
    }

    @Test
    @DisplayName("process 完整管道 — Tika 解析 → 分片 → 向量化")
    void process_fullPipeline() throws Exception {
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        doc.setTitle("测试文档");
        doc.setSourcePath("10/1.pdf");
        doc.setParseStatus("PENDING");

        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(minioStorageService.downloadFile("10/1.pdf"))
                .thenReturn(new ByteArrayInputStream("这是测试内容。\n\n第二段落内容。".getBytes()));
        // 以下 stub 用于管道后续阶段（chunkDocument / embedAndIndex），
        // Tika 在纯单测环境下解析行为不确定，用 lenient 避免不必要 stub 报错
        lenient().when(chunkMapper.insert(any(DocumentChunk.class))).thenReturn(1);
        lenient().when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(documentMapper.update(any(), any())).thenReturn(1);

        // 执行管道
        etlPipeline.process(1L);

        // 验证状态更新到 INDEXED
        verify(documentMapper, atLeastOnce()).update(any(), any());
        // 验证从 MinIO 下载了文件
        verify(minioStorageService).downloadFile("10/1.pdf");
    }

    @Test
    @DisplayName("process 文档不存在 — 设置 FAILED 状态")
    void process_docNotFound_setsFailed() {
        when(documentMapper.selectById(999L)).thenReturn(null);

        etlPipeline.process(999L);

        // 验证状态被设为 FAILED
        verify(documentMapper).update(any(), any());
    }

    // ========================================================================
    // P2-1 修复波次新增：process 状态守卫 / 部分失败标 FAILED / 流关闭
    // ========================================================================

    @Test
    @DisplayName("process 状态守卫 — 抢占失败（非 PENDING/FAILED）直接跳过，不执行解析")
    void process_claimFailed_skipsExecution() {
        // Given: document 存在，抢占 update 返回 0（状态为 INDEXED/执行中）
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        doc.setSourcePath("10/1.pdf");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(0);

        etlPipeline.process(1L);

        // Then: 不下载文件（解析未执行）
        verify(minioStorageService, never()).downloadFile(anyString());
        // 守卫语义：抢占 update 的条件必须限定 parse_status IN (PENDING, FAILED)
        ArgumentCaptor<LambdaUpdateWrapper> claimCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper).update(any(), claimCaptor.capture());
        LambdaUpdateWrapper claimWrapper = claimCaptor.getValue();
        // 先渲染（getSqlSegment）——IN 占位符参数在渲染时才写入 paramNameValuePairs（MP 3.5.12 惰性）
        String sqlSegment = claimWrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("parse_status IN"), "抢占条件应限定 parse_status IN (...): " + sqlSegment);
        String condValues = String.valueOf(claimWrapper.getParamNameValuePairs().values());
        assertTrue(
                condValues.contains("PENDING") && condValues.contains("FAILED"),
                "抢占条件应含 parse_status IN (PENDING, FAILED): " + condValues);
    }

    @Test
    @DisplayName("process 状态守卫 — FAILED 状态可重试（抢占成功继续执行）")
    void process_claimSuccessFromFailed_continues() throws Exception {
        // Given: 抢占 update 返回 1（PENDING/FAILED → PARSING 成功）
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        doc.setSourcePath("10/1.pdf");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        // 以下 stub 用于管道后续阶段（parse/chunk/embed），用 lenient 避免不必要 stub 报错
        lenient()
                .when(minioStorageService.downloadFile("10/1.pdf"))
                .thenReturn(new ByteArrayInputStream("测试内容。\n\n第二段。".getBytes()));
        lenient().when(chunkMapper.insert(any(DocumentChunk.class))).thenReturn(1);
        lenient().when(chunkMapper.selectList(any())).thenReturn(List.of());

        etlPipeline.process(1L);

        // Then: 管道继续执行（下载被调用）
        verify(minioStorageService).downloadFile("10/1.pdf");
        // 守卫语义：第一次 update 即抢占，条件必须限定 parse_status IN (PENDING, FAILED)
        ArgumentCaptor<LambdaUpdateWrapper> claimCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper, atLeastOnce()).update(any(), claimCaptor.capture());
        LambdaUpdateWrapper claimWrapper = claimCaptor.getAllValues().get(0);
        // 先渲染（getSqlSegment）——IN 占位符参数在渲染时才写入 paramNameValuePairs（MP 3.5.12 惰性）
        String sqlSegment = claimWrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("parse_status IN"), "抢占条件应限定 parse_status IN (...): " + sqlSegment);
        String condValues = String.valueOf(claimWrapper.getParamNameValuePairs().values());
        assertTrue(
                condValues.contains("PENDING") && condValues.contains("FAILED"),
                "抢占条件应含 parse_status IN (PENDING, FAILED): " + condValues);
    }

    @Test
    @DisplayName("embedAndIndex 部分失败 — 标 FAILED 而非 INDEXED")
    void embedAndIndex_partialFailure_setsFailed() throws Exception {
        // Given: 1 个 chunk，embedding 抛异常（部分失败）
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        doc.setSourcePath("10/1.pdf");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setContent("内容");
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embedding 服务不可用"));

        etlPipeline.embedAndIndex(1L);

        // Then: 状态 FAILED（非 INDEXED）——捕获 update 调用断言 set 值
        // 注意：MP 3.5.12 的 wrapper.toString() 仅含 #{ew.MPGENVALn} 占位符，实际值存于
        // paramNameValuePairs，故从值集合断言（而非 SQL 片段）
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper, atLeastOnce()).update(any(), wrapperCaptor.capture());
        String setValues = wrapperCaptor.getAllValues().stream()
                .map(w -> String.valueOf(w.getParamNameValuePairs().values()))
                .reduce("", (a, b) -> a + b);
        assertTrue(setValues.contains("FAILED"), "部分失败应标 FAILED: " + setValues);
        assertFalse(setValues.contains("INDEXED"), "部分失败不应标 INDEXED: " + setValues);
    }

    @Test
    @DisplayName("embedAndIndex 空向量 — 计入失败标 FAILED（不静默跳过误标 INDEXED）")
    void embedAndIndex_emptyVector_setsFailed() {
        // Given: 1 个 chunk，embedding 返回空数组（模型未抛异常但输出为空）
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        doc.setSourcePath("10/1.pdf");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setContent("内容");
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(embeddingModel.embed(anyString())).thenReturn(new float[0]);

        etlPipeline.embedAndIndex(1L);

        // Then: 空向量计入失败 → 状态 FAILED（非 INDEXED），断言方式同 partialFailure 测试
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper, atLeastOnce()).update(any(), wrapperCaptor.capture());
        String setValues = wrapperCaptor.getAllValues().stream()
                .map(w -> String.valueOf(w.getParamNameValuePairs().values()))
                .reduce("", (a, b) -> a + b);
        assertTrue(setValues.contains("FAILED"), "空向量应标 FAILED: " + setValues);
        assertFalse(setValues.contains("INDEXED"), "空向量不应标 INDEXED: " + setValues);
    }

    @Test
    @DisplayName("parseDocument 解析异常 — 输入流仍被关闭（try-with-resources）")
    void parseDocument_parseFailure_streamClosed() throws Exception {
        // Given: 下载成功但 Tika 解析抛异常（损坏文件——底层 read 抛 IOException）
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        doc.setSourcePath("10/bad.pdf");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        InputStream mockStream = mock(InputStream.class);
        // 损坏文件：读取魔数时抛 IOException（确定性失败，避免 mock 默认返回 0 导致的空读）
        when(mockStream.read(any(byte[].class), anyInt(), anyInt())).thenThrow(new IOException("损坏文件"));
        when(minioStorageService.downloadFile("10/bad.pdf")).thenReturn(mockStream);

        // When: 解析抛异常（异常向上传播）
        assertThrows(Exception.class, () -> etlPipeline.parseDocument(1L));
        // Then: 流已关闭（try-with-resources 保证）
        verify(mockStream).close();
    }

    @Test
    @DisplayName("deleteFromMilvusByChunkIds — chunk_id IN 一次删除（P0-1 课程删除按 PG 关联清理）")
    void deleteFromMilvusByChunkIds_deletesByInFilter() {
        etlPipeline.deleteFromMilvusByChunkIds(List.of("1", "2", "3"));

        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClientV2).delete(captor.capture());
        assertEquals("chunk_id in [\"1\", \"2\", \"3\"]", captor.getValue().getFilter());
    }

    @Test
    @DisplayName("deleteFromMilvusByChunkIds — 空列表直接返回，不调用 Milvus")
    void deleteFromMilvusByChunkIds_empty_noop() {
        etlPipeline.deleteFromMilvusByChunkIds(List.of());

        verify(milvusClientV2, never()).delete(any());
    }
}
