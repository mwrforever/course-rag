package com.commerce.rag.etl.parser;

import java.io.InputStream;
import java.util.List;

/**
 * 文档解析器接口
 *
 * 统一各格式文档解析入口，实现类按 MIME type 路由。
 * 解析结果为纯文本 + 结构化元数据，供下游分块模块消费。
 */
public interface DocumentParser {

    /**
     * 解析文档为纯文本段落列表
     *
     * @param inputStream 文档输入流
     * @param fileName    文件名（含扩展名，用于类型推断）
     * @return 解析结果，包含文本段落列表和文档级元数据
     */
    ParsedDocument parse(InputStream inputStream, String fileName);

    /**
     * 判断此解析器是否支持给定的文件类型
     *
     * @param fileType 文件扩展名（不含点号），如 "pdf"、"docx"
     * @return 是否支持
     */
    boolean supports(String fileType);

    /**
     * 解析后的文档结构
     *
     * @param title    文档标题
     * @param sections 按章节/段落拆分的内容列表
     */
    record ParsedDocument(
            String title,
            List<ParsedSection> sections
    ) {}

    /**
     * 解析后的段落/章节
     *
     * @param headingPath 标题层级路径，如 "第1章 > 1.2 节"
     * @param parentTitle 所属章节标题
     * @param content     段落纯文本内容
     * @param startPage   起始页码（0 表示无页码概念）
     * @param endPage     结束页码
     */
    record ParsedSection(
            String headingPath,
            String parentTitle,
            String content,
            int startPage,
            int endPage
    ) {}
}
