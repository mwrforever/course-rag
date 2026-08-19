package com.commerce.rag.enums;

/**
 * 用户附件类型（spec §5.2 首版范围）
 *
 * <p>image=图片（VLM caption 链路）；document=文本文档（PDF/Word/TXT/MD，局部检索链路）。
 */
public enum AttachmentType {
    IMAGE,
    DOCUMENT
}
