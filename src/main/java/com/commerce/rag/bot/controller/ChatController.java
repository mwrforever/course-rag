package com.commerce.rag.bot.controller;

import com.commerce.rag.bot.chat.ChatMessage;
import com.commerce.rag.bot.chat.ChatService;
import com.commerce.rag.bot.chat.ChatSession;
import com.commerce.rag.bot.controller.dto.CreateSessionRequest;
import com.commerce.rag.bot.controller.dto.SendMessageRequest;
import com.commerce.rag.common.result.ApiResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 对话 API 控制器
 *
 * 提供会话管理和消息发送的 REST API。
 * 所有接口统一返回 ApiResult 包装。
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 创建新会话
     *
     * @param request 包含 userId 的请求
     * @return 新创建的会话信息
     */
    @PostMapping("/sessions")
    public ApiResult<ChatSession> createSession(@Valid @RequestBody CreateSessionRequest request) {
        ChatSession session = chatService.createSession(request.getUserId());
        return ApiResult.ok(session);
    }

    /**
     * 发送消息并获取 AI 回答
     *
     * @param request 包含 sessionId 和消息内容
     * @return AI 回答、意图类型和来源引用
     */
    @PostMapping("/send")
    public ApiResult<ChatService.ChatResponse> sendMessage(@Valid @RequestBody SendMessageRequest request) {
        ChatService.ChatResponse response = chatService.processMessage(
                request.getSessionId(), request.getMessage());
        return ApiResult.ok(response);
    }

    /**
     * 获取会话历史消息
     *
     * @param sessionId 会话 ID
     * @return 历史消息列表（按时间正序）
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResult<List<ChatMessage>> getHistory(@PathVariable UUID sessionId) {
        List<ChatMessage> messages = chatService.getHistory(sessionId);
        return ApiResult.ok(messages);
    }

    /**
     * 关闭会话
     *
     * @param sessionId 会话 ID
     * @return 操作结果
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResult<Void> closeSession(@PathVariable UUID sessionId) {
        chatService.closeSession(sessionId);
        return ApiResult.ok();
    }
}
