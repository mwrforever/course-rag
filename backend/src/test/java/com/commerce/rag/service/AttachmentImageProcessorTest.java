package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.etl.ImageCaptionService;
import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            10, 50, 10, 100, 100, 30, 16, 60000, new AttachmentProperties.Executor(2, 4, 20, "attachment-test-"));

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
                List.of("a.png", "b.png"));

        assertEquals(2, results.size());
        assertEquals("图片1:红色图表", results.get(0).caption());
        assertEquals("图片2:蓝色图表", results.get(1).caption());
    }

    @Test
    @DisplayName("同图重复出现 — Caffeine 命中，caption 只调一次")
    void processImages_cacheHit() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("同一张图");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache, Runnable::run, PROPS);

        byte[] img = normalImage(NORMAL_IMAGE_BYTES + 2);
        processor.processImages(List.of(img), List.of("a.png"));
        processor.processImages(List.of(img), List.of("a.png"));

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
                List.of("bad.png", "ok.png"));

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
                List.of(normalImage(1024), normalImage(2048)), List.of("icon.png", "icon2.png"));

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
                List.of(normalImage(1024), normalImage(NORMAL_IMAGE_BYTES)), List.of("small.png", "big.png"));

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
                List.of("a.jpg", "a.gif", "a.webp", "a.bmp", "a.unknownext"));

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
                    List.of("slow.png", "fast.png"));

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
                    List.of("bad.png", "ok.png"));

            assertEquals(1, results.size(), "失败图跳过，正常图保留");
            // 失败图占位序号 1，正常图按原始位置标注「图片2」（不因前面失败而前移）
            assertEquals("图片2:正常图", results.get(0).caption());
        } finally {
            realPool.shutdownNow();
        }
    }
}
