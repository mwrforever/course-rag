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

    /** 用户消息内容 */
    @NotBlank(message = "消息内容不能为空")
    private String message;
}
