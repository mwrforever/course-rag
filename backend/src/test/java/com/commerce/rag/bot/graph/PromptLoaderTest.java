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
        String result = promptLoader.loadRawAndReplace(
                "dynamic-context.yml",
                Map.of("current_time", "2026-08-15 12:00:00 +0800", "rewritten_queries", "1. 测试查询"));

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

    @Test
    @DisplayName("loadRawAndReplace → 未匹配的占位符原样保留，只替换命中项")
    void loadRawAndReplace_unmatchedPlaceholder_keptAsIs() {
        // 只替换 current_time，rewritten_queries 未提供 → ${rewritten_queries} 原样保留
        String result = promptLoader.loadRawAndReplace(
                "dynamic-context.yml", Map.of("current_time", "2026-08-16 10:00:00 +0800"));

        assertTrue(result.contains("2026-08-16 10:00:00 +0800"));
        assertFalse(result.contains("${current_time}"));
        assertTrue(result.contains("${rewritten_queries}"), "未提供的占位符应原样保留");
    }

    @Test
    @DisplayName("loadAndReplace → 展平文本中替换占位符且保留分段标记")
    void loadAndReplace_replacesPlaceholdersInFlattenedText() {
        String result = promptLoader.loadAndReplace(
                "dynamic-context.yml",
                Map.of("current_time", "2026-08-16 10:00:00 +0800", "rewritten_queries", "重写查询"));

        // 展平模式下保留叶子所在层级的 key 分段标记（reminder 为顶层 key，不输出前缀）
        assertTrue(result.contains("template:"));
        assertTrue(result.contains("重写查询"));
        assertFalse(result.contains("${current_time}"));
        assertFalse(result.contains("${rewritten_queries}"));
    }

    @Test
    @DisplayName("load → 三级嵌套 YAML 递归展平为叶子 key: value（含字符串列表）")
    void load_nestedThreeLevels_flattensToLeafEntries() {
        // 夹具 test-nested.yml：level1.level2.level3 + level1.items 列表
        String template = promptLoader.load("test-nested.yml");

        assertTrue(template.contains("level3: 深层叶子值"), "深层叶子应以 key: value 形式保留");
        assertTrue(template.contains("items:"), "字符串列表应保留 key 标记");
        assertTrue(template.contains("- 甲"), "列表元素应以 - 前缀保留");
        assertTrue(template.contains("- 乙"));
    }

    @Test
    @DisplayName("loadSections — 多叶子 YAML 展平为路径→文本映射")
    void loadSections_flattensLeafPaths() {
        PromptLoader loader = new PromptLoader();
        // 夹具 test-sections.yml：caption.system / caption.instruction 两层叶子
        Map<String, String> sections = loader.loadSections("test-sections.yml");

        assertTrue(sections.containsKey("caption.system"));
        assertTrue(sections.containsKey("caption.instruction"));
        assertTrue(sections.get("caption.system").contains("描述要求"));
    }

    @Test
    @DisplayName("loadSections — 真实 caption.yml 可加载（图片 caption 模板缺段会静默降级）")
    void loadSections_captionYml_loadsBothSections() {
        Map<String, String> sections = promptLoader.loadSections("caption.yml");

        assertTrue(sections.containsKey("caption.system"));
        assertTrue(sections.containsKey("caption.instruction"));
        assertTrue(sections.get("caption.system").contains("描述要求"));
    }

    @Test
    @DisplayName("loadSections — 不存在的模板文件返回空 Map，不抛出")
    void loadSections_missingFile_returnsEmptyMap() {
        Map<String, String> sections = promptLoader.loadSections("no-such-file.yml");

        assertTrue(sections.isEmpty(), "加载失败应返回空 Map");
    }

    @Test
    @DisplayName("loadRaw → 多叶子歧义按现有实现返回第一个叶子（嵌套优先）")
    void loadRaw_multiLeaf_returnsFirstEncounteredLeaf() {
        // 夹具 test-multi-leaf.yml：先遍历到嵌套 sub.leaf，再遇到 first → 返回第一个（嵌套叶子）
        String raw = promptLoader.loadRaw("test-multi-leaf.yml");

        assertEquals("深层叶子", raw, "多叶子时按现有实现返回第一个遇到的叶子值");
    }

    @Test
    @DisplayName("load → 空 YAML 文件（无键值）返回空字符串")
    void load_emptyYaml_returnsEmpty() {
        assertEquals("", promptLoader.load("test-empty.yml"));
        assertEquals("", promptLoader.loadRaw("test-empty.yml"));
    }

    @Test
    @DisplayName("load → YAML 解析失败降级返回空字符串，不抛出")
    void load_brokenYaml_returnsEmpty() {
        // 夹具 test-broken.prompt：内容为非法 YAML（plain scalar 后出现 ": "），
        // 扩展名刻意不用 .yml 以免被 pre-commit check-yaml 误拦
        assertEquals("", promptLoader.load("test-broken.prompt"));
        assertEquals("", promptLoader.loadRaw("test-broken.prompt"));
    }

    @Test
    @DisplayName("loadRaw → 同文件二次加载命中缓存（返回同一实例，不重复读文件）")
    void loadRaw_secondCall_usesCache() {
        String first = promptLoader.loadRaw("system-base.yml");
        String second = promptLoader.loadRaw("system-base.yml");

        assertSame(first, second, "二次加载应命中缓存返回同一实例");
    }
}
