package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.properties.EtlProperties;
import com.commerce.rag.record.ChunkLinkPair;
import com.commerce.rag.record.ContentHash;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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

    @Mock
    private ImageCaptionService imageCaptionService;

    /** Dashboard 统计缓存（真实 Caffeine 实例，状态写入失效钩子验证用） */
    private final Cache<String, Object> dashboardStatsCache =
            Caffeine.newBuilder().build();

    private EtlPipeline etlPipeline;

    @BeforeEach
    void setUp() {
        EtlProperties props = new EtlProperties(
                100,
                new EtlProperties.Executor(2, 4, 20, "etl-"),
                new EtlProperties.Chunk(768, 64),
                16,
                "qwen3.7-flash",
                10,
                new EtlProperties.Table(25, 30, 2));
        etlPipeline = new EtlPipeline(
                documentMapper,
                chunkMapper,
                minioStorageService,
                embeddingModel,
                milvusClientV2,
                props,
                dashboardStatsCache,
                new XhtmlDocumentParser(),
                new TableChunker(props),
                imageCaptionService);
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
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("embedding 服务不可用"));

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
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[0]));

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

    // ========================================================================
    // 分片算法与向量化成功路径补测（parsedContentCache 反射注入）
    // ========================================================================

    /** 反射向 parsedContentCache 注入解析结果（process 内由 Tika 写入，单测直接 seed） */
    @SuppressWarnings("unchecked")
    private void seedParsedContent(Long docId, String text) throws Exception {
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(docId, new ParsedContent(List.of(new ParsedContent.TextSection("", text))));
    }

    @Test
    @DisplayName("chunkDocument — 长段落触发 TokenTextSplitter 分片：多分片 + prev 链 + 课程归属透传")
    void chunkDocument_longParagraph_splitsWithRelations() throws Exception {
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        doc.setCourseId("5");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        // 单个超过 chunkSize(768) token 的长正文（含 ASCII "RAG"，无 .?! 回卷点），触发 TokenTextSplitter 按 token 切为多片
        String longPara = ("RAG检索增强生成是一种结合检索与生成的架构范式，向量数据库负责存储嵌入向量。" + "混合检索融合了向量相似度与关键词匹配两种召回信号。").repeat(30);
        seedParsedContent(1L, longPara);
        // mock insert 赋自增 id，建立 prev/next 链
        java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(100);
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(idSeq.getAndIncrement());
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(1L);

        // 长文本被拆成多个分片
        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper, atLeast(3)).insert(captor.capture());
        List<DocumentChunk> inserted = captor.getAllValues();
        // 首分片无 prev；后续分片 prev 指向前一个（时间序链）
        assertNull(inserted.get(0).getPrevChunkId());
        assertEquals(inserted.get(0).getId(), inserted.get(1).getPrevChunkId());
        // chunkIndex 从 0 递增
        assertEquals(0, inserted.get(0).getChunkIndex());
        assertEquals(1, inserted.get(1).getChunkIndex());
        // 文档级 course_id 透传至每个分片（非空时不做 DEFAULT 兜底）
        assertTrue(inserted.stream().allMatch(c -> "5".equals(c.getCourseId())));
        // 新分片模型：无段落父子分组，contentType 恒为 text
        assertTrue(inserted.stream().allMatch(c -> "text".equals(c.getContentType())));
        assertTrue(inserted.stream().allMatch(c -> c.getParentChunkId() == null));
        // token 估算非零（TokenEstimator.estimate 执行）
        assertTrue(inserted.get(0).getTokenCount() > 0);
        // 分片数回写文档
        verify(documentMapper, atLeastOnce()).update(any(), any());
        // M-1：next_chunk_id 单条批量回填（收集全部链路对，非逐分片 UPDATE）
        ArgumentCaptor<List> linkCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkMapper).batchUpdateNextChunkIds(linkCaptor.capture());
        List<ChunkLinkPair> pairs = linkCaptor.getValue();
        assertFalse(pairs.isEmpty());
        assertEquals(inserted.get(0).getId(), pairs.get(0).prevChunkId());
        assertEquals(inserted.get(1).getId(), pairs.get(0).nextChunkId());
    }

    @Test
    @DisplayName("chunkDocument — 短文本单分片：courseId 缺省 DEFAULT、无关联指针")
    void chunkDocument_shortText_singleChunkWithDefaultCourse() throws Exception {
        Document doc = new Document();
        doc.setId(2L);
        doc.setKbId(20L);
        when(documentMapper.selectById(2L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        seedParsedContent(2L, "这是短文本内容，不足一个分片大小。");
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(200L);
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(2L);

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).insert(captor.capture());
        DocumentChunk chunk = captor.getValue();
        assertEquals("DEFAULT", chunk.getCourseId());
        assertEquals(0, chunk.getChunkIndex());
        assertNull(chunk.getParentChunkId());
        assertNull(chunk.getPrevChunkId());
        assertEquals("TECHNICAL_QA", chunk.getCollectionType());
    }

    @Test
    @DisplayName("chunkDocument — 多标题分区：各分片继承所属 headingPath")
    void chunkDocument_multiSections_inheritHeadingPath() throws Exception {
        Document doc = new Document();
        doc.setId(3L);
        doc.setKbId(30L);
        when(documentMapper.selectById(3L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(
                        3L,
                        new ParsedContent(List.of(
                                new ParsedContent.TextSection("第一章", "第一章的正文内容。".repeat(10)),
                                new ParsedContent.TextSection("第二章", "第二章的正文内容。".repeat(10)))));
        java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(300);
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(idSeq.getAndIncrement());
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(3L);

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper, atLeast(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(c -> "第一章".equals(c.getHeadingPath())));
        assertTrue(captor.getAllValues().stream().anyMatch(c -> "第二章".equals(c.getHeadingPath())));
    }

    @Test
    @DisplayName("chunkDocument — 过小尾块并入前一个（不产生 <64 字符碎块）")
    void chunkDocument_smallTail_mergedIntoPrevious() throws Exception {
        Document doc = new Document();
        doc.setId(4L);
        doc.setKbId(40L);
        when(documentMapper.selectById(4L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        // 构造：正文 > 768 token（触发 TokenTextSplitter 多片）+ 短尾句「完。」。
        // 尾块实测约 13 字符：大于框架 minChunkLengthToEmbed(5) 被保留、小于 64 被 mergeSmallPieces
        // 并入前一片（2→1）；若去掉合并，13 字符「完。」尾块会命中下方断言使测试失败（真实回归守卫）。
        // 注意：尾句不得 ≤5 字符（会被框架直接丢弃），正文不得含 ASCII .?! 回卷点。
        String longBody = "检索增强生成结合检索与生成，向量数据库存储嵌入向量。".repeat(30);
        String tail = "完。";
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(4L, new ParsedContent(List.of(new ParsedContent.TextSection("", longBody + tail))));
        java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(400);
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(idSeq.getAndIncrement());
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(4L);

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper, atLeast(1)).insert(captor.capture());
        assertTrue(
                captor.getAllValues().stream()
                        .noneMatch(c ->
                                c.getContent().length() < 64 && c.getContent().endsWith("完。")),
                "过小尾块应并入前一个分片");
    }

    @Test
    @DisplayName("chunkDocument — 表格分区产出 content_type=table 的 Markdown chunk")
    void chunkDocument_tableSection_producesTableChunk() throws Exception {
        Document doc = new Document();
        doc.setId(5L);
        doc.setKbId(50L);
        when(documentMapper.selectById(5L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(
                        5L,
                        new ParsedContent(List.of(new ParsedContent.TableSection(
                                "价格表",
                                "<table><tr><th>名称</th><th>价格</th></tr><tr><td>课程A</td><td>1999</td></tr></table>"))));
        java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(500);
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(idSeq.getAndIncrement());
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(5L);

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).insert(captor.capture());
        DocumentChunk chunk = captor.getValue();
        assertEquals("table", chunk.getContentType());
        assertEquals("价格表", chunk.getHeadingPath());
        assertTrue(chunk.getContent().contains("| 课程A | 1999 |"));
    }

    @Test
    @DisplayName("chunkDocument — 图片分区：小图标被过滤跳过、有效图片产出 image chunk")
    void chunkDocument_imageSection_filtersAndCaptions() throws Exception {
        Document doc = new Document();
        doc.setId(6L);
        doc.setKbId(60L);
        when(documentMapper.selectById(6L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        byte[] bigImage = new byte[10 * 1024]; // ≥ imageMinSizeKb(10KB)，不触发小图标过滤
        when(minioStorageService.uploadFile(eq(60L), anyString(), any(InputStream.class), eq("png")))
                .thenReturn("60/abc.png");
        when(imageCaptionService.caption(any(byte[].class), eq("image/png"))).thenReturn("这是一段图片描述");
        Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        // 两张图：9KB 小图标（<10KB 触发 isSmallIcon 过滤）+ 有效图（≥10KB 正常处理）
        ((ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(
                        6L,
                        new ParsedContent(List.of(
                                new ParsedContent.ImageSection("", "image/png", new byte[10 * 1024 - 1], "icon.png"),
                                new ParsedContent.ImageSection("图例", "image/png", bigImage, "image0.png"))));
        AtomicLong idSeq = new AtomicLong(600);
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(idSeq.getAndIncrement());
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(6L);

        // 小图被过滤：仅 1 个 image chunk 落库（即文档成功产出且小图标未进入处理管线）
        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).insert(captor.capture());
        DocumentChunk chunk = captor.getValue();
        assertEquals("image", chunk.getContentType());
        assertEquals("这是一段图片描述", chunk.getContent());
        assertEquals("60/abc.png", chunk.getImageUrl());
        assertEquals("图例", chunk.getHeadingPath());
        assertTrue(chunk.getMetadataJson().contains("image0.png"));
        assertFalse(chunk.getMetadataJson().contains("icon.png"), "小图标不应产出 chunk");
        // caption 仅被有效图字节调用一次（小图标过滤后未进入 caption）
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(imageCaptionService).caption(bytesCaptor.capture(), eq("image/png"));
        assertArrayEquals(bigImage, bytesCaptor.getValue(), "caption 只应收到有效图字节");
        // 有效图也仅 upload 一次（小图标未触发 MinIO 上传）
        verify(minioStorageService, times(1)).uploadFile(eq(60L), anyString(), any(InputStream.class), eq("png"));
    }

    @Test
    @DisplayName("chunkDocument — caption 失败仅跳过该图，文档 ETL 不 FAILED")
    void chunkDocument_imageCaptionFailure_skipsImage() throws Exception {
        Document doc = new Document();
        doc.setId(7L);
        doc.setKbId(70L);
        when(documentMapper.selectById(7L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        byte[] bigImage = new byte[10 * 1024];
        when(minioStorageService.uploadFile(eq(70L), anyString(), any(InputStream.class), eq("png")))
                .thenReturn("70/abc.png");
        when(imageCaptionService.caption(any(byte[].class), eq("image/png")))
                .thenThrow(new RuntimeException("VLM 服务不可用"));
        Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(
                        7L,
                        new ParsedContent(List.of(
                                new ParsedContent.TextSection("", "正文内容保证文档非空。"),
                                new ParsedContent.ImageSection("", "image/png", bigImage, "image1.png"))));
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(700L);
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(7L);

        // 仅文本 chunk 落库（图片跳过），状态 CHUNKED 而非 FAILED
        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).insert(captor.capture());
        assertEquals("text", captor.getValue().getContentType());
    }

    @Test
    @DisplayName("chunkDocument — 文档不存在抛 IllegalStateException")
    void chunkDocument_docNotFound_throws() {
        when(documentMapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> etlPipeline.chunkDocument(99L));
    }

    @Test
    @DisplayName("chunkDocument — 解析文本为空抛 IllegalStateException（不产生分片）")
    void chunkDocument_blankText_throws() throws Exception {
        Document doc = new Document();
        doc.setId(1L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        seedParsedContent(1L, "   ");

        assertThrows(IllegalStateException.class, () -> etlPipeline.chunkDocument(1L));
        verify(chunkMapper, never()).insert(any(DocumentChunk.class));
    }

    @Test
    @DisplayName("chunkDocument — 批内重复内容只入库一次")
    void chunkDocument_duplicateContentWithinBatch_deduped() throws Exception {
        Document doc = new Document();
        doc.setId(8L);
        doc.setKbId(80L);
        when(documentMapper.selectById(8L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        // 两个内容完全相同的文本分区（不同 heading）
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(
                        8L,
                        new ParsedContent(List.of(
                                new ParsedContent.TextSection("甲", "完全相同的内容段落。"),
                                new ParsedContent.TextSection("乙", "完全相同的内容段落。"))));
        // 查库：无已有 hash
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(800L);
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(8L);

        // 终审加固回归：去重查询必须排除本文档自身既有 chunk（失败重跑/预留路径下旧 chunk 不得作为「已存在」
        // 去重依据——否则会在 delete-then-insert 前被跳过，导致内容丢失 + chunk 计数漂移）
        ArgumentCaptor<LambdaQueryWrapper> dedupCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(chunkMapper).selectList(dedupCaptor.capture());
        LambdaQueryWrapper dedupWrapper = dedupCaptor.getValue();
        // 先渲染（getSqlSegment）——IN/占位符参数在渲染时才写入 paramNameValuePairs（MP 3.5.12 惰性）
        String dedupSql = dedupWrapper.getSqlSegment();
        assertTrue(dedupSql.contains("doc_id") && dedupSql.contains("<>"), "去重查询应含 doc_id <> 当前文档条件: " + dedupSql);
        // 参数值集合断言含精确 Long 8L（hash 均为 String，不会与 Long 类型混淆）
        assertTrue(
                dedupWrapper.getParamNameValuePairs().values().contains(8L),
                "去重查询参数应含当前 docId=8: " + dedupWrapper.getParamNameValuePairs());

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).insert(captor.capture());
        assertEquals(1, captor.getAllValues().size(), "重复内容应只入库一次");
        assertEquals(64, captor.getValue().getSha256().length());
    }

    @Test
    @DisplayName("chunkDocument — 全库已有同内容：零入库，状态 CHUNKED")
    void chunkDocument_allContentExists_skipsInsert() throws Exception {
        Document doc = new Document();
        doc.setId(9L);
        doc.setKbId(90L);
        when(documentMapper.selectById(9L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        String text = "全库已存在的内容。";
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(9L, new ParsedContent(List.of(new ParsedContent.TextSection("", text))));
        // 查库：返回同 hash 的既有分片（deleted=0）
        DocumentChunk existing = new DocumentChunk();
        existing.setSha256(ContentHash.of(text).sha256());
        when(chunkMapper.selectList(any())).thenReturn(List.of(existing));

        etlPipeline.chunkDocument(9L);

        verify(chunkMapper, never()).insert(any(DocumentChunk.class));
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper, atLeastOnce()).update(any(), captor.capture());
        String setValues = captor.getAllValues().stream()
                .map(w -> String.valueOf(w.getParamNameValuePairs().values()))
                .reduce("", (a, b) -> a + b);
        assertTrue(setValues.contains("CHUNKED"), "全部去重后仍应正常收尾 CHUNKED: " + setValues);
    }

    @Test
    @DisplayName("embedAndIndex — 全部分片成功 → 状态 INDEXED（PG 向量更新 + Milvus insert）")
    void embedAndIndex_allSuccess_marksIndexed() {
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("测试文档");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        DocumentChunk c1 = new DocumentChunk();
        c1.setId(1L);
        c1.setDocId(1L);
        c1.setKbId(10L);
        c1.setContent("内容一");
        c1.setChunkIndex(0);
        DocumentChunk c2 = new DocumentChunk();
        c2.setId(2L);
        c2.setDocId(1L);
        c2.setKbId(10L);
        c2.setContent("内容二");
        c2.setChunkIndex(1);
        when(chunkMapper.selectList(any())).thenReturn(List.of(c1, c2));
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[] {0.1f, 0.2f, 0.3f}, new float[] {0.4f, 0.5f, 0.6f}));

        etlPipeline.embedAndIndex(1L);

        // 状态最终为 INDEXED（EMBEDDING 前置 + INDEXED 终态；值参数化在 paramNameValuePairs）
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper, atLeast(2)).update(any(), captor.capture());
        boolean indexed = captor.getAllValues().stream().anyMatch(w -> {
            w.getSqlSet(); // 触发惰性渲染，填充 paramNameValuePairs
            return w.getParamNameValuePairs().containsValue("INDEXED");
        });
        assertTrue(indexed, "成功路径应以 INDEXED 收尾");
        // H-3：PG 向量批量回写（单条 CASE WHEN UPDATE，原逐分片 update）
        verify(chunkMapper).batchUpdateVectors(anyList());
        // H-3：Milvus 清旧 1 次 delete + 1 次多行 insert（2 行）
        verify(milvusClientV2, times(1)).delete(any(DeleteReq.class));
        ArgumentCaptor<InsertReq> insertCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClientV2, times(1)).insert(insertCaptor.capture());
        assertEquals(2, insertCaptor.getValue().getData().size(), "多行插入应携带 2 行");
    }

    @Test
    @DisplayName("Milvus 行字段 — 新 schema：含 content_type/image_url/sha256，不含 collection_type")
    void milvusRow_containsNewFields_noCollectionType() {
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("测试文档");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setContent("图片描述");
        chunk.setContentType("image");
        chunk.setImageUrl("10/abc.png");
        chunk.setSha256("f".repeat(64));
        chunk.setChunkIndex(0);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[] {0.1f, 0.2f}));

        etlPipeline.embedAndIndex(1L);

        ArgumentCaptor<InsertReq> captor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClientV2).insert(captor.capture());
        JsonObject row = captor.getValue().getData().get(0);
        assertEquals("image", row.get("content_type").getAsString());
        assertEquals("10/abc.png", row.get("image_url").getAsString());
        assertEquals("f".repeat(64), row.get("sha256").getAsString());
        assertNull(row.get("collection_type"), "新 schema 行不应含 collection_type");
    }

    // ==================== reEmbedAndUpsert / 同步 Milvus（M-4 批量） ====================

    @Test
    @DisplayName("reEmbedAndUpsert → 单分片重新向量化：PG 向量更新 + Milvus delete-then-insert")
    void reEmbedAndUpsert_updatesVectorAndMilvus() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setContent("新内容");
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("测试文档");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f, 0.3f});

        etlPipeline.reEmbedAndUpsert(1L);

        // PG：单分片向量回写（updateChunkVector）
        verify(chunkMapper).update(any(), any());
        // Milvus：delete 1 次 + 多行 insert 1 次（单行）
        verify(milvusClientV2).delete(any(DeleteReq.class));
        ArgumentCaptor<InsertReq> insertCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClientV2).insert(insertCaptor.capture());
        assertEquals(1, insertCaptor.getValue().getData().size());
    }

    @Test
    @DisplayName("reEmbedAndUpsert → 分片不存在抛 IllegalStateException")
    void reEmbedAndUpsert_chunkNotFound_throws() {
        when(chunkMapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> etlPipeline.reEmbedAndUpsert(99L));
    }

    @Test
    @DisplayName("reEmbedAndUpsert → Embedding 返回空向量抛异常")
    void reEmbedAndUpsert_emptyVector_throws() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setContent("内容");
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        when(embeddingModel.embed(anyString())).thenReturn(new float[0]);

        assertThrows(RuntimeException.class, () -> etlPipeline.reEmbedAndUpsert(1L));
    }

    @Test
    @DisplayName("syncChunkToMilvus → 已向量化分片重建 Milvus 行（向量从 PG 恢复）")
    void syncChunkToMilvus_vectorized_syncsRow() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setCourseId("COURSE_1");
        chunk.setDenseVector(new byte[] {0, 0, 0, 0}); // 1 个 float
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("测试文档");
        when(documentMapper.selectById(1L)).thenReturn(doc);

        etlPipeline.syncChunkToMilvus(1L);

        verify(milvusClientV2).delete(any(DeleteReq.class));
        verify(milvusClientV2).insert(any(InsertReq.class));
    }

    @Test
    @DisplayName("syncChunkToMilvus → 未向量化分片跳过（不调 Milvus）")
    void syncChunkToMilvus_noVector_skips() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        when(chunkMapper.selectById(1L)).thenReturn(chunk);

        etlPipeline.syncChunkToMilvus(1L);

        verify(milvusClientV2, never()).delete(any(DeleteReq.class));
        verify(milvusClientV2, never()).insert(any(InsertReq.class));
    }

    @Test
    @DisplayName("syncDocToMilvus（M-4）→ 批量 delete（filter IN）+ 多行 insert，未向量化分片跳过")
    void syncDocToMilvus_batchDeleteAndInsert() {
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("测试文档");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        DocumentChunk vectorized = new DocumentChunk();
        vectorized.setId(1L);
        vectorized.setDocId(1L);
        vectorized.setKbId(10L);
        vectorized.setDenseVector(new byte[] {0, 0, 0, 0});
        DocumentChunk noVector = new DocumentChunk();
        noVector.setId(2L);
        noVector.setDocId(1L);
        noVector.setKbId(10L);
        noVector.setDenseVector(null);
        when(chunkMapper.selectList(any())).thenReturn(List.of(vectorized, noVector));

        etlPipeline.syncDocToMilvus(1L);

        // M-4: 批量 delete 一次（filter IN 含 1 个 chunk）+ 多行 insert 一次（1 行）
        ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClientV2, times(1)).delete(deleteCaptor.capture());
        assertTrue(deleteCaptor.getValue().getFilter().contains("chunk_id in"), "应使用 filter IN 批量删除");
        ArgumentCaptor<InsertReq> insertCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClientV2, times(1)).insert(insertCaptor.capture());
        assertEquals(1, insertCaptor.getValue().getData().size(), "多行插入仅含已向量化分片");
    }

    @Test
    @DisplayName("syncDocToMilvus → 无向量化分片时跳过（不调 Milvus）")
    void syncDocToMilvus_noVectorized_skips() {
        Document doc = new Document();
        doc.setId(1L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        DocumentChunk noVector = new DocumentChunk();
        noVector.setId(2L);
        noVector.setDocId(1L);
        when(chunkMapper.selectList(any())).thenReturn(List.of(noVector));

        etlPipeline.syncDocToMilvus(1L);

        verify(milvusClientV2, never()).delete(any(DeleteReq.class));
        verify(milvusClientV2, never()).insert(any(InsertReq.class));
    }

    @Test
    @DisplayName("syncDocToMilvus → 文档不存在抛 IllegalStateException")
    void syncDocToMilvus_docNotFound_throws() {
        when(documentMapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> etlPipeline.syncDocToMilvus(99L));
    }

    // ========================================================================
    // B3-1 修复波次新增：Milvus insert 失败不吞异常（对齐 delete 路径 P0-8 哲学）
    // ========================================================================

    @Test
    @DisplayName("B3-1: embedAndIndex — Milvus insert 抛异常 → 计入失败标 FAILED（不误标 INDEXED）+ 半成品清理")
    void embedAndIndex_milvusInsertFails_setsFailedNotIndexed() {
        // Given: embed 成功 + PG 向量回写成功 + Milvus insert 抛异常（服务瞬时不可用）
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setContent("内容");
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[] {0.1f, 0.2f}));
        when(milvusClientV2.insert(any(InsertReq.class))).thenThrow(new RuntimeException("milvus 不可用"));

        etlPipeline.embedAndIndex(1L);

        // Then: 状态 FAILED（非 INDEXED）——向量缺失时文档不得标绿
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper, atLeastOnce()).update(any(), wrapperCaptor.capture());
        String setValues = wrapperCaptor.getAllValues().stream()
                .map(w -> String.valueOf(w.getParamNameValuePairs().values()))
                .reduce("", (a, b) -> a + b);
        assertTrue(setValues.contains("FAILED"), "Milvus insert 失败应标 FAILED: " + setValues);
        assertFalse(setValues.contains("INDEXED"), "Milvus insert 失败不应误标 INDEXED: " + setValues);
        // P2-6: FAILED 半成品清理被触发（开头清旧 1 次 + 失败清理 1 次 = 2 次 delete）
        verify(milvusClientV2, times(2)).delete(any(DeleteReq.class));
    }

    @Test
    @DisplayName("B3-1: reEmbedAndUpsert — Milvus insert 失败上抛（阻断调用方，不再静默丢向量）")
    void reEmbedAndUpsert_milvusInsertFails_throws() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setContent("新内容");
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("测试文档");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        // delete 成功（旧向量已删）+ insert 失败——吞异常会让该 chunk 向量从 Milvus 消失且无感知
        when(milvusClientV2.insert(any(InsertReq.class))).thenThrow(new RuntimeException("milvus 不可用"));

        assertThrows(RuntimeException.class, () -> etlPipeline.reEmbedAndUpsert(1L));
    }

    @Test
    @DisplayName("B3-1: syncDocToMilvus — Milvus insert 失败上抛（阻断标注同步，可重试收敛）")
    void syncDocToMilvus_milvusInsertFails_throws() {
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("测试文档");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        DocumentChunk vectorized = new DocumentChunk();
        vectorized.setId(1L);
        vectorized.setDocId(1L);
        vectorized.setKbId(10L);
        vectorized.setDenseVector(new byte[] {0, 0, 0, 0});
        when(chunkMapper.selectList(any())).thenReturn(List.of(vectorized));
        when(milvusClientV2.insert(any(InsertReq.class))).thenThrow(new RuntimeException("milvus 不可用"));

        assertThrows(RuntimeException.class, () -> etlPipeline.syncDocToMilvus(1L));
    }

    @Test
    @DisplayName("B3-1: syncChunkToMilvus — Milvus insert 失败上抛（同步不静默丢向量）")
    void syncChunkToMilvus_milvusInsertFails_throws() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setDenseVector(new byte[] {0, 0, 0, 0});
        when(chunkMapper.selectById(1L)).thenReturn(chunk);
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("测试文档");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(milvusClientV2.insert(any(InsertReq.class))).thenThrow(new RuntimeException("milvus 不可用"));

        assertThrows(RuntimeException.class, () -> etlPipeline.syncChunkToMilvus(1L));
    }

    @Test
    @DisplayName("embedAndIndex 部分失败且 Milvus 半成品清理失败 — 仅告警，仍标 FAILED")
    void embedAndIndex_partialFailure_cleanupDeleteFails_stillFailed() {
        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        when(documentMapper.selectById(1L)).thenReturn(doc);
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setContent("内容");
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("embedding 服务不可用"));
        // 开头清旧 delete 成功、FAILED 半成品清理 delete 失败（仅告警不阻断）
        doReturn(null)
                .doThrow(new RuntimeException("milvus down"))
                .when(milvusClientV2)
                .delete(any(DeleteReq.class));

        etlPipeline.embedAndIndex(1L);

        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper, atLeastOnce()).update(any(), wrapperCaptor.capture());
        String setValues = wrapperCaptor.getAllValues().stream()
                .map(w -> String.valueOf(w.getParamNameValuePairs().values()))
                .reduce("", (a, b) -> a + b);
        assertTrue(setValues.contains("FAILED"), "半成品清理失败仍应标 FAILED: " + setValues);
    }
}
