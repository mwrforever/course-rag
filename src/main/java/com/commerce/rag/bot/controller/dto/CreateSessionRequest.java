package com.commerce.rag.bot.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建会话请求
 */
@Data
public class CreateSessionRequest {

    /** 用户标识 */
    @NotBlank(message = "userId 不能为空")
    private String userId;
}
