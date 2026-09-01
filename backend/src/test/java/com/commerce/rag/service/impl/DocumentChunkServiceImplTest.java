package com.commerce.rag.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.convert.DocumentChunkConverter;
import com.commerce.rag.convert.StudentConverter;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.properties.EtlProperties;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DocumentChunkServiceImpl 单元测试 —— PERF-20 批量标注文档级 Milvus 同步并行化（保守方案）
 *
 * <p>覆盖契约：docIds 去重、2 并发分批提交 etlPool（不得超并发、不得在请求线程内联执行）、
 * 总超时阻断、单文档失败隔离后仍上抛（保持「失败上抛可重试收敛」语义）。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentChunkServiceImpl 批量标注文档级同步并行化测试")
class DocumentChunkServiceImplTest {

    @BeforeAll
    static void initMybatisPlus() {
        // LambdaQueryWrapper/LambdaUpdateWrapper 需实体 TableInfo 缓存才能解析列名（无 Spring 上下文）
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

    @Mock
    private DocumentChunkConverter chunkConverter;

    @Mock
    private StudentConverter studentConverter;

    @Mock
    private DashboardCacheEvictor dashboardCacheEvictor;

    /** 被测服务（每个用例按超时配置重建） */
    private DocumentChunkServiceImpl service;

    /** 仿生产 etlPool 形态的测试线程池（core 2/max 4/有界 20/etl- 前缀/AbortPolicy） */
    private ThreadPoolExecutor etlPool;

    @BeforeEach
    void setUp() {
        etlPool = new ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(20),
                new ThreadFactoryBuilder()
                        .setNameFormat("etl-%d")
                        .setDaemon(true)
                        .build(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @AfterEach
    void tearDown() {
        // 释放测试线程池，防止线程跨用例残留
        etlPool.shutdownNow();
    }

    /**
     * 构造 ETL 配置（仅标注同步总超时可变，其余取生产默认形态）
     *
     * @param timeoutSeconds 批量标注文档级同步总超时（秒）
     */
    private EtlProperties etlProps(int timeoutSeconds) {
        return new EtlProperties(
                100,
                new EtlProperties.Executor(2, 4, 20, "etl-"),
                new EtlProperties.ImageExecutor(2, 4, 20, "etl-image-", 60),
                new EtlProperties.Chunk(768, 64),
                16,
                10,
                new EtlProperties.Table(25, 30, 2),
                500,
                timeoutSeconds);
    }

    /** 构造被测服务（超时取 30s 避免常规用例误伤，超时用例单独构造） */
    private DocumentChunkServiceImpl buildService(int timeoutSeconds) {
        return new DocumentChunkServiceImpl(
                chunkMapper,
                documentMapper,
                knowledgeBaseMapper,
                etlPipeline,
                chunkConverter,
                studentConverter,
                dashboardCacheEvictor,
                etlPool,
                etlProps(timeoutSeconds));
    }

    /** 构造分片行（id + docId 两列，batchUpdate 的 docId 收集只消费这两列） */
    private DocumentChunk chunk(long id, long docId) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setDocId(docId);
        return c;
    }

    /** 预置 batchUpdate 的 mapper 桩：docId 收集查询返回指定分片行（isAdmin=true 旁路归属校验） */
    private void stubDocIdQuery(DocumentChunk... chunks) {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunks));
    }

    @Test
    @DisplayName("docIds 去重：4 分片行跨 3 个文档 → syncDocToMilvus 恰调 3 次且在 etlPool 线程执行")
    void batchUpdate_dedupesDocIds_andRunsOnEtlPool() {
        service = buildService(30);
        // 4 行分片：docId=100 出现两次 → 去重后 {100,200,300}
        stubDocIdQuery(chunk(1, 100), chunk(2, 100), chunk(3, 200), chunk(4, 300));
        // 记录每次同步的执行线程名，断言跑在 etlPool（而非 HTTP 请求线程内联）
        ConcurrentHashMap<String, Integer> threadNames = new ConcurrentHashMap<>();
        doAnswer(inv -> {
                    threadNames.merge(Thread.currentThread().getName(), 1, Integer::sum);
                    return null;
                })
                .when(etlPipeline)
                .syncDocToMilvus(anyLong());

        service.batchUpdate(List.of(1L, 2L, 3L, 4L), "TEXT", "course-1", 1L, true);

        // 调用次数 = 去重后文档数（3），docId=100 只同步一次
        verify(etlPipeline, times(3)).syncDocToMilvus(anyLong());
        verify(etlPipeline, times(1)).syncDocToMilvus(100L);
        verify(etlPipeline, times(1)).syncDocToMilvus(200L);
        verify(etlPipeline, times(1)).syncDocToMilvus(300L);
        // 全部同步必须在 etl- 前缀的池线程执行（禁止请求线程内联逐文档同步）
        int totalSyncs =
                threadNames.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(3, totalSyncs, "3 个文档应各同步一次");
        threadNames.keySet().forEach(name -> assertTrue(name.startsWith("etl-"), "同步应跑在 etlPool 线程: " + name));
    }

