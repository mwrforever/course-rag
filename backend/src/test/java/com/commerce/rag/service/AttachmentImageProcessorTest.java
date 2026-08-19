package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commerce.rag.etl.ImageCaptionService;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
