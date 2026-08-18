package com.commerce.rag.etl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * Tika XHTML 结构解析器 —— 提取标题导航路径，将正文切分为带标题的文本单元，
 * 并定位内嵌图片（&lt;img src="embedded:xxx"&gt; 与捕获字节按资源名匹配，未匹配者按捕获顺序追加）。
 *
 * <p>纯函数组件（无状态、无 IO）：输入 XHTML 字符串与图片字节映射，输出 ParsedContent，
 * 便于单元测试。
 *
 * @author commerce-rag
 */
@Component
public class XhtmlDocumentParser {

    /** 块级元素：递归结束后补换行，保证正文段落分隔 */
    private static final Set<String> BLOCK_TAGS = Set.of("p", "div", "li", "br", "tr", "ul", "ol", "pre", "blockquote");

    /**
     * 解析 XHTML 为结构分区
     *
     * @param xhtml  Tika ToHTMLContentHandler 输出的 XHTML
     * @param images 内嵌图片映射（resourceName → 字节与 MIME），允许为空
     * @return 按文档顺序排列的文本/表格/图片分区
     */
    public ParsedContent parse(String xhtml, Map<String, ParsedContent.CapturedImage> images) {
        Element body = Jsoup.parse(xhtml).body();
        List<ParsedContent.ParsedSection> sections = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Deque<Heading> headings = new ArrayDeque<>();
        List<String> matchedNames = new ArrayList<>();

        walk(body, headings, buf, sections, images, matchedNames);
        flushText(buf, currentPath(headings), sections);

        // 未在正文中定位到的图片：按捕获顺序追加到末尾（无标题/位置信息，尽力而为）
        for (Map.Entry<String, ParsedContent.CapturedImage> entry : images.entrySet()) {
            if (!matchedNames.contains(entry.getKey())) {
                sections.add(new ParsedContent.ImageSection(
                        "", entry.getValue().mimeType(), entry.getValue().bytes(), entry.getKey()));
            }
        }
        return new ParsedContent(sections);
    }

    /** 深度优先遍历元素树，维护标题栈并收集正文/图片 */
    private void walk(
            Element element,
            Deque<Heading> headings,
            StringBuilder buf,
            List<ParsedContent.ParsedSection> sections,
            Map<String, ParsedContent.CapturedImage> images,
            List<String> matchedNames) {
        for (Node child : element.childNodes()) {
            if (child instanceof Element e) {
                String tag = e.tagName();
                if (tag.matches("h[1-6]")) {
                    // 标题：切换标题栈（标题文本进 heading_path，不进正文）
                    flushText(buf, currentPath(headings), sections);
                    int level = tag.charAt(1) - '0';
                    while (!headings.isEmpty() && headings.peek().level() >= level) {
                        headings.pop();
                    }
                    String title = e.text().trim();
                    if (!title.isEmpty()) {
                        headings.push(new Heading(level, title));
                    }
                    continue;
                }
                if (tag.equals("table")) {
                    // 表格是语义完整单元：独立成 TableSection，从正文剥离（spec §4.3）
                    flushText(buf, currentPath(headings), sections);
                    sections.add(new ParsedContent.TableSection(currentPath(headings), e.outerHtml()));
                    continue;
                }
                if (tag.equals("img")) {
                    flushText(buf, currentPath(headings), sections);
                    String src = e.attr("src");
                    ParsedContent.CapturedImage image = lookup(images, src);
                    if (image != null) {
                        // resourceName 使用捕获映射的规范键（剥除 embedded: 前缀），
                        // 保证与 parse() 末尾未匹配图片追加判断的键一致，避免重复捕获
                        String resourceName = resolveKey(images, src);
                        sections.add(new ParsedContent.ImageSection(
                                currentPath(headings), image.mimeType(), image.bytes(), resourceName));
                        matchedNames.add(resourceName);
                    }
                    continue;
                }
                walk(e, headings, buf, sections, images, matchedNames);
                if (BLOCK_TAGS.contains(tag)) {
                    buf.append('\n');
                }
            } else if (child instanceof TextNode textNode) {
                String text = textNode.text().trim();
                if (!text.isEmpty()) {
                    buf.append(text).append('\n');
                }
            }
        }
    }

    /** 解析 src 对应的捕获映射规范键（已含前缀的 src 直接命中，否则剥除 embedded: 前缀） */
    private static String resolveKey(Map<String, ParsedContent.CapturedImage> images, String src) {
        if (images.containsKey(src)) {
            return src;
        }
        if (src.startsWith("embedded:")) {
            return src.substring("embedded:".length());
        }
        return src;
    }

    /** 按资源名匹配图片字节；XHTML src 形如 embedded:image0.png */
    private static ParsedContent.CapturedImage lookup(Map<String, ParsedContent.CapturedImage> images, String src) {
        return images.get(resolveKey(images, src));
    }

    private static void flushText(StringBuilder buf, String path, List<ParsedContent.ParsedSection> sections) {
        String text = buf.toString().trim();
        buf.setLength(0);
        if (!text.isEmpty()) {
            sections.add(new ParsedContent.TextSection(path, text));
        }
    }

    private static String currentPath(Deque<Heading> headings) {
        // 标题栈栈顶为最内层标题（push/peek 操作头部），输出时需外层在前内层在后（降序迭代）
        List<String> titles = new ArrayList<>();
        headings.descendingIterator().forEachRemaining(h -> titles.add(h.title()));
        return String.join(" > ", titles);
    }

    /** 标题栈元素（层级 + 标题文本） */
    private record Heading(int level, String title) {}
}
