package com.commerce.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 消息级重放请求（M5 replay 端点入参，spec D2/D5）
 *
 * <p>承载编辑最后一条用户消息重答（EDIT）与重新生成最后一条回答（REGENERATE）
 * 两种模式的重放语义；编排与校验见 ChatStreamEntry.replay。
 *
 * @param mode        重放模式（必填，白名单 EDIT / REGENERATE——service 层校验，
 *                    非白名单值 400）
 * @param query       编辑后的新问题文本（EDIT 必填非空——service 层校验 400；
 *                    REGENERATE 忽略该字段，可空：服务端以目标 run 落库的原问题
 *                    文本回填 XADD 供消息行持久化）
 * @param targetRunId 目标 run ID（必填；EDIT=被编辑用户消息所在 run，
 *                    REGENERATE=被重生成回答的 run；须属于当前会话且未软删）
 */
public record ChatReplayRequest(
        @NotBlank(message = "mode 不能为空") String mode,
        String query,
        @NotNull(message = "targetRunId 不能为空") Long targetRunId) {}
