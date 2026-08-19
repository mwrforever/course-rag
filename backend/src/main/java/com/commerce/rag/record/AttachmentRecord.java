package com.commerce.rag.record;

/**
 * 用户附件记录（上传返回/落库/重建的统一载体）
 *
 * @param type 附件类型（image/document）
 * @param url  MinIO 对象访问 URL（objectKey，重建时下载用）
 * @param name 原始文件名
 * @param size 字节大小
 */
public record AttachmentRecord(String type, String url, String name, Long size) {}
