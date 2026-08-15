package com.commerce.rag.bot.graph;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PromptLoader 单元测试 —— YAML 提示词模板加载（flatten 展平 / 叶子值 / 占位符替换）
 *
 * <p>使用 src/main/resources/prompts/ 下的真实模板文件验证加载行为。
 *
 * @author commerce-rag
 */
@DisplayName("PromptLoader 提示词模板加载测试")
class PromptLoaderTest {

    private final PromptLoader promptLoader = new PromptLoader();

    @Test
    @DisplayName("load → 展平加载 query-rewrite.yml 且保留 key 分段标记")
    void load_flattenedTemplateKeepsSectionMarkers() {
        String template = promptLoader.load("query-rewrite.yml");

        assertNotNull(template);
        assertTrue(template.contains("system:"));
        assertTrue(template.contains("instruction:"));
        // 同文件重复加载命中缓存
        assertSame(template, promptLoader.load("query-rewrite.yml"));
    }

    @Test
    @DisplayName("loadRaw → 返回叶子值原始文本（不带 key 前缀）")
    void loadRaw_returnsLeafTextWithoutPrefix() {
        String raw = promptLoader.loadRaw("system-base.yml");

        assertNotNull(raw);
        assertFalse(raw.isBlank());
        // 叶子值不携带 YAML key 前缀
        assertFalse(raw.startsWith("base:"));
    }

    @Test
    @DisplayName("loadRawAndReplace → 替换 ${placeholder} 占位符")
    void loadRawAndReplace_replacesPlaceholders() {
        String result = promptLoader.loadRawAndReplace("dynamic-context.yml", Map.of("current_time", "2026-08-15 12:00:00 +0800", "rewritten_queries", "1. 测试查询"));

        assertTrue(result.contains("2026-08-15 12:00:00 +0800"));
        assertTrue(result.contains("1. 测试查询"));
        assertFalse(result.contains("${current_time}"));
    }

    @Test
    @DisplayName("load → 不存在的模板文件返回空字符串")
    void load_missingFile_returnsEmpty() {
        assertEquals("", promptLoader.load("no-such-file.yml"));
        assertEquals("", promptLoader.loadRaw("no-such-file.yml"));
    }
}
