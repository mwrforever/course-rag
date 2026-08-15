package com.commerce.rag.controller;

import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.service.DashboardService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 Dashboard 统计 Controller（P2-2 契约对齐 + 2026-08-15 用户裁决：全局统计口径）
 *
 * <p>提供三个统计端点（方法级路径区分，前缀 /api/v1/admin）：
 * <ul>
 *   <li>GET /api/v1/admin/dashboard/stats — 文档/待修正分片/知识库数（全局）</li>
 *   <li>GET /api/v1/admin/feedback/stats?period=today — 学生数/反馈数/点赞率（全局）</li>
 *   <li>GET /api/v1/admin/feedback/trend?days=7 — 近 N 天反馈趋势（全局）</li>
 * </ul>
 *
 * <p>2026-08-15 用户裁决：所有人都能看到，不区分是谁的学生——TEACHER 与 SUPER_ADMIN 所见一致（无 created_by 过滤）。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;

    /** Dashboard KPI：文档数 + 待修正分片数 + 知识库数（前端文档 :783；全局口径） */
    @GetMapping("/dashboard/stats")
    public ApiResponse<Map<String, Object>> dashboardStats() {
        return ApiResponse.ok(dashboardService.dashboardStats());
    }

    /** Dashboard KPI：周期内学生数 + 反馈数 + 点赞率（前端文档 :784；全局口径） */
    @GetMapping("/feedback/stats")
    public ApiResponse<Map<String, Object>> feedbackStats(@RequestParam(defaultValue = "today") String period) {
        return ApiResponse.ok(dashboardService.feedbackStats(period));
    }

    /** Dashboard 趋势图：近 N 天每日反馈数（前端文档 :786；全局口径） */
    @GetMapping("/feedback/trend")
    public ApiResponse<List<Map<String, Object>>> feedbackTrend(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(dashboardService.feedbackTrend(days));
    }
}
