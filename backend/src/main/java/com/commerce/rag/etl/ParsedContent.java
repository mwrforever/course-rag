package com.commerce.rag.etl;

import java.util.List;

/**
 * Tika 结构化解析结果 —— parseDocument 阶段的产出，chunkDocument 阶段的输入
 *
 * <p>sections 按文档出现顺序排列（文本/表格/图片混合），保证 chunk_index 与原文顺序一致。
 *
 * @author commerce-rag
 */
public record ParsedContent(List<ParsedContent.ParsedSection> sections) {

    /** 文档结构单元（文本 / 表格 / 图片），按出现顺序编排 */
    public sealed interface ParsedSection permits TextSection, ImageSection, TableSection {}

    /** 文本单元：同一标题路径下的连续正文 */
    public record TextSection(String headingPath, String text) implements ParsedSection {}

    /** 表格单元：Tika XHTML 输出中的原始 table 片段（Markdown 化在分片阶段，Task 6 引入） */
    public record TableSection(String headingPath, String html) implements ParsedSection {}

    /** 图片单元：内嵌图片字节与 MIME（caption 与入库在分片阶段，Task 7 消费） */
    public record ImageSection(String headingPath, String mimeType, byte[] bytes, String resourceName)
            implements ParsedSection {}

    /** 内嵌图片捕获结果（resourceName → 字节与 MIME） */
    public record CapturedImage(byte[] bytes, String mimeType) {}
}
