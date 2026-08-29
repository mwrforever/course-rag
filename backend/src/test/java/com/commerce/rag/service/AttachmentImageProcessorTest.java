package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.etl.ImageCaptionService;
import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.record.ImageCaptionResult;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.ThinkingPusher;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 图片附件 caption 处理测试（多图标注/缓存命中/过滤；P2-2 并行 caption 后序号按原始位置）
 *
 * <p>语义用例使用同步执行器（Runnable::run）验证过滤/缓存/失败语义；
 * 并行用例使用真实线程池验证 caption 完成乱序时「图片N」序号仍按上传顺序分配。
 */
class AttachmentImageProcessorTest {

    /** 正常图片最小字节数（小于 10KB 会被 ImageFilter 当小图标过滤，走不到 VLM caption） */
    private static final int NORMAL_IMAGE_BYTES = 20480;

    /** 测试配置：caption 并行总超时 60s（同步执行器语义用例不触发超时） */
    private static final AttachmentProperties PROPS = new AttachmentProperties(
            10,
            50,
            10,
            100,
            100,
            30,
            16,
            60000,
            new AttachmentProperties.Executor(2, 4, 20, "attachment-test-"),
            "qwen3.7-max-2026-06-08");

    /** 构造一张不小于 10KB 的正常图片字节（不同 size 内容不同，hash 不同，互不命中缓存） */
    private static byte[] normalImage(int size) {
        return new byte[size];
    }

    @Test
    @DisplayName("多图 — 每张生成 caption，标注图片1/图片2（按上传顺序）")
    void processImages_multiCaptions() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("红色图表", "蓝色图表");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        List<ImageCaptionResult> results = processor.processImages(
                List.of(normalImage(NORMAL_IMAGE_BYTES), normalImage(NORMAL_IMAGE_BYTES + 1)),
                List.of("a.png", "b.png"),
                null,
                null);

