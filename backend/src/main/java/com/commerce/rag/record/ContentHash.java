package com.commerce.rag.record;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容哈希 —— 归一化文本 + SHA-256 摘要
 *
 * <p>ETL 全局去重（入库硬约束，spec §4.4）与检索侧防御去重（计划 2/5 ContextBuilder
 * 消费）共用；sha256Hex 同时供图片字节级去重使用（同图只处理一次）。
 *
 * @author commerce-rag
 */
public record ContentHash(String sha256, String normalizedText) {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * 计算字节数组的 SHA-256 十六进制摘要
     *
     * @param bytes 原始字节（不允许为空）
     * @return 64 位十六进制小写摘要
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = HEX[(digest[i] >> 4) & 0xF];
                hex[i * 2 + 1] = HEX[digest[i] & 0xF];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 计算文本的 SHA-256 十六进制摘要（UTF-8 编码）
     *
     * @param text 文本（不允许为空）
     * @return 64 位十六进制小写摘要
     */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
