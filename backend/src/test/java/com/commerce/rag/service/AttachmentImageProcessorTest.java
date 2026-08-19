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
import com.commerce.rag.record.ImageCaptionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** 图片附件 caption 处理测试（多图标注/缓存命中/过滤） */
class AttachmentImageProcessorTest {

    /** 正常图片最小字节数（小于 10KB 会被 ImageFilter 当小图标过滤，走不到 VLM caption） */
    private static final int NORMAL_IMAGE_BYTES = 20480;

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
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache);

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
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache);

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
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache);

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
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache);

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
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache);

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
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache);

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
}
