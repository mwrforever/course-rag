package com.commerce.rag.bot.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * 发送消息请求
 */
@Data
public class SendMessageRequest {

    /** 会话 ID */
    @NotNull(message = "sessionId 不能为空")
    private UUID sessionId;

    /** 用户消息内容（新消息时必填，续传时可为空） */
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 断线续传：上次流的 runId（正常新消息时为 null） */
    private UUID lastRunId;

    /** 断线续传：客户端已收到的最后一个 Redis Stream 事件 ID（如 "1234567890-0"） */
    private String lastEventId;
}
