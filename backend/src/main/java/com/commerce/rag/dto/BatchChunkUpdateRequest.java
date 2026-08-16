package com.commerce.rag.dto;

import java.util.List;

/**
 * 批量更新分片标量字段请求
 *
 * @param ids            分片 ID 列表
 * @param collectionType 新 collection_type（可选）
 * @param courseId       新 course_id（可选）
 */
public record BatchChunkUpdateRequest(List<Long> ids, String collectionType, String courseId) {}
