package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.service.DashboardService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * AdminDashboardController 契约测试 —— 三统计端点与前端文档对齐（P2-2）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminDashboardController 契约测试")
class AdminDashboardControllerTest {

    @Mock
    private DashboardService dashboardService;

    private AdminDashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminDashboardController(dashboardService);
    }

    @Test
    @DisplayName("契约 — dashboardStats 映射 GET /dashboard/stats（:783）")
    void dashboardStats_mapsToPath() throws Exception {
        var method = AdminDashboardController.class.getMethod("dashboardStats");
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/dashboard/stats"}, mapping.value());
        when(dashboardService.dashboardStats()).thenReturn(Map.of("documentCount", 1L));
        assertEquals(1L, controller.dashboardStats().data().get("documentCount"));
    }

    @Test
    @DisplayName("契约 — feedbackStats 映射 GET /feedback/stats 且 period 默认 today（:784）")
    void feedbackStats_mapsToPath() throws Exception {
        var method = AdminDashboardController.class.getMethod("feedbackStats", String.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/feedback/stats"}, mapping.value());
        when(dashboardService.feedbackStats("today")).thenReturn(Map.of("sessionCount", 5L));
        assertEquals(5L, controller.feedbackStats("today").data().get("sessionCount"));
    }

    @Test
    @DisplayName("契约 — feedbackTrend 映射 GET /feedback/trend 且 days 默认 7（:786）")
    void feedbackTrend_mapsToPath() throws Exception {
        var method = AdminDashboardController.class.getMethod("feedbackTrend", int.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/feedback/trend"}, mapping.value());
        when(dashboardService.feedbackTrend(7)).thenReturn(List.of());
        assertNotNull(controller.feedbackTrend(7).data());
    }
}