    @Test
    @DisplayName("2 并发上限：5 文档分批提交——同一时刻恰好 2 个在执行，后续批次待前批完成后才启动")
    void batchUpdate_capsConcurrencyAtTwo() throws Exception {
        service = buildService(30);
        stubDocIdQuery(chunk(1, 600), chunk(2, 700), chunk(3, 800), chunk(4, 900), chunk(5, 1000));
        // 前 2 个任务各自 countDown 后阻塞在 release；批次语义保证第 3+ 个任务只能在前批完成后启动
        CountDownLatch twoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger startedBeforeRelease = new AtomicInteger();
        doAnswer(inv -> {
                    // release 未打开即视为「前批」启动（后批启动晚于 release，存在 happens-before 保证）
                    if (release.getCount() > 0) {
                        startedBeforeRelease.incrementAndGet();
                    }
                    twoStarted.countDown();
                    // 有界等待防用例异常时线程悬挂
                    assertTrue(release.await(10, TimeUnit.SECONDS), "测试释放信号应在 10s 内到达");
                    return null;
                })
                .when(etlPipeline)
                .syncDocToMilvus(anyLong());

        // 请求线程语义：batchUpdate 在独立线程发起，主线程观测并发窗口
        Thread caller =
                new Thread(() -> service.batchUpdate(List.of(1L, 2L, 3L, 4L, 5L), "TEXT", "course-1", 1L, true));
        caller.start();
        // 2 个任务同时阻塞在 release 才能凑齐两次 countDown——证明并发=2 而非串行
        assertTrue(twoStarted.await(5, TimeUnit.SECONDS), "前 2 个文档应并发执行（而非逐个串行）");

        release.countDown();
        caller.join(5000);
        verify(etlPipeline, times(5)).syncDocToMilvus(anyLong());
        // release 打开前启动的同步恰为 2——证明并发上限封在 2，后续批次未抢跑
        assertEquals(2, startedBeforeRelease.get(), "同一时刻最多 2 个文档在同步");
    }

    @Test
    @DisplayName("单文档失败隔离：首个失败不阻断其余文档执行，全部执行完后仍上抛原异常（可重试收敛）")
    void batchUpdate_singleDocFailure_isolatedThenRethrown() {
        service = buildService(30);
        // 3 个文档：第 1 次调用失败（模拟 Milvus 不可用），后续 2 次成功
        stubDocIdQuery(chunk(1, 100), chunk(2, 200), chunk(3, 300));
        IllegalStateException syncFailure = new IllegalStateException("Milvus 同步失败: docId=100");
        doThrow(syncFailure).doNothing().doNothing().when(etlPipeline).syncDocToMilvus(anyLong());

        // 对外契约不变：异常仍从 batchUpdate 上抛（GlobalExceptionHandler 按现状处理）
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.batchUpdate(List.of(1L, 2L, 3L), "TEXT", "course-1", 1L, true));

        // 失败被隔离：其余 2 个文档仍完成了同步（不静默吞结果），总共尝试 3 次
        verify(etlPipeline, times(3)).syncDocToMilvus(anyLong());
        // 上抛的是原始异常（保持异常类型与消息契约）
        assertEquals(syncFailure, ex);
    }

    @Test
    @DisplayName("总超时：同步超过配置阈值（1s）→ 阻断上抛超时异常（不无限等待）")
    void batchUpdate_exceedsTotalTimeout_throws() throws Exception {
        service = buildService(1);
        stubDocIdQuery(chunk(1, 100), chunk(2, 200));
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
                    // 模拟 Milvus 挂死（远超 1s 总超时）；有界等待防用例异常时线程悬挂
                    return release.await(10, TimeUnit.SECONDS);
                })
                .when(etlPipeline)
                .syncDocToMilvus(anyLong());

        long start = System.nanoTime();
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> service.batchUpdate(List.of(1L, 2L), "TEXT", "course-1", 1L, true));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 超时消息中文说明并可定位（含超时阈值语义）
        assertTrue(ex.getMessage().contains("超时"), "异常消息应说明超时: " + ex.getMessage());
        // 在总超时（1s）附近快速失败，而非陪挂死任务等到 10s
        assertTrue(elapsedMs < 5000, "应在总超时附近快速失败，实际耗时 " + elapsedMs + "ms");
        // 释放挂死任务，归还池线程
        release.countDown();
    }

    @Test
    @DisplayName("边界：无分片行（空 docIds）→ 不同步不抛异常")
    void batchUpdate_noChunks_noSync() {
        service = buildService(30);
        stubDocIdQuery();

        assertDoesNotThrow(() -> service.batchUpdate(List.of(1L, 2L), "TEXT", "course-1", 1L, true));

        verify(etlPipeline, times(0)).syncDocToMilvus(anyLong());
    }

    @Test
    @DisplayName("边界：ids 为空 → 直接返回不触发任何查询与同步")
    void batchUpdate_emptyIds_returnsEarly() {
        service = buildService(30);

        service.batchUpdate(List.of(), "TEXT", "course-1", 1L, true);

        verify(chunkMapper, times(0)).selectList(any());
        verify(etlPipeline, times(0)).syncDocToMilvus(anyLong());
    }
}
