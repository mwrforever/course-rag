package com.commerce.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.service.ChatMessageService;
import com.commerce.rag.service.ChatSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B 端会话管理 Controller —— CRUD 端点 H1-H4
 *
 * <p>权限：SUPER_ADMIN（管理全部会话）
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin/sessions")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminSessionController {

    private final ChatSessionService sessionService;
    private final ChatMessageService messageService;

    public AdminSessionController(ChatSessionService sessionService, ChatMessageService messageService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
    }

    /**
     * H1: 会话列表（分页）
     */
    @GetMapping
    public ApiResponse<PageResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        IPage<ChatSession> result = sessionService.findAllSessions(page, size);
        List<Map<String, Object>> records =
                result.getRecords().stream().map(this::toSummaryMap).collect(Collectors.toList());
        return ApiResponse.ok(
                new PageResponse<>(records, result.getTotal(), (int) result.getCurrent(), (int) result.getSize()));
    }

    /**
     * H2: 查看会话（含消息列表）
     */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        ChatSession session = sessionService.findById(id);
        if (session == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        List<ChatMessage> messages = messageService.findBySessionId(id);
        List<Map<String, Object>> messageList =
                messages.stream().map(this::toMessageMap).collect(Collectors.toList());

        Map<String, Object> data = toSummaryMap(session);
        data.put("messages", messageList);
        return ApiResponse.ok(data);
    }

    /**
     * H3: 关闭会话（status → CLOSED）
     */
    @PatchMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id) {
        sessionService.closeSession(id);
        return ApiResponse.ok();
    }

    /**
     * H4: 删除会话（级联软删消息 + Run）
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long operatorId = AuthInterceptor.getCurrentUserId(request);
        sessionService.deleteSession(id, operatorId);
        return ApiResponse.ok();
    }

    private Map<String, Object> toSummaryMap(ChatSession s) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", s.getId());
        map.put("userId", s.getUserId());
        map.put("title", s.getTitle());
        map.put("status", s.getStatus());
        map.put("lastMessageAt", s.getLastMessageAt());
        map.put("model", s.getModel());
        map.put("createdAt", s.getCreatedAt());
        return map;
    }

    private Map<String, Object> toMessageMap(ChatMessage m) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", m.getId());
        map.put("role", m.getRole());
        map.put("content", m.getContent());
        map.put("messageType", m.getMessageType());
        map.put("intentType", m.getIntentType());
        map.put("runId", m.getRunId());
        map.put("seq", m.getSeq());
        map.put("createdAt", m.getCreatedAt());
        return map;
    }
}
