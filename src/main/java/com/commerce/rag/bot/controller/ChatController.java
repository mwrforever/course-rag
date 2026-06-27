package com.commerce.rag.bot.controller;

import com.commerce.rag.bot.chat.ChatMessage;
import com.commerce.rag.bot.chat.ChatService;
import com.commerce.rag.bot.chat.ChatSession;
import com.commerce.rag.bot.chat.stream.ChatSseBridge;
import com.commerce.rag.bot.chat.stream.StreamSessionManager;
import com.commerce.rag.bot.controller.dto.CreateSessionRequest;
import com.commerce.rag.bot.controller.dto.SendMessageRequest;
import com.commerce.rag.common.result.ApiResult;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * 对话 API 控制器（瘦控制器）
 *
 * 提供会话管理、消息发送（同步/流式）和流取消的 REST API。
 * 流式接口采用 Worker Queue 架构：
 *   HTTP 请求 → XADD 请求队列 → 订阅响应流 → SseEmitter
 * Worker 消费队列并在后台完成 RAG 处理。
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final StreamSessionManager streamManager;
    private final ChatSseBridge sseBridge;

    public ChatController(ChatService chatService,
                          StreamSessionManager streamManager,
                          ChatSseBridge sseBridge) {
        this.chatService = chatService;
        this.streamManager = streamManager;
        this.sseBridge = sseBridge;
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
     * 发送消息并获取 AI 回答（同步，向后兼容）
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
     * 流式发送消息（SSE 打字效果）
     *
     * Worker Queue 架构：
     * 1. 创建 Redis 响应流 + meta
     * 2. XADD 请求到全局队列 chat:request（Worker 消费）
     * 3. 创建 SseEmitter + 启动异步轮询 chat:response:{runId}
     *
     * SSE 事件协议：
     * - start: 流开始，携带 runId 和 messageId
     * - delta: 文本增量
     * - citation: RAG 来源引用
     * - done: 流结束
     * - cancelled: 流被取消
     * - error: 流异常
     * - heartbeat: 保活（SSE 注释格式）
     *
     * 断线续传：传入 lastRunId + lastEventId，服务端从 Redis Stream 重放未送达事件。
     *
     * @param request 包含 sessionId、消息内容和可选的续传参数
     * @return SseEmitter 事件流
     */
    @PostMapping(value = "/send/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendStream(@RequestBody SendMessageRequest request) {
        // 断线续传
        if (request.getLastRunId() != null) {
            SseEmitter reconnected = sseBridge.reconnectEmitter(
                    request.getLastRunId(), request.getLastEventId());
            if (reconnected != null) {
                return reconnected;
            }
            // 续传失败 → 降级为新请求
        }

        // 新请求
        UUID runId = UUID.randomUUID();
        UUID sessionId = request.getSessionId();

        // 1. 创建 Redis 响应流 + meta
        streamManager.createStreamSession(runId, sessionId);

        // 2. 发布请求到 Worker 队列
        streamManager.publishRequest(runId, sessionId, request.getMessage());

        // 3. 创建 SseEmitter + 启动响应流轮询
        return sseBridge.createStreamEmitter(runId, sessionId);
    }

    /**
     * 取消正在进行的流式回答
     *
     * 设置 Redis 取消标记后，Worker 在下一个 delta 前检测到并中断流。
     *
     * @param runId 流式回答的 runId（来自 start 事件）
     * @return 操作结果
     */
    @PostMapping("/stream/{runId}/cancel")
    public ApiResult<Void> cancelStream(@PathVariable UUID runId) {
        streamManager.setCancelFlag(runId);
        return ApiResult.ok();
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
