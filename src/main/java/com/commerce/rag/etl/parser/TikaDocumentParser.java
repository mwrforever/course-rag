package com.commerce.rag.etl.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 Apache Tika 的通用文档解析器
 *
 * 支持 PDF、Word(docx)、PPT(pptx)、TXT 等格式。
 * 通过 Tika 的 MIME type 自动检测能力统一处理各类二进制文档。
 *
 * 解析策略：
 * - 整文档提取纯文本（BodyContentHandler 不限长度）
 * - 按双换行符拆分为段落
 * - 页码信息从 Metadata 中提取（Tika 支持 PDF 的 xmpTPg:NPages）
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    /** 支持的二进制文件类型 */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "pdf", "docx", "doc", "pptx", "ppt", "txt", "html", "htm"
    );

    private final Tika tika = new Tika();

    @Override
    public boolean supports(String fileType) {
        return SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    /**
     * 解析文档为结构化段落
     *
     * 使用 Tika AutoDetectParser 提取全文，按双换行拆段。
     * PDF 格式额外提取总页数信息。
     *
     * @param inputStream 文档输入流（调用方负责关闭）
     * @param fileName    文件名
     * @return 解析后的文档结构
     */
    @Override
    public ParsedDocument parse(InputStream inputStream, String fileName) {
        log.info("开始解析文档: {}", fileName);
        long startTime = System.currentTimeMillis();

        try {
            // 使用 AutoDetectParser 自动识别 MIME type
            BodyContentHandler handler = new BodyContentHandler(-1);  // 不限制文本长度
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(inputStream, handler, metadata, new ParseContext());

            String fullText = handler.toString();
            String title = metadata.get(TikaCoreProperties.TITLE);
            if (title == null || title.isBlank()) {
                // 无标题时取文件名（去掉扩展名）
                title = fileName.contains(".")
                        ? fileName.substring(0, fileName.lastIndexOf('.'))
                        : fileName;
            }

            // 提取总页数（PDF 场景）
            int totalPages = 0;
            String pageCount = metadata.get("xmpTPg:NPages");
            if (pageCount != null) {
                try {
                    totalPages = Integer.parseInt(pageCount);
                } catch (NumberFormatException ignored) {
                    // 无法解析页数时保持 0
                }
            }

            // 按双换行拆分为段落
            List<ParsedSection> sections = splitIntoSections(fullText, title, totalPages);

            log.info("文档解析完成: {}, 段落数: {}, 耗时: {}ms",
                    fileName, sections.size(), System.currentTimeMillis() - startTime);
            return new ParsedDocument(title, sections);

        } catch (IOException | TikaException e) {
            log.error("文档解析失败: {}", fileName, e);
            throw new RuntimeException("文档解析失败: " + fileName, e);
        }
    }

    /**
     * 将全文按双换行拆分为段落列表
     *
     * @param fullText   完整文本
     * @param title      文档标题
     * @param totalPages 总页数（0 表示无页码）
     * @return 段落列表
     */
    private List<ParsedSection> splitIntoSections(String fullText, String title, int totalPages) {
        List<ParsedSection> sections = new ArrayList<>();
        if (fullText == null || fullText.isBlank()) {
            return sections;
        }

        // 按双换行拆分段落
        String[] paragraphs = fullText.split("\n\\s*\n");
        int currentSection = 0;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;

            // 简单估算页码（按段落均分）
            int startPage = totalPages > 0
                    ? (currentSection * totalPages / Math.max(paragraphs.length, 1)) + 1
                    : 0;
            int endPage = startPage;

            sections.add(new ParsedSection(
                    title,      // headingPath：无结构化标题时取文档标题
                    title,      // parentTitle
                    trimmed,
                    startPage,
                    endPage
            ));
            currentSection++;
        }
        return sections;
    }
}
