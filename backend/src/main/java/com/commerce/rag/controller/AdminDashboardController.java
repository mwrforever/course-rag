package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.service.DashboardService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 Dashboard 统计 Controller（P2-2 契约对齐 + 2026-08-15 用户裁决：教师数据隔离）
 *
 * <p>提供三个统计端点（方法级路径区分，前缀 /api/v1/admin）：
 * <ul>
 *   <li>GET /api/v1/admin/dashboard/stats — 文档/待修正分片/知识库数（教师=自己的数据）</li>
 *   <li>GET /api/v1/admin/feedback/stats?period=today — 学生数/反馈数/点赞率（教师=自己的学生）</li>
 *   <li>GET /api/v1/admin/feedback/trend?days=7 — 近 N 天反馈趋势（教师=自己的学生）</li>
 * </ul>
 *
 * <p>教师数据隔离：TEACHER 仅统计自己创建的数据（created_by），SUPER_ADMIN 不限制（全局）。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;

    /** Dashboard KPI：文档数 + 待修正分片数 + 知识库数（前端文档 :783；教师按 created_by 隔离） */
    @GetMapping("/dashboard/stats")
    public ApiResponse<Map<String, Object>> dashboardStats(HttpServletRequest request) {
        Long operatorId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        return ApiResponse.ok(dashboardService.dashboardStats(operatorId, isAdmin));
    }

    /** Dashboard KPI：周期内学生数 + 反馈数 + 点赞率（前端文档 :784；教师=自己学生，超管=全部） */
    @GetMapping("/feedback/stats")
    public ApiResponse<Map<String, Object>> feedbackStats(
            @RequestParam(defaultValue = "today") String period, HttpServletRequest request) {
        Long operatorId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        return ApiResponse.ok(dashboardService.feedbackStats(period, operatorId, isAdmin));
    }

    /** Dashboard 趋势图：近 N 天每日反馈数（前端文档 :786；教师=自己学生，超管=全部） */
    @GetMapping("/feedback/trend")
    public ApiResponse<List<Map<String, Object>>> feedbackTrend(
            @RequestParam(defaultValue = "7") int days, HttpServletRequest request) {
        Long operatorId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        return ApiResponse.ok(dashboardService.feedbackTrend(days, operatorId, isAdmin));
    }
}
