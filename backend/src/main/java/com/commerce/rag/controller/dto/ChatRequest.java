package com.commerce.rag.controller.dto;

/**
 * 对话请求 DTO。
 *
 * @param sessionId 可选：null=新建会话，非 null=已有会话
 * @param query     必填：用户问题
 */
public record ChatRequest(Long sessionId, String query) {}
