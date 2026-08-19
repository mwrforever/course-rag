package com.commerce.rag.record;

/**
 * 图片 caption 处理结果
 *
 * @param caption 带序号标注的 caption 文本（"图片N:描述"）；被过滤/失败的图片不产生结果
 * @param resourceName 图片资源标识（原始文件名，调试/审计用）
 */
public record ImageCaptionResult(String caption, String resourceName) {}
