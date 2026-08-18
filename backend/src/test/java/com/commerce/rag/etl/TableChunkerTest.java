package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.properties.EtlProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TableChunker 单元测试 —— HTML 表格 → Markdown；大表行分组/表头重复/overlap 行/上下文前缀
 *
 * @author commerce-rag
 */
class TableChunkerTest {

    private static final EtlProperties PROPS = new EtlProperties(
            100,
            new EtlProperties.Executor(2, 4, 20, "etl-"),
            new EtlProperties.Chunk(768, 64),
            16,
            "qwen3.7-flash",
            10,
            new EtlProperties.Table(25, 30, 2));

    private final TableChunker chunker = new TableChunker(PROPS);

    @Test
    @DisplayName("小表格 — 整表一个 Markdown chunk（表头行 + 分隔行 + 数据行）")
    void smallTable_singleMarkdownChunk() {
        String html = "<table><tr><th>名称</th><th>价格</th></tr>"
                + "<tr><td>课程A</td><td>1999</td></tr><tr><td>课程B</td><td>2999</td></tr></table>";

        List<ChunkSpec> specs = chunker.chunk(html, "课程列表");

        assertEquals(1, specs.size());
        ChunkSpec spec = specs.get(0);
        assertEquals("table", spec.contentType());
        assertEquals("课程列表", spec.headingPath());
        assertTrue(spec.content().contains("| 名称 | 价格 |"), "应含表头行: " + spec.content());
        assertTrue(spec.content().contains("| --- | --- |"), "应含 Markdown 分隔行: " + spec.content());
        assertTrue(spec.content().contains("| 课程A | 1999 |"), "应含数据行: " + spec.content());
    }

    @Test
    @DisplayName("大表格 — 行分组：每组重复表头、组间 overlap 行、上下文前缀")
    void largeTable_groupedWithHeaderAndOverlap() {
        // 40 行大表（每行约 40+ token，5 行即逼近 768 token 上限）
        StringBuilder html = new StringBuilder("<table><tr><th>序号</th><th>说明</th></tr>");
        for (int i = 1; i <= 40; i++) {
            html.append("<tr><td>")
                    .append(i)
                    .append("</td><td>")
                    .append("这是第")
                    .append(i)
                    .append("行的详细说明内容，包含足够的文字。".repeat(2))
                    .append("</td></tr>");
        }
        html.append("</table>");

        List<ChunkSpec> specs = chunker.chunk(html.toString(), "");

        assertTrue(specs.size() > 1, "大表应拆分为多组: " + specs.size());
        for (ChunkSpec spec : specs) {
            assertEquals("table", spec.contentType());
            // 每组重复完整表头（语义独立）；诊断消息截断需防短 content 越界
            assertTrue(
                    spec.content().contains("| 序号 | 说明 |"),
                    "每组应含表头: "
                            + spec.content()
                                    .substring(0, Math.min(200, spec.content().length())));
            // 上下文前缀 = 表头 + 前 2 数据行（拼在 content 开头）
            assertTrue(spec.content().startsWith("| 序号 | 说明 |"), "前缀应位于 content 开头");
            // token 上限（分组 ≤ 768 token + 前缀约 120 token 的余量）
            assertTrue(TokenEstimator.estimate(spec.content()) <= 1000, "分组 token 超上限");
        }
        // 相邻组 overlap：前一组末尾行出现在后一组
        String prev = specs.get(0).content();
        String next = specs.get(1).content();
        boolean overlapped = java.util.stream.IntStream.rangeClosed(1, 40)
                .anyMatch(i -> prev.contains("| " + i + " |") && next.contains("| " + i + " |"));
        assertTrue(overlapped, "相邻组应有重叠行");
    }

    @Test
    @DisplayName("仅表头表格 — 一个 chunk（不抛异常）")
    void headerOnly_singleChunk() {
        List<ChunkSpec> specs = chunker.chunk("<table><tr><th>名称</th><th>价格</th></tr></table>", "");
        assertEquals(1, specs.size());
    }

    @Test
    @DisplayName("非表格 HTML — 空列表")
    void noTable_emptyList() {
        assertEquals(0, chunker.chunk("<p>纯文本。</p>", "").size());
    }

    @Test
    @DisplayName("单行超长 — 强制单行成组（不硬切行内结构）")
    void oversizedSingleRow_forcedGroup() {
        String longCell = "超长单元格内容".repeat(200);
        String html = "<table><tr><th>列</th></tr><tr><td>" + longCell + "</td></tr></table>";

        List<ChunkSpec> specs = chunker.chunk(html, "");

        assertEquals(1, specs.size());
        assertTrue(specs.get(0).content().contains("超长单元格内容"), "超长行不得被硬切丢内容");
    }
}
