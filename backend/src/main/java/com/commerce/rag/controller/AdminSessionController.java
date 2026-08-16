package com.commerce.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.convert.ChatSessionConverter;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatSessionService;
import com.commerce.rag.vo.ChatSessionDetailVO;
import com.commerce.rag.vo.ChatSessionVO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    private final IChatSessionService sessionService;
    private final IChatMessageService messageService;
    private final ChatSessionConverter converter;

    public AdminSessionController(
            IChatSessionService sessionService, IChatMessageService messageService, ChatSessionConverter converter) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.converter = converter;
    }

    /**
     * H1: 会话列表（分页）
     */
    @GetMapping
    public ApiResponse<PageResponse<ChatSessionVO>> list(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        IPage<ChatSession> result = sessionService.findAllSessions(page, size);
        List<ChatSessionVO> records =
                result.getRecords().stream().map(converter::toSummaryVO).toList();
        return ApiResponse.ok(
                new PageResponse<>(records, result.getTotal(), (int) result.getCurrent(), (int) result.getSize()));
    }

    /**
     * H2: 查看会话（含消息列表）
     */
    @GetMapping("/{id}")
    public ApiResponse<ChatSessionDetailVO> detail(@PathVariable Long id) {
        ChatSession session = sessionService.findById(id);
        if (session == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        List<ChatMessage> messages = messageService.findBySessionId(id);
        return ApiResponse.ok(converter.toDetailVO(session, messages));
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
}
