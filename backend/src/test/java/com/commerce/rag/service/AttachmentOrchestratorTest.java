package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.DocumentLocalChunk;
import com.commerce.rag.record.ImageCaptionResult;
import com.commerce.rag.stream.ThinkingPusher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AttachmentOrchestrator 单元测试 —— 下载 → 按类型分发（图片 caption / 文档局部语料）→ 组装 AttachmentContext
 *
 * <p>核心验证：图片字节交 imageProcessor、文档字节交 documentProcessor、单项失败跳过不中断、
 * 空/全失败附件返回 empty（spec §5.1 单项失败不阻断对话）；P2-2 并行化后另验证
 * 附件级并行执行、总超时丢弃慢附件、图片字节按原始顺序组装、池满拒绝快速跳过。
 *
 * <p>语义用例使用同步执行器（Runnable::run，mock 即时返回不引入时序）；
 * 并行/超时用例使用真实线程池与 latch 控制确定性。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttachmentOrchestrator 附件编排测试")
class AttachmentOrchestratorTest {

    /** 默认测试配置：总超时 60s（并行用例自建真实线程池，语义用例同步执行不触发超时） */
    private static final AttachmentProperties PROPS = new AttachmentProperties(
            10, 50, 10, 100, 100, 30, 16, 60000, new AttachmentProperties.Executor(2, 4, 20, "attachment-test-"));

    /** 构建指定总超时的测试配置（超时用例用 300ms 触发丢弃） */
    private static AttachmentProperties propsWithTimeout(long timeoutMs) {
        return new AttachmentProperties(
                10, 50, 10, 100, 100, 30, 16, timeoutMs, new AttachmentProperties.Executor(2, 4, 20, "attachment-"));
    }

    @Mock
    private IAttachmentService attachmentService;

    @Mock
    private AttachmentImageProcessor imageProcessor;

    @Mock
    private AttachmentDocumentProcessor documentProcessor;

    private AttachmentOrchestrator orchestrator;

    /** Task 4 默认参数：无思考推送（同步 caption 路径）+ 未取消（语义用例聚焦分发/组装） */
    private static final BooleanSupplier NOT_CANCELLED = () -> false;

    @BeforeEach
    void setUp() {
        // 同步执行器：语义用例（mock 即时返回）验证分发/组装/失败语义，不引入线程时序
        orchestrator =
                new AttachmentOrchestrator(attachmentService, imageProcessor, documentProcessor, Runnable::run, PROPS);
    }

    @Test
    @DisplayName("图片附件 — 下载字节后交 imageProcessor，captions 组装入 context")
    void process_imageAttachment_delegatesToImageProcessor() {
        // Given: 单张图片附件，download 返回字节，imageProcessor 产出 caption
        byte[] bytes = new byte[] {1, 2, 3};
        when(attachmentService.download("0/a.png")).thenReturn(bytes);
        when(imageProcessor.processImages(List.of(bytes), List.of("a.png"), null))
                .thenReturn(List.of(new ImageCaptionResult("图片1:红色图表", "a.png")));

        // When
        AttachmentContext ctx = orchestrator.process(
                List.of(new AttachmentRecord("image", "0/a.png", "a.png", 1L)), null, NOT_CANCELLED);

        // Then: 图片字节交给 imageProcessor，不触发文档处理；captions 组装、documents 为空
        verify(attachmentService).download("0/a.png");
        verify(imageProcessor).processImages(List.of(bytes), List.of("a.png"), null);
        verify(documentProcessor, never()).processDocument(any(), any());
        assertEquals(1, ctx.captions().size());
        assertEquals("图片1:红色图表", ctx.captions().get(0).caption());
        assertTrue(ctx.documents().isEmpty());
        assertTrue(ctx.hasAny());
    }

