package com.commerce.rag.bot.context;

/**
 * Token 估算工具
 *
 * DashScope 未公开 tokenizer，采用启发式估算：
 * - 中文（CJK）字符：约 1 token / 1.5 字符
 * - 英文/数字/符号：约 1 token / 4 字符（GPT 系经验值）
 *
 * 精度约 ±15%，足够做上下文窗口容量管理。
 * 若未来 DashScope 开放 tokenizer，替换本类即可。
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /**
     * 估算文本的 token 数
     *
     * @param text 任意语言文本
     * @return 估算 token 数，null/空 返回 0
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;

        int cjk = 0;    // CJK 字符数
        int other = 0;  // 非 CJK 字符数（英文、数字、标点等）

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }

        // CJK: 约 1.5 字符/token；ASCII: 约 4 字符/token
        return (int) Math.ceil(cjk / 1.5 + other / 4.0);
    }

    /**
     * 估算多条文本的总 token 数
     */
    public static int estimateAll(String... texts) {
        int total = 0;
        for (String t : texts) {
            total += estimate(t);
        }
        return total;
    }

    /** 判断是否为 CJK 字符（中文/日文/韩文） */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
