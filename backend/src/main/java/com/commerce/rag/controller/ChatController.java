package com.commerce.rag.controller;

import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.ChatRequest;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.service.IAttachmentService;
import com.commerce.rag.stream.ChatStreamEntry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat SSE Controller —— 对话流式端点（薄控制器）
 *
 * <p>提供 4 个端点：
 * <ol>
 *   <li>POST /api/v1/student/chat — 发起对话，返回 SseEmitter</li>
 *   <li>POST /api/v1/student/chat/{runId}/cancel — 取消正在执行的 run</li>
 *   <li>GET /api/v1/student/chat/{runId}/reconnect — 断线重连</li>
 *   <li>POST /api/v1/student/chat/attachments — 用户附件上传（限额校验 + MinIO 落盘）</li>
 * </ol>
 *
 * <p>端点仅做参数绑定与转发，SSE 编排（会话/run 创建、Redis 入队、心跳、归属校验、
 * 断线回放）统一收编在 {@link ChatStreamEntry}，供本控制器与 StudentController 共用。
 *
 * <p>鉴权：通过 AuthInterceptor 注入的 request attribute 获取已认证用户 ID。
 * 角色门禁（P2-4 用户裁决）：允许 C 端学生与 B 端角色（TEACHER/SUPER_ADMIN）使用对话能力——
 * 显式声明 hasAnyRole 防止未来误改（原无注解 = 所有已认证角色可访问，语义不变）。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/student/chat")
@PreAuthorize("hasAnyRole('STUDENT', 'TEACHER', 'SUPER_ADMIN')")
public class ChatController {

    private final ChatStreamEntry chatStreamEntry;
    private final IAttachmentService attachmentService;

    public ChatController(ChatStreamEntry chatStreamEntry, IAttachmentService attachmentService) {
        this.chatStreamEntry = chatStreamEntry;
        this.attachmentService = attachmentService;
    }

    // ========================================================================
    // POST /api/v1/student/chat — 发起对话
    // ========================================================================

    /**
     * 发起对话，返回 SseEmitter（编排逻辑见 ChatStreamEntry.chat）。
     */
    @PostMapping
    public SseEmitter chat(HttpServletRequest httpRequest, @RequestBody ChatRequest request) {
        return chatStreamEntry.chat(httpRequest, request);
    }

    // ========================================================================
    // POST /api/v1/student/chat/{runId}/cancel — 取消 run
    // ========================================================================

    /**
     * 取消正在执行的 run（归属校验与取消下发见 ChatStreamEntry.cancel）。
     */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String runId, HttpServletRequest httpRequest) {
        return chatStreamEntry.cancel(runId, httpRequest);
    }

    // ========================================================================
    // GET /api/v1/student/chat/{runId}/reconnect — 断线重连
    // ========================================================================

    /**
     * 断线重连：原子「回放 + 订阅」恢复事件流（编排逻辑见 ChatStreamEntry.reconnect）。
     */
    @GetMapping("/{runId}/reconnect")
    public SseEmitter reconnect(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long lastEventId,
            HttpServletRequest httpRequest) {
        return chatStreamEntry.reconnect(runId, lastEventId, httpRequest);
    }

    // ========================================================================
    // POST /api/v1/student/chat/attachments — 用户附件上传
    // ========================================================================

    /**
     * 用户附件上传（spec §5.1）：只存 MinIO 返回 objectKey，caption/解析延迟到消息发送后处理。
     * 限额校验（个数/类型/单文件/合计）与落盘委托 AttachmentServiceImpl，薄控制器仅做参数绑定与转发。
     *
     * @param files 上传文件字段（multipart 多文件，必填，约束见 AttachmentServiceImpl.upload）
     * @return {@code ApiResponse<List<AttachmentRecord>>}，data 为附件记录列表（type/url/name/size）
     */
    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<AttachmentRecord>> uploadAttachments(@RequestParam("files") MultipartFile[] files) {
        return ApiResponse.ok(attachmentService.upload(files));
    }
}
