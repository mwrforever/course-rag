package com.commerce.rag.etl;

import com.commerce.rag.properties.EtlProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * 表格分片器 —— HTML 表格转 Markdown；大表按行分组（表头重复 + 组间 overlap 行 + 上下文前缀）
 *
 * <p>核心原则（spec §4.3）：表格是语义完整单元，永不硬切破坏结构：
 * <ul>
 *   <li>小表格（≤ chunk.size token）：整表一个 chunk</li>
 *   <li>大表格：每 rowsPerChunk 行一组（token 估算动态调整，硬上限 maxRowsPerChunk），
 *       每个子 chunk 重复完整表头，相邻子 chunk 间 overlap overlapRows 行</li>
 *   <li>上下文前缀：表头 + 前 2 数据行拼进 content 开头（向量感知表格主题）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class TableChunker {

    /** 上下文前缀包含的数据行数（spec §4.3 定稿：表头 + 前 2 行） */
    private static final int PREFIX_DATA_ROWS = 2;

    private final EtlProperties etlProperties;

    /**
     * 将单个 HTML 表格切分为 Markdown 分片规格
     *
     * @param html        Tika XHTML 中的原始 table 片段
     * @param headingPath 表格所在章节的标题导航路径
     * @return 表格分片规格列表（非表格输入返回空列表）
     */
    public List<ChunkSpec> chunk(String html, String headingPath) {
        Element table = selectFirstTable(html);
        if (table == null) {
            return List.of();
        }

        List<List<String>> rows = extractRows(table);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> header = rows.get(0);
        List<List<String>> dataRows = rows.subList(1, rows.size());
        if (dataRows.isEmpty()) {
            // 仅表头：整表一个 chunk
            return List.of(new ChunkSpec(toMarkdown(List.of(header)), headingPath, "table", null, null, null, null));
        }

        int maxTokens = etlProperties.chunk().size();
        String fullMarkdown = toMarkdown(rows);
        if (TokenEstimator.estimate(fullMarkdown) <= maxTokens) {
            // 小表格：整表一个 chunk，不加不减
            return List.of(new ChunkSpec(fullMarkdown, headingPath, "table", null, null, null, null));
        }

        // 大表格：上下文前缀（表头 + 前 2 数据行）
        List<List<String>> prefixRows = new ArrayList<>();
        prefixRows.add(header);
        prefixRows.addAll(dataRows.subList(0, Math.min(PREFIX_DATA_ROWS, dataRows.size())));
        String prefix = toMarkdown(prefixRows);

        // 行分组：表头 + 至多 maxRowsPerChunk 数据行；超 token 上限则逐行回退收口
        List<ChunkSpec> specs = new ArrayList<>();
        int startIdx = 0;
        while (startIdx < dataRows.size()) {
            List<List<String>> current = new ArrayList<>();
            current.add(header);
            int count = 0;
            while (startIdx + count < dataRows.size()
                    && count < etlProperties.table().maxRowsPerChunk()) {
                current.add(dataRows.get(startIdx + count));
                count++;
            }
            // 超 token 上限：逐行回退收口（回退行留给下一组，单行超长时退至该行成组），
            // 直至≤ maxTokens 或仅剩 1 行（不硬切行内结构）
            while (count > 1 && TokenEstimator.estimate(toMarkdown(current)) > maxTokens) {
                current.remove(current.size() - 1);
                count--;
            }
            specs.add(
                    new ChunkSpec(prefix + "\n\n" + toMarkdown(current), headingPath, "table", null, null, null, null));
            // 相邻子 chunk 间 overlap：下一组从本组末尾 overlapRows 行开始
            startIdx += Math.max(count - etlProperties.table().overlapRows(), 1);
        }
        return specs;
    }

    /** 定位第一个 table 元素（parseBodyFragment 包装后 select） */
    private static Element selectFirstTable(String html) {
        return Jsoup.parseBodyFragment(html).body().selectFirst("table");
    }

    /** 提取表格行（表头行 + 数据行，含 th/td 单元格） */
    private static List<List<String>> extractRows(Element table) {
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.select("th, td")) {
                cells.add(cell.text().trim());
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    /** 行列表 → Markdown 表格（首行为表头，自动生成分隔行，列数对齐） */
    private static String toMarkdown(List<List<String>> rows) {
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            List<String> padded = new ArrayList<>(rows.get(i));
            while (padded.size() < cols) {
                padded.add("");
            }
            sb.append("| ").append(String.join(" | ", padded)).append(" |\n");
            if (i == 0) {
                sb.append("|").append(" --- |".repeat(cols)).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
