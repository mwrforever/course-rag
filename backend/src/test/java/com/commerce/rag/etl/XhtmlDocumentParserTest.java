package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XhtmlDocumentParser 单元测试 —— 标题路径/正文切分/图片定位（纯函数）
 *
 * @author commerce-rag
 */
class XhtmlDocumentParserTest {

    private final XhtmlDocumentParser parser = new XhtmlDocumentParser();

    @Test
    @DisplayName("标题嵌套 — 正文单元继承完整标题路径")
    void headings_producePath() {
        String xhtml =
                "<html><body>" + "<h1>第一章</h1><p>第一节正文内容。</p>" + "<h2>1.1 小节</h2><p>小节正文内容。</p>" + "</body></html>";

        ParsedContent parsed = parser.parse(xhtml, Map.of());
        List<ParsedContent.ParsedSection> sections = parsed.sections();

        assertEquals(2, sections.size());
        ParsedContent.TextSection first = (ParsedContent.TextSection) sections.get(0);
        ParsedContent.TextSection second = (ParsedContent.TextSection) sections.get(1);
        assertEquals("第一章", first.headingPath());
        assertTrue(first.text().contains("第一节正文内容"));
        assertEquals("第一章 > 1.1 小节", second.headingPath());
        assertTrue(second.text().contains("小节正文内容"));
    }

    @Test
    @DisplayName("标题文本不进正文 — heading 只出现在 headingPath")
    void headingText_excludedFromBody() {
        String xhtml = "<html><body><h2>环境要求</h2><p>JDK 17 以上。</p></body></html>";

        ParsedContent parsed = parser.parse(xhtml, Map.of());
        ParsedContent.TextSection section =
                (ParsedContent.TextSection) parsed.sections().get(0);

        assertTrue(!section.text().contains("环境要求"), "标题文本不应重复出现在正文: " + section.text());
    }

    @Test
    @DisplayName("表格剥离正文 — 产出独立 TableSection，正文不含表格文本")
    void tableElement_producesTableSection() {
        String xhtml = "<html><body><h1>数据</h1><p>前置说明。</p>"
                + "<table><tr><th>名称</th></tr><tr><td>数值</td></tr></table>"
                + "<p>后置说明。</p></body></html>";

        ParsedContent parsed = parser.parse(xhtml, Map.of());

        assertEquals(3, parsed.sections().size(), "应产出 前置文本/表格/后置文本 三个分区");
        ParsedContent.TableSection table =
                (ParsedContent.TableSection) parsed.sections().get(1);
        assertEquals("数据", table.headingPath());
        assertTrue(table.html().contains("<table>"));
        String allText = parsed.sections().stream()
                .filter(ParsedContent.TextSection.class::isInstance)
                .map(s -> ((ParsedContent.TextSection) s).text())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(!allText.contains("数值"), "表格文本应从正文剥离: " + allText);
    }

    @Test
    @DisplayName("图片定位 — embedded: 前缀剥除后按资源名匹配，产出 ImageSection")
    void imgElement_matchedToCapturedImage() {
        String xhtml = "<html><body><h1>图例</h1><img src=\"embedded:image0.png\"/></body></html>";
        byte[] bytes = new byte[] {1, 2, 3};
        Map<String, ParsedContent.CapturedImage> images = new LinkedHashMap<>();
        images.put("image0.png", new ParsedContent.CapturedImage(bytes, "image/png"));

        ParsedContent parsed = parser.parse(xhtml, images);

        assertEquals(1, parsed.sections().size());
        ParsedContent.ImageSection image =
                (ParsedContent.ImageSection) parsed.sections().get(0);
        assertEquals("图例", image.headingPath());
        assertEquals("image/png", image.mimeType());
        assertEquals(bytes, image.bytes());
    }

    @Test
    @DisplayName("未匹配图片 — 按捕获顺序追加到末尾（尽力而为）")
    void unmatchedImage_appendedAtEnd() {
        ParsedContent parsed = parser.parse(
                "<html><body><p>只有文本。</p></body></html>",
                Map.of("orphan.png", new ParsedContent.CapturedImage(new byte[] {9}, "image/png")));

        assertEquals(2, parsed.sections().size());
        ParsedContent.ImageSection image =
                (ParsedContent.ImageSection) parsed.sections().get(1);
        assertEquals("orphan.png", image.resourceName());
        assertEquals("", image.headingPath());
    }

    @Test
    @DisplayName("空输入 — 空分区列表")
    void blankInput_emptySections() {
        ParsedContent parsed = parser.parse("<html><body></body></html>", Map.of());
        assertTrue(parsed.sections().isEmpty());
    }
}
