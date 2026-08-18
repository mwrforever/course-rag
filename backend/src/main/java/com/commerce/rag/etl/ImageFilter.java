package com.commerce.rag.etl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * 图片过滤器 —— 过滤小图标与装饰图（spec §4.2）
 *
 * <p>规则：<10KB 图标直接跳过；带 alpha 通道且全部透明或仅一种有效颜色的装饰图
 * （PNG 分割线/纯色 logo）跳过。全部为内存级判断，不依赖外部服务。
 *
 * @author commerce-rag
 */
public final class ImageFilter {

    /** 装饰图检测的像素采样上限（控制解码后扫描成本） */
    private static final int MAX_SAMPLE_PIXELS = 4096;

    private ImageFilter() {}

    /**
     * 是否小于最小体积阈值的小图标
     *
     * @param bytes     图片字节
     * @param minSizeKb 最小体积阈值（KB，etl.image-min-size-kb）
     * @return true 表示应跳过
     */
    public static boolean isSmallIcon(byte[] bytes, int minSizeKb) {
        return bytes.length < (long) minSizeKb * 1024;
    }

    /**
     * 是否装饰图 —— 带 alpha 通道且（全部透明 或 仅一种有效颜色）
     *
     * <p>采样检测（上限 MAX_SAMPLE_PIXELS 像素），解码失败返回 false（不因过滤逻辑误杀有效图片）。
     *
     * @param bytes 图片字节
     * @return true 表示应跳过
     */
    public static boolean isDecorative(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null || !image.getColorModel().hasAlpha()) {
                return false;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int stride = (int) Math.max(1, ((long) width * height) / MAX_SAMPLE_PIXELS);
            Set<Integer> opaqueColors = new HashSet<>();
            boolean anyOpaque = false;
            for (int y = 0; y < height; y += stride) {
                for (int x = 0; x < width; x += stride) {
                    int argb = image.getRGB(x, y);
                    if ((argb >>> 24) == 0) {
                        continue; // 全透明像素
                    }
                    anyOpaque = true;
                    opaqueColors.add(argb & 0x00FFFFFF);
                    if (opaqueColors.size() > 1) {
                        return false;
                    }
                }
            }
            return !anyOpaque || opaqueColors.size() <= 1;
        } catch (IOException e) {
            return false;
        }
    }
}
