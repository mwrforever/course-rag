package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ImageFilter 单元测试 —— 小图标 / 装饰图过滤
 *
 * @author commerce-rag
 */
class ImageFilterTest {

    @Test
    @DisplayName("isSmallIcon — 小于阈值的字节跳过（含边界）")
    void isSmallIcon_boundary() {
        assertTrue(ImageFilter.isSmallIcon(new byte[10 * 1024 - 1], 10));
        assertFalse(ImageFilter.isSmallIcon(new byte[10 * 1024], 10));
    }

    @Test
    @DisplayName("isDecorative — 全透明 alpha 图片为装饰图")
    void isDecorative_fullyTransparent() throws Exception {
        byte[] png = pngOf((g, w, h) -> {
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, w, h);
        });

        assertTrue(ImageFilter.isDecorative(png));
    }

    @Test
    @DisplayName("isDecorative — 单一纯色 alpha 图片为装饰图")
    void isDecorative_singleColor() throws Exception {
        byte[] png = pngOf((g, w, h) -> {
            g.setColor(new Color(255, 0, 0, 255));
            g.fillRect(0, 0, w, h);
        });

        assertTrue(ImageFilter.isDecorative(png));
    }

    @Test
    @DisplayName("isDecorative — 多颜色有效图片不误杀")
    void isDecorative_multiColor_notDecorative() throws Exception {
        byte[] png = pngOf((g, w, h) -> {
            g.setColor(Color.RED);
            g.fillRect(0, 0, w / 2, h);
            g.setColor(Color.BLUE);
            g.fillRect(w / 2, 0, w / 2, h);
        });

        assertFalse(ImageFilter.isDecorative(png));
    }

    @Test
    @DisplayName("isDecorative — 损坏字节不按装饰图处理（不误杀）")
    void isDecorative_corruptBytes_false() {
        assertFalse(ImageFilter.isDecorative(new byte[] {1, 2, 3, 4}));
    }

    @Test
    @DisplayName("isDecorative — 无 alpha 通道图片直接放行")
    void isDecorative_noAlpha_false() throws Exception {
        // TYPE_INT_RGB 无 alpha 通道：PNG 编码后解码 getColorModel().hasAlpha()=false，
        // isDecorative 直接放行（单色但无 alpha 不判装饰图，避免误杀 JPEG 等无透明通道图片）
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);

        assertFalse(ImageFilter.isDecorative(out.toByteArray()));
    }

    /** 生成指定绘制逻辑的 PNG 字节 */
    private static byte[] pngOf(Painter painter) throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        painter.paint(image.createGraphics(), 32, 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private interface Painter {
        void paint(Graphics2D g, int w, int h);
    }
}
