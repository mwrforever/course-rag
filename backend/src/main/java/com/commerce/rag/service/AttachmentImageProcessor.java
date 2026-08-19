package com.commerce.rag.service;

import com.commerce.rag.etl.ImageCaptionService;
import com.commerce.rag.etl.ImageFilter;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 图片附件 caption 处理器 —— 会话级图片统一 VLM caption 链路（spec §5.3）
 *
 * <p>流程：过滤小图标/装饰图 → Caffeine 按字节 hash 缓存（同图只 caption 一次）→
 * 经既有 ImageCaptionService 调 VLM caption（OpenAI 兼容协议，qwen3.7-flash）
 * → 每张图按上传顺序占用一个序号，标注"图片N"。
 *
 * <p>caption 双角色：作为 user-document 内容注入；非闲聊场景下由调用方决定是否作为
 * 查询文本检索系统知识库（Task 9/10 消费）。
 *
 * <p>字节下载由调用方（AttachmentOrchestrator）完成——本类只负责过滤/caption/缓存，
 * 不依赖 IAttachmentService，测试无需 mock MinIO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentImageProcessor {

    /** 图片过滤最小尺寸（KB，与 ETL 同阈值，spec §5.2） */
    private static final int IMAGE_MIN_SIZE_KB = 10;

    private final ImageCaptionService imageCaptionService;
    private final AttachmentCacheService cacheService;

    /**
     * 处理一组图片字节（按上传顺序标注图片1/2…）
     *
     * <p>序号语义：每张图占用一个序号，无论该图 success / caption 失败 / 被过滤——
     * 失败或过滤的图不产生结果但序号仍递增；Caffeine 缓存命中时返回缓存值且不重复
     * 触发 caption，只影响是否产生结果，序号仍按原始位置计算。
     *
     * @param images 图片字节列表（与上传顺序一致）
     * @param names  原始文件名列表（同序，MIME 识别用）
     * @return caption 结果列表（"图片N:描述"；被过滤/失败的图片不产生结果；全部失败返回空列表）
     */
    public List<ImageCaptionResult> processImages(List<byte[]> images, List<String> names) {
        List<ImageCaptionResult> results = new ArrayList<>(images.size());
        int index = 1;
        for (int i = 0; i < images.size(); i++) {
            byte[] bytes = images.get(i);
            String name = names.get(i);
            try {
                String hash = cacheService.computeHash(bytes);
                // 同图只 caption 一次（Caffeine 按字节 hash 缓存，spec §5.1）
                String caption = cacheService.getOrProcess(hash, b -> captionInternal(b, name), bytes);
                if (caption == null) {
                    index++;
                    continue;
                }
                results.add(new ImageCaptionResult("图片" + index + ":" + caption, name));
            } catch (Exception e) {
                // 单图失败跳过，不中断整体（spec §5.3 边界）
                log.warn("图片 caption 处理失败，跳过: name={}, error={}", name, e.getMessage());
            }
            index++;
        }
        return results;
    }

    /** caption 内部逻辑：过滤 → VLM 调用（返回 null 表示被过滤） */
    private String captionInternal(byte[] bytes, String name) {
        if (ImageFilter.isSmallIcon(bytes, IMAGE_MIN_SIZE_KB) || ImageFilter.isDecorative(bytes)) {
            log.info("图片过滤（小图标/装饰图）: name={}", name);
            return null;
        }
        return imageCaptionService.caption(bytes, mimeOf(name));
    }

    /** 按文件名后缀取 MIME（与 ETL extensionOf 同语义） */
    private static String mimeOf(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}
