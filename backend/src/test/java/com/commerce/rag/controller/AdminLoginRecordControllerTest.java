package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.convert.AdminLoginRecordConverter;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.service.ISysLoginRecordService;
import com.commerce.rag.vo.SysLoginRecordVO;
import com.commerce.rag.vo.SysTokenBlacklistVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AdminLoginRecordController 单元测试 —— 登录记录与 Token 黑名单端点 K1-K7
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminLoginRecordController 登录记录与黑名单端点测试")
class AdminLoginRecordControllerTest {

    @Mock
    private ISysLoginRecordService sysLoginRecordService;

    @Mock
    private AdminLoginRecordConverter converter;

    private AdminLoginRecordController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminLoginRecordController(sysLoginRecordService, converter);
    }

    private SysLoginRecord loginRecord(Long id) {
        SysLoginRecord r = new SysLoginRecord();
        r.setId(id);
        r.setUserId(5L);
        r.setJtiAt("jti-at-1");
        r.setJtiRt("jti-rt-1");
        r.setDeviceType("PC");
        r.setDeviceInfo("Chrome");
        r.setIpAddress("127.0.0.1");
        r.setStatus("ACTIVE");
        r.setExpiresAt(LocalDateTime.now().plusDays(1));
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    private SysLoginRecordVO loginRecordVO(Long id) {
        return new SysLoginRecordVO(
                id,
                5L,
                "jti-at-1",
                "jti-rt-1",
                "PC",
                "Chrome",
                "127.0.0.1",
                LocalDateTime.now().plusDays(1),
                "ACTIVE",
                LocalDateTime.now(),
                null);
    }

    private SysTokenBlacklistVO blacklistVO(Long id) {
        return new SysTokenBlacklistVO(
                id,
                "jti-1",
                "ACCESS",
                5L,
                1L,
                "MANUAL_REVOKE",
                LocalDateTime.now().plusDays(7),
                null);
    }

    @Test
    @DisplayName("K1 listLoginRecords → 透传筛选条件返回分页记录（VO）")
    void listLoginRecords_returnsPaged() {
        Page<SysLoginRecordVO> paged = new Page<>(1, 20);
        paged.setRecords(List.of(loginRecordVO(1L)));
        paged.setTotal(1);
        when(sysLoginRecordService.findPage(1, 20, 5L, "PC", "ACTIVE")).thenReturn(paged);

        ApiResponse<PageResponse<SysLoginRecordVO>> result = controller.listLoginRecords(1, 20, 5L, "PC", "ACTIVE");

        assertEquals(1, result.data().records().size());
        assertEquals(5L, result.data().records().get(0).userId());
        assertEquals("jti-at-1", result.data().records().get(0).jtiAt());
    }

    @Test
    @DisplayName("K2 getLoginRecord → 返回记录详情（VO）")
    void getLoginRecord_returnsRecord() {
        when(sysLoginRecordService.findById(1L)).thenReturn(loginRecord(1L));
        when(converter.toLoginRecordVO(any(SysLoginRecord.class))).thenReturn(loginRecordVO(1L));

        ApiResponse<SysLoginRecordVO> result = controller.getLoginRecord(1L);

        assertEquals("jti-at-1", result.data().jtiAt());
    }

    @Test
    @DisplayName("K3 revokeLoginRecord → 携带管理员 ID 调用 revoke")
    void revokeLoginRecord_passesAdminId() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(9L);

        controller.revokeLoginRecord(1L, req);

        verify(sysLoginRecordService).revoke(1L, 9L);
    }

    @Test
    @DisplayName("K4 listBlacklist → 透传筛选条件返回分页黑名单（VO）")
    void listBlacklist_returnsPaged() {
        Page<SysTokenBlacklistVO> paged = new Page<>(1, 20);
        paged.setRecords(List.of(blacklistVO(1L)));
        paged.setTotal(1);
        when(sysLoginRecordService.findBlacklistPage(1, 20, 5L, "jti-1", "ACCESS"))
                .thenReturn(paged);

        ApiResponse<PageResponse<SysTokenBlacklistVO>> result = controller.listBlacklist(1, 20, 5L, "jti-1", "ACCESS");

        assertEquals(1, result.data().records().size());
        assertEquals("jti-1", result.data().records().get(0).jti());
    }

    @Test
    @DisplayName("K5 addToBlacklist → 未传 expiresAt 时默认 7 天后过期")
    void addToBlacklist_noExpiresAt_usesSevenDaysDefault() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(9L);
        LocalDateTime before = LocalDateTime.now().plusDays(7);

        controller.addToBlacklist("jti-1", "ACCESS", 5L, "MANUAL_REVOKE", null, req);

        // 断言 service 收到非 null 且约为 7 天后的过期时间（避免硬编码时间导致 flaky）
        verify(sysLoginRecordService)
                .addToBlacklist(eq("jti-1"), eq("ACCESS"), eq(5L), eq(9L), eq("MANUAL_REVOKE"), argThat(expires -> {
                    long seconds = Duration.between(before, expires).getSeconds();
                    return expires != null && Math.abs(seconds) < 5;
                }));
    }

    @Test
    @DisplayName("K5 addToBlacklist → 显式 expiresAt 时按入参解析")
    void addToBlacklist_withExpiresAt_parsesExplicitTime() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(9L);
        String expiresAt = "2026-08-20T12:00:00";

        controller.addToBlacklist("jti-2", "REFRESH", 5L, "MANUAL_REVOKE", expiresAt, req);

        verify(sysLoginRecordService)
                .addToBlacklist("jti-2", "REFRESH", 5L, 9L, "MANUAL_REVOKE", LocalDateTime.parse(expiresAt));
    }

    @Test
    @DisplayName("K6 deleteFromBlacklist → 调用删除")
    void deleteFromBlacklist_callsService() {
        controller.deleteFromBlacklist(1L);

        verify(sysLoginRecordService).deleteFromBlacklist(1L);
    }

    @Test
    @DisplayName("K7 cleanupBlacklist → 返回清理数量")
    void cleanupBlacklist_returnsCount() {
        when(sysLoginRecordService.cleanupExpiredBlacklist()).thenReturn(3);

        ApiResponse<Map<String, Integer>> result = controller.cleanupBlacklist();

        assertEquals(3, result.data().get("cleaned"));
    }
}
