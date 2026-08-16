package com.commerce.rag.dto;

import java.util.List;

/**
 * 批量标记已修正请求
 *
 * @param ids 分片 ID 列表
 */
public record BatchCorrectedRequest(List<Long> ids) {}
