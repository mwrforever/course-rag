package com.commerce.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.controller.vo.SysLoginRecordVO;
import com.commerce.rag.controller.vo.SysTokenBlacklistVO;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.service.AdminLoginRecordConverter;
import com.commerce.rag.service.SysLoginRecordService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录记录 + Token 黑名单管理 Controller —— CRUD K1-K7
 *
 * <p>权限：SUPER_ADMIN 全部。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminLoginRecordController {

    private final SysLoginRecordService sysLoginRecordService;
    private final AdminLoginRecordConverter converter;

    public AdminLoginRecordController(
            SysLoginRecordService sysLoginRecordService, AdminLoginRecordConverter converter) {
        this.sysLoginRecordService = sysLoginRecordService;
        this.converter = converter;
    }

    // ========================================================================
    // 登录记录管理（K1-K3）
    // ========================================================================

    /** K1: 登录记录列表（分页 + 用户/设备/状态筛选） */
    @GetMapping("/login-records")
    public ApiResponse<PageResponse<SysLoginRecordVO>> listLoginRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String status) {

        IPage<SysLoginRecord> result = sysLoginRecordService.findPage(page, size, userId, deviceType, status);
        List<SysLoginRecordVO> records =
                result.getRecords().stream().map(converter::toLoginRecordVO).toList();
        return ApiResponse.ok(new PageResponse<>(records, result.getTotal(), page, size));
    }

    /** K2: 查看登录记录详情 */
    @GetMapping("/login-records/{id}")
    public ApiResponse<SysLoginRecordVO> getLoginRecord(@PathVariable Long id) {
        return ApiResponse.ok(converter.toLoginRecordVO(sysLoginRecordService.findById(id)));
    }

    /** K3: 踢出设备（标记 REVOKED + jti 入黑名单） */
    @PostMapping("/login-records/{id}/revoke")
    public ApiResponse<Void> revokeLoginRecord(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long adminUserId = (Long) httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID);
        sysLoginRecordService.revoke(id, adminUserId);
        return ApiResponse.ok();
    }

    // ========================================================================
    // Token 黑名单管理（K4-K7）
    // ========================================================================

    /** K4: 黑名单列表（分页 + 用户/jti/类型筛选） */
    @GetMapping("/token-blacklist")
    public ApiResponse<PageResponse<SysTokenBlacklistVO>> listBlacklist(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String jti,
            @RequestParam(required = false) String tokenType) {

        IPage<SysTokenBlacklist> result = sysLoginRecordService.findBlacklistPage(page, size, userId, jti, tokenType);
        List<SysTokenBlacklistVO> records =
                result.getRecords().stream().map(converter::toBlacklistVO).toList();
        return ApiResponse.ok(new PageResponse<>(records, result.getTotal(), page, size));
    }

    /** K5: 手动添加黑名单 */
    @PostMapping("/token-blacklist")
    public ApiResponse<Void> addToBlacklist(
            @RequestParam String jti,
            @RequestParam String tokenType,
            @RequestParam Long userId,
            @RequestParam(defaultValue = "MANUAL_REVOKE") String reason,
            @RequestParam(required = false) String expiresAt,
            HttpServletRequest httpRequest) {
        Long adminUserId = (Long) httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID);
        LocalDateTime expires = expiresAt != null
                ? LocalDateTime.parse(expiresAt)
                : LocalDateTime.now().plusDays(7);
        sysLoginRecordService.addToBlacklist(jti, tokenType, userId, adminUserId, reason, expires);
        return ApiResponse.ok();
    }

    /** K6: 删除黑名单记录（Token 过期后可清理） */
    @DeleteMapping("/token-blacklist/{id}")
    public ApiResponse<Void> deleteFromBlacklist(@PathVariable Long id) {
        sysLoginRecordService.deleteFromBlacklist(id);
        return ApiResponse.ok();
    }

    /** K7: 清理过期黑名单 */
    @PostMapping("/token-blacklist/cleanup")
    public ApiResponse<Map<String, Integer>> cleanupBlacklist() {
        int count = sysLoginRecordService.cleanupExpiredBlacklist();
        return ApiResponse.ok(Map.of("cleaned", count));
    }
}
