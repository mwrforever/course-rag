package com.commerce.rag.record;

/**
 * 偏好提取输入（spec §7.6：摘要+最近三轮标注 历史上下文 / 当前对话标注明 当前对话）
 *
 * @param contextText 历史上下文文本（会话摘要如有 + 最近三轮，标注来源）
 * @param currentText 当前对话文本（当前轮用户提问 + 助手最终回答）
 */
public record ExtractionInput(String contextText, String currentText) {}
