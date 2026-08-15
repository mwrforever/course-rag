package com.commerce.rag.controller.dto;

/**
 * 分片 collection_type 更新请求
 *
 * @param collectionType 新 collection_type
 * @param courseId       新 course_id（可选）
 */
public record ChunkCollectionTypeRequest(String collectionType, String courseId) {}
