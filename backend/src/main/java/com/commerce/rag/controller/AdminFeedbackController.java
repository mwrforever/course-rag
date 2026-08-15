package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.service.UserFeedbackService;
import com.commerce.rag.vo.UserFeedbackVO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈管理 Controller（I1-I3）
 *
 * <p>B 端管理接口，教师/超级管理员可查看反馈列表、统计、删除反馈。
 * 2026-08-15 用户裁决：全局可见，不区分教师/超管视角（无 created_by 过滤）。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin/feedbacks")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
public class AdminFeedbackController {

    private static final Logger log = LoggerFactory.getLogger(AdminFeedbackController.class);

    private final UserFeedbackService feedbackService;

    public AdminFeedbackController(UserFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** I1: 分页查询反馈（2026-08-15 用户裁决：全局可见，不区分教师/超管视角） */
    @GetMapping
    public ApiResponse<PageResponse<UserFeedbackVO>> findPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String intentType) {
        return ApiResponse.ok(PageResponse.of(feedbackService.findPage(page, size, intentType)));
    }

    /** I2: 反馈统计（按意图分组统计赞/踩数；全局口径） */
    @GetMapping("/stats")
    public ApiResponse<List<Map<String, Object>>> findStats() {
        return ApiResponse.ok(feedbackService.findStats());
    }

    /** I3: 删除反馈 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long operatorId = AuthInterceptor.getCurrentUserId(request);
        feedbackService.delete(id, operatorId);
        return ApiResponse.ok();
    }
}