    @Test
    @DisplayName("文档附件 — 下载字节后交 documentProcessor，documents 按 url 键组装")
    void process_documentAttachment_delegatesToDocumentProcessor() {
        // Given: 单个文档附件，download 返回字节，documentProcessor 产出局部分片
        byte[] bytes = new byte[] {4, 5, 6};
        when(attachmentService.download("0/doc.pdf")).thenReturn(bytes);
        List<DocumentLocalChunk> chunks = List.of(new DocumentLocalChunk("附件正文", new float[] {1f}, 0));
        when(documentProcessor.processDocument(bytes, "doc.pdf")).thenReturn(chunks);

        // When
        AttachmentContext ctx = orchestrator.process(
                List.of(new AttachmentRecord("document", "0/doc.pdf", "doc.pdf", 2L)), null, NOT_CANCELLED);

        // Then: 文档字节交给 documentProcessor，不触发图片处理；documents 以 url 为键
        verify(documentProcessor).processDocument(bytes, "doc.pdf");
        verify(imageProcessor, never()).processImages(anyList(), anyList(), any());
        assertTrue(ctx.documents().containsKey("0/doc.pdf"));
        assertEquals(chunks, ctx.documents().get("0/doc.pdf"));
        assertTrue(ctx.captions().isEmpty());
        assertTrue(ctx.hasAny());
    }

    @Test
    @DisplayName("混合附件 — 图片与文档各自进入对应处理器并组装完整 context")
    void process_mixedAttachments_bothProcessorsInvoked() {
        // Given: 1 图 + 1 文档，均处理成功
        byte[] imgBytes = new byte[] {1};
        byte[] docBytes = new byte[] {2};
        when(attachmentService.download("0/a.png")).thenReturn(imgBytes);
        when(attachmentService.download("0/doc.pdf")).thenReturn(docBytes);
        when(imageProcessor.processImages(List.of(imgBytes), List.of("a.png"), null))
                .thenReturn(List.of(new ImageCaptionResult("图片1:柱状图", "a.png")));
        List<DocumentLocalChunk> chunks = List.of(new DocumentLocalChunk("课程大纲", new float[] {1f}, 0));
        when(documentProcessor.processDocument(docBytes, "doc.pdf")).thenReturn(chunks);

        // When
        AttachmentContext ctx = orchestrator.process(
                List.of(
                        new AttachmentRecord("image", "0/a.png", "a.png", 1L),
                        new AttachmentRecord("document", "0/doc.pdf", "doc.pdf", 2L)),
                null,
                NOT_CANCELLED);

        // Then: 两个处理器各执行一次，context 两边齐全
        verify(imageProcessor).processImages(List.of(imgBytes), List.of("a.png"), null);
        verify(documentProcessor).processDocument(docBytes, "doc.pdf");
        assertEquals(1, ctx.captions().size());
        assertTrue(ctx.documents().containsKey("0/doc.pdf"));
    }

    @Test
    @DisplayName("单项失败 — 跳过失败附件，其余附件正常处理（不中断）")
    void process_partialFailure_skipsFailedAndProcessesOthers() {
        // Given: 第一张图正常、第二张图下载抛异常
        byte[] good = new byte[] {7};
        when(attachmentService.download("0/good.png")).thenReturn(good);
        when(attachmentService.download("0/bad.png")).thenThrow(new RuntimeException("MinIO 不可用"));
        when(imageProcessor.processImages(List.of(good), List.of("good.png"), null))
                .thenReturn(List.of(new ImageCaptionResult("图片1:正常图", "good.png")));

        // When
        AttachmentContext ctx = orchestrator.process(
                List.of(
                        new AttachmentRecord("image", "0/good.png", "good.png", 1L),
                        new AttachmentRecord("image", "0/bad.png", "bad.png", 1L)),
                null,
                NOT_CANCELLED);

        // Then: 失败项跳过（不抛异常），正常图片仍完成 caption
        assertEquals(1, ctx.captions().size());
        assertEquals("图片1:正常图", ctx.captions().get(0).caption());
        assertTrue(ctx.hasAny());
    }

    @Test
    @DisplayName("全部失败 — 返回 empty，不中断调用方")
    void process_allFailures_returnsEmpty() {
        // Given: 所有附件下载失败
        when(attachmentService.download(anyString())).thenThrow(new RuntimeException("MinIO 不可用"));

        // When
        AttachmentContext ctx = orchestrator.process(
                List.of(
                        new AttachmentRecord("image", "0/a.png", "a.png", 1L),
                        new AttachmentRecord("document", "0/b.pdf", "b.pdf", 2L)),
                null,
                NOT_CANCELLED);

        // Then: 无任何 captions/documents，处理器均不触发
        assertFalse(ctx.hasAny());
        assertTrue(ctx.captions().isEmpty());
        assertTrue(ctx.documents().isEmpty());
        verify(imageProcessor, never()).processImages(anyList(), anyList(), any());
        verify(documentProcessor, never()).processDocument(any(), any());
    }