        assertEquals(2, results.size());
        assertEquals("图片1:红色图表", results.get(0).caption());
        assertEquals("图片2:蓝色图表", results.get(1).caption());
    }

    @Test
    @DisplayName("带 ThinkingPusher — VLM 走 captionStreaming（透传 pusher 与批级标志），caption 结果语义与同步路径一致")
    void processImages_withPusher_usesCaptionStreaming() {
        // Given: SSE 链路场景传入 per-run pusher
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        when(captionService.captionStreaming(any(), any(), same(pusher), any(), any()))
                .thenReturn("流式图表描述");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        // When
        List<ImageCaptionResult> results =
                processor.processImages(List.of(normalImage(NORMAL_IMAGE_BYTES)), List.of("a.png"), pusher, null);

        // Then: 走流式聚合方法且透传同一 pusher；「图片N:描述」组装语义不变
        assertEquals(1, results.size());
        assertEquals("图片1:流式图表描述", results.get(0).caption());
        verify(captionService).captionStreaming(any(), eq("image/png"), same(pusher), any(AtomicBoolean.class), any());
        verify(captionService, never()).caption(any(), any());
    }

    @Test
    @DisplayName("多图 reasoning 乱序完成 — 批级收口：END(attachments) 恰好一次（评审 I-2 stage 成对契约）")
    void processImages_reasoningOutOfOrder_endsExactlyOnce() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        // 桩模拟真实 captionStreaming 行为：推 reasoning + 置批级标志；图1 慢、图2 快 → 完成顺序颠倒
        when(captionService.captionStreaming(any(), any(), same(pusher), any(), any()))
                .thenAnswer(inv -> {
                    ThinkingPusher p = inv.getArgument(2);
                    AtomicBoolean reasoningSeenAny = inv.getArgument(3);
                    byte[] bytes = inv.getArgument(0);
                    if (bytes.length == NORMAL_IMAGE_BYTES) {
                        reasoningSeenAny.set(true);
                        p.push(SseEventTransformer.STAGE_ATTACHMENTS, "慢图思考");
                        Thread.sleep(200);
                    } else {
                        reasoningSeenAny.set(true);
                        p.push(SseEventTransformer.STAGE_ATTACHMENTS, "快图思考");
                    }
                    return "流式描述";
                });
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        ExecutorService realPool = Executors.newFixedThreadPool(2);
        try {
            AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, realPool, PROPS);

            List<ImageCaptionResult> results = processor.processImages(
                    List.of(normalImage(NORMAL_IMAGE_BYTES), normalImage(NORMAL_IMAGE_BYTES + 1)),
                    List.of("slow.png", "fast.png"),
                    pusher,
                    null);

            // 两图 reasoning 均实时推送（stage 不变），但无论完成顺序如何 END 只在批完成点补一次
            assertEquals(2, results.size());
            verify(pusher, times(2)).push(eq(SseEventTransformer.STAGE_ATTACHMENTS), anyString());
            verify(pusher, times(1)).end(SseEventTransformer.STAGE_ATTACHMENTS);
        } finally {
            realPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("批内零 reasoning（非思考响应）— pusher 非空也不发孤儿 END(attachments)")
    void processImages_batchWithoutReasoning_neverEnds() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        // 桩模拟非思考模型：只返回 content、不推 reasoning、不置批级标志
        when(captionService.captionStreaming(any(), any(), same(pusher), any(), any()))
                .thenReturn("纯回答描述");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        List<ImageCaptionResult> results = processor.processImages(
                List.of(normalImage(NORMAL_IMAGE_BYTES), normalImage(NORMAL_IMAGE_BYTES + 1)),
                List.of("a.png", "b.png"),
                pusher,
                null);

        assertEquals(2, results.size());
        verify(pusher, never()).push(any(), any());
        verify(pusher, never()).end(any());
    }

    @Test
    @DisplayName("pusher=null 调用 — 保持同步 caption 路径零变化（不调 captionStreaming）")
    void processImages_withoutPusher_keepsSyncCaption() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("同步描述");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        // When: 唯一入口显式传 pusher=null（两参死重载已随评审 M-3 删除）
        List<ImageCaptionResult> results =
                processor.processImages(List.of(normalImage(NORMAL_IMAGE_BYTES + 7)), List.of("a.png"), null, null);

        // Then: 仍走原同步 caption，不触流式方法（离线/测试兼容承诺）
        assertEquals(1, results.size());
        assertEquals("图片1:同步描述", results.get(0).caption());
        verify(captionService).caption(any(), any());
        verify(captionService, never()).captionStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("同图重复出现 — Caffeine 命中，caption 只调一次")
    void processImages_cacheHit() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("同一张图");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        byte[] img = normalImage(NORMAL_IMAGE_BYTES + 2);
        processor.processImages(List.of(img), List.of("a.png"), null, null);
        processor.processImages(List.of(img), List.of("a.png"), null, null);

        assertEquals(
                1,
                Mockito.mockingDetails(captionService).getInvocations().size(),
                "同图第二次调用命中 Caffeine 缓存，caption 只调用一次");
    }

    @Test
    @DisplayName("caption 失败 — 跳过该图不中断，其他图正常")
    void processImages_captionFailSkip() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any()))
                .thenThrow(new RuntimeException("模型调用失败"))
                .thenReturn("正常图");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        List<ImageCaptionResult> results = processor.processImages(
                List.of(normalImage(NORMAL_IMAGE_BYTES + 3), normalImage(NORMAL_IMAGE_BYTES + 4)),
                List.of("bad.png", "ok.png"),
                null,
                null);

        assertEquals(1, results.size());
        assertEquals("图片2:正常图", results.get(0).caption());
    }

    @Test
    @DisplayName("全部小图标 — 过滤分支返回空列表且不触发 VLM caption")
    void processImages_allSmallIconFiltered() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        // 两张均小于 10KB，isSmallIcon(bytes, 10) 恒 true，captionInternal 在过滤分支返回 null
        List<ImageCaptionResult> results = processor.processImages(
                List.of(normalImage(1024), normalImage(2048)), List.of("icon.png", "icon2.png"), null, null);

        assertTrue(results.isEmpty(), "小图标全部被过滤，不产生任何 caption 结果");
        // 过滤发生在 VLM 调用之前，caption 一次都不应被触发
        verify(captionService, never()).caption(any(), any());
    }

    @Test
    @DisplayName("小图 + 正常图混合 — 小图被过滤但序号递增，正常图标注图片2")
    void processImages_smallFilteredThenNormal() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("正常图描述");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        // 第一张 < 10KB 被过滤（不产生结果但序号 +1），第二张正常生成并占用下一个序号
        List<ImageCaptionResult> results = processor.processImages(
                List.of(normalImage(1024), normalImage(NORMAL_IMAGE_BYTES)),
                List.of("small.png", "big.png"),
                null,
                null);

        assertEquals(1, results.size());
        assertEquals("图片2:正常图描述", results.get(0).caption());
    }

    @Test
    @DisplayName("不同文件后缀 — mimeOf 各分支正确映射并逐张 caption")
    void processImages_mimeBranches() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("描述");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        // 全部为正常大小，覆盖 mimeOf 的 .jpg/.gif/.webp/.bmp 及未知后缀回退分支
        ArgumentCaptor<String> mimeCaptor = ArgumentCaptor.forClass(String.class);
        List<ImageCaptionResult> results = processor.processImages(
                List.of(
                        normalImage(NORMAL_IMAGE_BYTES),
                        normalImage(NORMAL_IMAGE_BYTES + 1),
                        normalImage(NORMAL_IMAGE_BYTES + 2),
                        normalImage(NORMAL_IMAGE_BYTES + 3),
                        normalImage(NORMAL_IMAGE_BYTES + 4)),
                List.of("a.jpg", "a.gif", "a.webp", "a.bmp", "a.unknownext"),
                null,
                null);

        // 5 张全为正常大小，全部生成结果，序号按上传顺序递增
        assertEquals(5, results.size());
        assertEquals("图片1:描述", results.get(0).caption());
        assertEquals("图片5:描述", results.get(4).caption());
        // 验证传给 VLM 的 MIME 与各后缀分支对应（未知后缀回退默认 image/jpeg）
        verify(captionService, times(5)).caption(any(), mimeCaptor.capture());
        assertEquals(
                List.of("image/jpeg", "image/gif", "image/webp", "image/bmp", "image/jpeg"), mimeCaptor.getAllValues());
    }

    @Test
    @DisplayName("并行 caption 完成乱序 — 序号仍按上传顺序分配（图片N 不随完成顺序漂移）")
    void processImages_parallelCaptions_indexByOriginalPosition() throws Exception {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        // 第一张 caption 慢（300ms）、第二张立即完成——完成顺序与上传顺序相反
        when(captionService.caption(any(), any())).thenAnswer(inv -> {
            byte[] bytes = inv.getArgument(0);
            if (bytes.length == NORMAL_IMAGE_BYTES) {
                Thread.sleep(300);
                return "慢图描述";
            }
            return "快图描述";
        });
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        ExecutorService realPool = Executors.newFixedThreadPool(2);
        try {
            AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, realPool, PROPS);

            List<ImageCaptionResult> results = processor.processImages(
                    List.of(normalImage(NORMAL_IMAGE_BYTES), normalImage(NORMAL_IMAGE_BYTES + 1)),
                    List.of("slow.png", "fast.png"),
                    null,
                    null);

            assertEquals(2, results.size());
            // 序号按原始上传位置：位置 1 的慢图占「图片1」，位置 2 的快图占「图片2」
            assertEquals("图片1:慢图描述", results.get(0).caption());
            assertEquals("图片2:快图描述", results.get(1).caption());
        } finally {
            realPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并行单飞失败 — 异常图跳过不中断，正常图按原始位置序号保留")
    void processImages_parallelSingleFailure_isolatedWithOriginalIndex() throws Exception {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        // 第一张（位置 1）caption 抛异常，第二张（位置 2）慢速成功——失败不吞掉后续结果
        when(captionService.caption(any(), any())).thenAnswer(inv -> {
            byte[] bytes = inv.getArgument(0);
            if (bytes.length == NORMAL_IMAGE_BYTES) {
                throw new RuntimeException("模型调用失败");
            }
            Thread.sleep(100);
            return "正常图";
        });
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        ExecutorService realPool = Executors.newFixedThreadPool(2);
        try {
            AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, realPool, PROPS);

            List<ImageCaptionResult> results = processor.processImages(
                    List.of(normalImage(NORMAL_IMAGE_BYTES), normalImage(NORMAL_IMAGE_BYTES + 1)),
                    List.of("bad.png", "ok.png"),
                    null,
                    null);

            assertEquals(1, results.size(), "失败图跳过，正常图保留");
            // 失败图占位序号 1，正常图按原始位置标注「图片2」（不因前面失败而前移）
            assertEquals("图片2:正常图", results.get(0).caption());
        } finally {
            realPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("空图片列表 — 直接返回空结果，不进入超时等待也不触发 VLM")
    void processImages_emptyImages_returnsEmptyImmediately() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        List<ImageCaptionResult> results = processor.processImages(List.of(), List.of(), null, null);

        assertTrue(results.isEmpty(), "无图片时直接返回空列表");
        verify(captionService, never()).caption(any(), any());
    }

    @Test
    @DisplayName("池满提交被拒 — 被拒图片快速跳过不抛出，其余图片正常 caption")
    void processImages_poolSaturated_rejectedImageSkippedOthersProceed() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("正常图");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        // 首次提交照常执行（同步），后续提交抛拒绝异常模拟附件池打满
        AtomicInteger submissions = new AtomicInteger();
        Executor rejectingPool = task -> {
            if (submissions.incrementAndGet() > 1) {
                throw new RejectedExecutionException("附件池已满");
            }
            task.run();
        };
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, rejectingPool, PROPS);

        List<ImageCaptionResult> results = processor.processImages(
                List.of(normalImage(NORMAL_IMAGE_BYTES), normalImage(NORMAL_IMAGE_BYTES + 1)),
                List.of("first.png", "second.png"),
                null,
                null);

        // 第一张正常 caption；第二张提交被拒仅自身跳过（序号 2 占位不产出），不中断第一张
        assertEquals(1, results.size(), "被拒图片跳过，已提交图片照常处理");
        assertEquals("图片1:正常图", results.get(0).caption());
        verify(captionService, times(1)).caption(any(), any());
    }

    @Test
    @DisplayName("总超时 — 慢图超时被取消丢弃，已完成的快图按原始序号保留")
    void processImages_timeout_slowCancelledFastKept() throws Exception {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        // 位置 1 为慢图（sleep 5s 远超总超时），位置 2 立即完成——验证超时只丢弃慢图
        when(captionService.caption(any(), any())).thenAnswer(inv -> {
            byte[] bytes = inv.getArgument(0);
            if (bytes.length == NORMAL_IMAGE_BYTES) {
                Thread.sleep(5000);
                return "慢图描述";
            }
            return "快图描述";
        });
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        ExecutorService realPool = Executors.newFixedThreadPool(2);
        try {
            // 总超时压到 200ms：快图来得及完成，慢图在超时后被取消
            AttachmentProperties shortTimeoutProps = new AttachmentProperties(
                    10,
                    50,
                    10,
                    100,
                    100,
                    30,
                    16,
                    200,
                    new AttachmentProperties.Executor(2, 4, 20, "attachment-timeout-test-"),
                    "qwen3.7-max-2026-06-08");
            AttachmentImageProcessor processor =
                    new AttachmentImageProcessor(captionService, cache, realPool, shortTimeoutProps);

            List<ImageCaptionResult> results = processor.processImages(
                    List.of(normalImage(NORMAL_IMAGE_BYTES), normalImage(NORMAL_IMAGE_BYTES + 1)),
                    List.of("slow.png", "fast.png"),
                    null,
                    null);

            // 慢图超时丢弃（无结果），快图保留且序号仍为原始位置 2
            assertEquals(1, results.size(), "慢图超时被丢弃，快图保留");
            assertEquals("图片2:快图描述", results.get(0).caption());
        } finally {
            realPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("调用线程被中断 — 取消未完成 caption 并交还中断标记，未完成结果丢弃")
    void processImages_interrupted_cancelsPendingAndRestoresFlag() throws Exception {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        CountDownLatch captionStarted = new CountDownLatch(1);
        // 唯一图片挂起 10s 直到被取消中断，模拟 SSE 请求线程在 caption 期间被取消
        when(captionService.caption(any(), any())).thenAnswer(inv -> {
            captionStarted.countDown();
            Thread.sleep(10000);
            return "未完成描述";
        });
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        ExecutorService realPool = Executors.newFixedThreadPool(1);
        try {
            AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, realPool, PROPS);
            Thread caller = Thread.currentThread();
            // caption 开跑后 150ms 中断调用线程（模拟客户端断开取消请求）
            Thread interrupter = new Thread(() -> {
                try {
                    captionStarted.await();
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                caller.interrupt();
            });
            interrupter.setDaemon(true);
            interrupter.start();

            List<ImageCaptionResult> results = processor.processImages(
                    List.of(normalImage(NORMAL_IMAGE_BYTES)), List.of("pending.png"), null, null);

            interrupter.join(1000);
            // 唯一图片未完成被取消 → 空结果；中断标记交还调用线程（消费掉避免污染后续用例）
            assertTrue(results.isEmpty(), "未完成 caption 被取消，不产生结果");
            assertTrue(Thread.interrupted(), "中断标记应交还调用线程");
        } finally {
            realPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("大体积单色装饰图 — 通过体积阈值仍被装饰图规则过滤，不触发 VLM")
    void processImages_largeDecorativePng_filteredWithoutVlm() throws Exception {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        // 构造 ≥10KB、带 alpha 且有效颜色唯一的 PNG：体积过阈值但命中装饰图规则（大 logo/分割线场景）
        List<ImageCaptionResult> results =
                processor.processImages(List.of(decorativePng()), List.of("divider.png"), null, null);

        assertTrue(results.isEmpty(), "单色装饰图应被过滤，不产生 caption 结果");
        verify(captionService, never()).caption(any(), any());
    }

    @Test
    @DisplayName("jpeg 后缀 — 与 jpg 同映射 image/jpeg，VLM 收到兼容 MIME")
    void processImages_jpegSuffix_mapsToJpegMime() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("照片描述");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        List<ImageCaptionResult> results =
                processor.processImages(List.of(normalImage(NORMAL_IMAGE_BYTES)), List.of("photo.jpeg"), null, null);

        assertEquals(1, results.size());
        assertEquals("图片1:照片描述", results.get(0).caption());
        ArgumentCaptor<String> mimeCaptor = ArgumentCaptor.forClass(String.class);
        verify(captionService).caption(any(), mimeCaptor.capture());
        assertEquals("image/jpeg", mimeCaptor.getValue());
    }

    /**
     * 生成「通过体积阈值但命中装饰图规则」的 PNG
     *
     * <p>带 alpha 通道（TYPE_INT_ARGB）、有效颜色唯一（RGB 固定绿色）满足 isDecorative 判定；
     * alpha 通道随机化使字节流不可压缩，确保体积远超 10KB 的小图标阈值，
     * 从而走到 isSmallIcon 为 false 后的装饰图检测分支。
     */
    private static byte[] decorativePng() throws Exception {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(20260823);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                // RGB 固定绿色（有效颜色唯一）、alpha 随机（保证编码后体积 ≥10KB）
                image.setRGB(x, y, (random.nextInt(256) << 24) | 0x00FF00);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
