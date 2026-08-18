package com.commerce.rag.etl;

/**
 * Token 数量估算器 —— 粗略估算（中文 1 字 ≈ 1 token，英文 4 字符 ≈ 1 token）
 *
 * <p>用于分片 token_count 字段与表格行分组 token 上限判断（非精确计费口径）。
 * 原 EtlPipeline 私有方法 estimateTokens 上提至此，供分片器与表格分片器共用。
 *
 * @author commerce-rag
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /**
     * 估算文本 token 数
     *
     * @param text 文本（可为空）
     * @return 估算 token 数（空文本为 0）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cnCount = 0;
        int enCount = 0;
        for (char c : text.toCharArray()) {
            if (c > 127) {
                cnCount++;
            } else {
                enCount++;
            }
        }
        return cnCount + enCount / 4;
    }
}
