package com.commerce.rag.etl.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Markdown 文档解析器
 *
 * 识别 Markdown 标题层级（# / ## / ### 等），按标题切分章节。
 * 保留标题层级路径（如 "第1章 > 1.2 节"），供分块阶段作为元数据。
 *
 * 相比 Tika，Markdown 解析器能精确提取标题层级结构。
 */
@Slf4j
@Component
public class MarkdownParser implements DocumentParser {

    @Override
    public boolean supports(String fileType) {
        return "md".equalsIgnoreCase(fileType) || "markdown".equalsIgnoreCase(fileType);
    }

    /**
     * 解析 Markdown 为结构化章节
     *
     * 逐行读取，遇到标题行（# 开头）时切分章节。
     * 标题层级栈维护 headingPath。
     *
     * @param inputStream 文档输入流
     * @param fileName    文件名
     * @return 解析后的文档结构
     */
    @Override
    public ParsedDocument parse(InputStream inputStream, String fileName) {
        log.info("开始解析 Markdown 文档: {}", fileName);
        long startTime = System.currentTimeMillis();

        String title = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        List<ParsedSection> sections = new ArrayList<>();
        // 标题层级栈：栈底为一级标题，栈顶为当前层级
        Deque<String> headingStack = new ArrayDeque<>();
        StringBuilder currentContent = new StringBuilder();
        String currentHeading = title;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                // 检测标题行
                int headingLevel = getHeadingLevel(line);
                if (headingLevel > 0) {
                    // 遇到新标题，先保存前一个章节的内容
                    saveSection(sections, currentHeading, currentContent, headingStack);

                    String headingText = line.substring(headingLevel).trim();
                    // 维护标题栈：弹出同级和更低级标题
                    while (headingStack.size() >= headingLevel) {
                        headingStack.pollLast();
                    }
                    headingStack.addLast(headingText);

                    if (headingLevel == 1) {
                        title = headingText;
                    }
                    currentHeading = headingText;
                    currentContent = new StringBuilder();
                } else {
                    if (!currentContent.isEmpty()) {
                        currentContent.append("\n");
                    }
                    currentContent.append(line);
                }
            }

            // 保存最后一个章节
            saveSection(sections, currentHeading, currentContent, headingStack);

        } catch (IOException e) {
            log.error("Markdown 解析失败: {}", fileName, e);
            throw new RuntimeException("Markdown 解析失败: " + fileName, e);
        }

        log.info("Markdown 解析完成: {}, 章节数: {}, 耗时: {}ms",
                fileName, sections.size(), System.currentTimeMillis() - startTime);
        return new ParsedDocument(title, sections);
    }

    /**
     * 检测行的标题级别
     *
     * @param line 文本行
     * @return 标题级别（1-6），0 表示非标题行
     */
    private int getHeadingLevel(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) != '#') return 0;
        int level = 0;
        for (char c : line.toCharArray()) {
            if (c == '#') level++;
            else break;
        }
        // # 后必须跟空格才是合法标题
        return (level <= 6 && line.length() > level && line.charAt(level) == ' ') ? level : 0;
    }

    /**
     * 保存当前章节到列表
     */
    private void saveSection(List<ParsedSection> sections, String heading,
                             StringBuilder content, Deque<String> headingStack) {
        String trimmed = content.toString().trim();
        if (trimmed.isEmpty()) return;

        String headingPath = String.join(" > ", headingStack);
        String parentTitle = headingStack.size() > 1
                ? headingStack.stream().skip(headingStack.size() - 2L).findFirst().orElse(heading)
                : heading;

        sections.add(new ParsedSection(headingPath, parentTitle, trimmed, 0, 0));
    }
}
