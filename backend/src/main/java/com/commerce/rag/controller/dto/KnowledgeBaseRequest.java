package com.commerce.rag.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 知识库创建/更新请求
 *
 * <p>P2-12：name 必填非空（@NotBlank），空 name 建库返回 400——
 * 与 LoginRequest/RefreshRequest 的既有校验惯例一致，杜绝脏数据入库。
 *
 * @param name        知识库名称（必填，不允许空白）
 * @param description 描述
 */
public record KnowledgeBaseRequest(@NotBlank(message = "名称不能为空") String name, String description) {}