    @Test
    @DisplayName("空附件列表 — 直接返回 empty，不访问服务与处理器")
    void process_emptyAttachments_returnsEmpty() {
        // When
        AttachmentContext ctx = orchestrator.process(List.of(), null, NOT_CANCELLED);

        // Then: empty 且下游零交互（无多余下载/处理）
        assertFalse(ctx.hasAny());
        assertTrue(ctx.captions().isEmpty());
        assertTrue(ctx.documents().isEmpty());
        verifyNoInteractions(attachmentService, imageProcessor, documentProcessor);
    }

    @Test
    @DisplayName("null 附件列表 — 返回 empty，不抛异常")
    void process_nullAttachments_returnsEmpty() {
        // When
        AttachmentContext ctx = orchestrator.process(null, null, NOT_CANCELLED);

        // Then: null 入参按空处理
        assertFalse(ctx.hasAny());
        verifyNoInteractions(attachmentService, imageProcessor, documentProcessor);
    }

    @Test
    @DisplayName("附件级并行 — 多附件下载并发执行（latch 互等证明，串行实现会超时丢附件）")
    void process_parallelDownloads_attachmentsProcessedConcurrently() throws Exception {
        // 两附件下载互相等待对方开跑（latch(2)）：只有真并行才都能通过；串行则第一个永久等待，
        // allOf 超时后该附件被丢弃 → documents 缺失 → 断言失败（确定性区分并行/串行，无 flaky）
        CountDownLatch bothStarted = new CountDownLatch(2);
        when(attachmentService.download("0/doc1.pdf")).thenAnswer(inv -> {
            bothStarted.countDown();
            bothStarted.await(5, TimeUnit.SECONDS);
            return new byte[] {1};
        });
        when(attachmentService.download("0/doc2.pdf")).thenAnswer(inv -> {
            bothStarted.countDown();
            bothStarted.await(5, TimeUnit.SECONDS);
            return new byte[] {2};
        });
        when(documentProcessor.processDocument(any(), any()))
                .thenReturn(List.of(new DocumentLocalChunk("内容", new float[] {1f}, 0)));

        ExecutorService realPool = Executors.newFixedThreadPool(4);
        try {
            AttachmentOrchestrator parallelOrchestrator =
                    new AttachmentOrchestrator(attachmentService, imageProcessor, documentProcessor, realPool, PROPS);

            AttachmentContext ctx = parallelOrchestrator.process(
                    List.of(
                            new AttachmentRecord("document", "0/doc1.pdf", "doc1.pdf", 1L),
                            new AttachmentRecord("document", "0/doc2.pdf", "doc2.pdf", 1L)),
                    null,
                    NOT_CANCELLED);

            // 两附件均完成 = 两个下载确实并发执行（任一串行等待都会触发总超时被丢弃）
            assertTrue(ctx.documents().containsKey("0/doc1.pdf"), "附件1 应完成处理");
            assertTrue(ctx.documents().containsKey("0/doc2.pdf"), "附件2 应完成处理");
        } finally {
            realPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("总超时 — 慢附件超时丢弃，已完成附件结果保留（不永久阻塞）")
    void process_timeout_skipsSlowAttachmentKeepsCompleted() throws Exception {
        // 附件A 立即返回；附件B 永久挂起（latch 不释放），总超时 300ms 后 B 被丢弃、A 保留
        when(attachmentService.download("0/fast.pdf")).thenReturn(new byte[] {1});
        CountDownLatch neverRelease = new CountDownLatch(1);
        when(attachmentService.download("0/slow.pdf")).thenAnswer(inv -> {
            neverRelease.await(5, TimeUnit.SECONDS);
            return new byte[] {2};
        });
        when(documentProcessor.processDocument(any(), any()))
                .thenReturn(List.of(new DocumentLocalChunk("快附件内容", new float[] {1f}, 0)));

        ExecutorService realPool = Executors.newFixedThreadPool(2);
        try {
            AttachmentOrchestrator timeoutOrchestrator = new AttachmentOrchestrator(
                    attachmentService, imageProcessor, documentProcessor, realPool, propsWithTimeout(300));

            AttachmentContext ctx = timeoutOrchestrator.process(
                    List.of(
                            new AttachmentRecord("document", "0/fast.pdf", "fast.pdf", 1L),
                            new AttachmentRecord("document", "0/slow.pdf", "slow.pdf", 1L)),
                    null,
                    NOT_CANCELLED);

            assertTrue(ctx.documents().containsKey("0/fast.pdf"), "已完成的快附件结果应保留");
            assertFalse(ctx.documents().containsKey("0/slow.pdf"), "超时的慢附件应被丢弃");
        } finally {
            realPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("图片顺序 — 下载完成乱序时图片字节仍按附件原始顺序交 processor（序号语义保障）")
    void process_imagesCollectedInOriginalOrder_whenDownloadsFinishOutOfOrder() throws Exception {
        // 附件1（图片字节 [1]）下载慢 300ms，附件2（字节 [2]）立即完成——完成顺序与原始顺序相反
        when(attachmentService.download("0/slow.png")).thenAnswer(inv -> {
            Thread.sleep(300);
            return new byte[] {1};
        });
        when(attachmentService.download("0/fast.png")).thenReturn(new byte[] {2});
        when(imageProcessor.processImages(anyList(), anyList(), isNull()))
                .thenReturn(List.of(
                        new ImageCaptionResult("图片1:慢图", "slow.png"), new ImageCaptionResult("图片2:快图", "fast.png")));

        ExecutorService realPool = Executors.newFixedThreadPool(2);
        try {
            AttachmentOrchestrator parallelOrchestrator =
                    new AttachmentOrchestrator(attachmentService, imageProcessor, documentProcessor, realPool, PROPS);

            AttachmentContext ctx = parallelOrchestrator.process(
                    List.of(
                            new AttachmentRecord("image", "0/slow.png", "slow.png", 1L),
                            new AttachmentRecord("image", "0/fast.png", "fast.png", 1L)),
                    null,
                    NOT_CANCELLED);

            // 交给 imageProcessor 的字节/文件名列表必须按原始附件顺序（后完成的慢图在前）
            ArgumentCaptor<List<byte[]>> bytesCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<List<String>> namesCaptor = ArgumentCaptor.forClass(List.class);
            verify(imageProcessor).processImages(bytesCaptor.capture(), namesCaptor.capture(), isNull());
            assertArrayEquals(
                    new byte[][] {new byte[] {1}, new byte[] {2}},
                    bytesCaptor.getValue().toArray());
            assertEquals(List.of("slow.png", "fast.png"), namesCaptor.getValue());
            // captions 按原始顺序组装（图片1=慢图）
            assertEquals(2, ctx.captions().size());
            assertEquals("图片1:慢图", ctx.captions().get(0).caption());
            assertEquals("图片2:快图", ctx.captions().get(1).caption());
        } finally {
            realPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("池满拒绝 — 提交被拒的附件快速跳过，不抛异常不影响调用方")
    void process_poolRejected_skipsAttachmentWithoutThrowing() {
        // 执行器提交即拒绝（模拟 AbortPolicy 池满）：所有附件按失败跳过，返回 empty 不抛异常
        Executor rejectedExecutor = command -> {
            throw new RejectedExecutionException("附件池已满");
        };
        AttachmentOrchestrator rejectedOrchestrator = new AttachmentOrchestrator(
                attachmentService, imageProcessor, documentProcessor, rejectedExecutor, PROPS);

        AttachmentContext ctx = rejectedOrchestrator.process(
                List.of(
                        new AttachmentRecord("image", "0/a.png", "a.png", 1L),
                        new AttachmentRecord("document", "0/b.pdf", "b.pdf", 2L)),
                null,
                NOT_CANCELLED);

        assertFalse(ctx.hasAny(), "全部提交被拒应返回空上下文");
        verifyNoInteractions(imageProcessor, documentProcessor);
    }

    @Test
    @DisplayName("思考流式接线 — ThinkingPusher 原样透传给 imageProcessor（caption reasoning 推送通道）")
    void process_thinkingPusher_forwardedToImageProcessor() {
        // Given: SSE 链路场景 worker 传入非空 pusher
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        byte[] bytes = new byte[] {9};
        when(attachmentService.download("0/a.png")).thenReturn(bytes);
        when(imageProcessor.processImages(List.of(bytes), List.of("a.png"), pusher))
                .thenReturn(List.of(new ImageCaptionResult("图片1:图", "a.png")));

        // When
        AttachmentContext ctx = orchestrator.process(
                List.of(new AttachmentRecord("image", "0/a.png", "a.png", 1L)), pusher, NOT_CANCELLED);

        // Then: 同一 pusher 实例交给处理器（流式 VLM 由此推送 attachments 阶段思考），结果语义不变
        verify(imageProcessor).processImages(List.of(bytes), List.of("a.png"), pusher);
        assertEquals(1, ctx.captions().size());
        assertEquals("图片1:图", ctx.captions().get(0).caption());
    }

    @Test
    @DisplayName("取消检查点 — 取消源恒真时零提交（不下载不处理，返回 empty 不抛异常）")
    void process_alreadyCancelled_skipsAllAttachments() {
        // When: 取消标志在首个文件提交前即为 true（worker 已收到 cancel 请求）
        AttachmentContext ctx = orchestrator.process(
                List.of(
                        new AttachmentRecord("image", "0/a.png", "a.png", 1L),
                        new AttachmentRecord("document", "0/b.pdf", "b.pdf", 2L)),
                null,
                () -> true);

        // Then: 全部附件跳过——不再为不会再消费的附件耗下载/VLM 配额，空上下文交调用方正常收敛
        assertFalse(ctx.hasAny());
        verifyNoInteractions(attachmentService, imageProcessor, documentProcessor);
    }

    @Test
    @DisplayName("取消检查点 — 批中取消只跳过剩余文件，已提交附件结果照常组装")
    void process_cancelledMidBatch_skipsRemainingFilesOnly() {
        // Given: 两文档附件，取消源第一次检查（文件1 提交前）为 false、第二次（文件2 提交前）为 true
        byte[] doc1 = new byte[] {1};
        when(attachmentService.download("0/doc1.pdf")).thenReturn(doc1);
        when(documentProcessor.processDocument(doc1, "doc1.pdf"))
                .thenReturn(List.of(new DocumentLocalChunk("内容1", new float[] {1f}, 0)));
        AtomicInteger checks = new AtomicInteger();
        BooleanSupplier cancelAfterFirst = () -> checks.incrementAndGet() > 1;

        // When（同步执行器：批循环逐个提交，检查点顺序确定）
        AttachmentContext ctx = orchestrator.process(
                List.of(
                        new AttachmentRecord("document", "0/doc1.pdf", "doc1.pdf", 1L),
                        new AttachmentRecord("document", "0/doc2.pdf", "doc2.pdf", 2L)),
                null,
                cancelAfterFirst);

        // Then: 文件1 正常处理入上下文；文件2 未提交——不下载、不处理（never 断言取消即时性）
        assertTrue(ctx.documents().containsKey("0/doc1.pdf"));
        assertFalse(ctx.documents().containsKey("0/doc2.pdf"));
        verify(attachmentService, never()).download("0/doc2.pdf");
        verify(documentProcessor, never()).processDocument(any(), eq("doc2.pdf"));
    }

    @Test
    @DisplayName("AttachmentContext.empty — hasAny 恒为 false，两栏均为空")
    void emptyContext_hasAnyFalse() {
        // When
        AttachmentContext ctx = AttachmentContext.empty();

        // Then: 无附件上下文
        assertFalse(ctx.hasAny());
        assertTrue(ctx.captions().isEmpty());
        assertTrue(ctx.documents().isEmpty());
    }

    @Test
    @DisplayName("AttachmentContext.hasAny — 仅含 captions 或仅含 documents 均判定有附件上下文")
    void hasAny_returnsTrueWhenEitherPresent() {
        // When: 仅 captions
        AttachmentContext onlyCaptions =
                new AttachmentContext(List.of(new ImageCaptionResult("图片1:图", "a.png")), Map.of());
        assertTrue(onlyCaptions.hasAny());

        // When: 仅 documents
        AttachmentContext onlyDocs = new AttachmentContext(
                List.of(), Map.of("0/a.pdf", List.of(new DocumentLocalChunk("内容", new float[] {1f}, 0))));
        assertTrue(onlyDocs.hasAny());
    }
}
