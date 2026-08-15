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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
    @DisplayName("契约 — 类级 @RequestMapping /api/v1/admin + @PreAuthorize + 参数默认值")
    void classLevelMappingAndDefaults() throws Exception {
        // 类级映射前缀（三个端点共享）
        RequestMapping classMapping = AdminDashboardController.class.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping, "必须声明类级 @RequestMapping");
        assertArrayEquals(new String[] {"/api/v1/admin"}, classMapping.value(), "前缀应为 /api/v1/admin");
        // 权限注解存在（角色限制不放开）
        PreAuthorize preAuthorize = AdminDashboardController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(preAuthorize, "必须声明 @PreAuthorize");
        assertTrue(preAuthorize.value().contains("SUPER_ADMIN"), "应允许 SUPER_ADMIN: " + preAuthorize.value());
        // 参数默认值契约：period=today / days=7（前端文档 :784/:786）
        RequestParam periodParam = AdminDashboardController.class
                .getMethod("feedbackStats", String.class)
                .getParameters()[0]
                .getAnnotation(RequestParam.class);
        assertEquals("today", periodParam.defaultValue(), "period 默认值应为 today");
        RequestParam daysParam = AdminDashboardController.class
                .getMethod("feedbackTrend", int.class)
                .getParameters()[0]
                .getAnnotation(RequestParam.class);
        assertEquals("7", daysParam.defaultValue(), "days 默认值应为 7");
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
