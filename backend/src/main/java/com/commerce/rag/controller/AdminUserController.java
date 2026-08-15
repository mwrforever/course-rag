package com.commerce.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.CreateUserRequest;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.controller.dto.ResetPasswordRequest;
import com.commerce.rag.controller.dto.UpdateStatusRequest;
import com.commerce.rag.controller.dto.UpdateUserRequest;
import com.commerce.rag.controller.dto.UserDTO;
import com.commerce.rag.service.SysUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用户管理 Controller —— CRUD A1-A7
 *
 * <p>权限：SUPER_ADMIN 全部 / TEACHER 仅自己创建的学生。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final SysUserService sysUserService;

    public AdminUserController(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    /** A1: 用户列表（分页 + 角色筛选，教师仅见自己创建的用户） */
    @GetMapping
    public ApiResponse<PageResponse<UserDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {

        Long currentUserId = AuthInterceptor.getCurrentUserId(request);
        // P2-2: 教师过滤按 DB 最新角色（service 内 resolveDbRole），token 角色不参与权限判定
        IPage<UserDTO> result = sysUserService.findPage(page, size, role, status, currentUserId);
        PageResponse<UserDTO> response = new PageResponse<>(result.getRecords(), result.getTotal(), page, size);
        return ApiResponse.ok(response);
    }

    /** A2: 创建用户（超管唯一性校验，教师仅可创建学生——P2-2：角色判定按 DB 最新角色） */
    @PostMapping
    public ApiResponse<UserDTO> create(@Valid @RequestBody CreateUserRequest request, HttpServletRequest httpRequest) {
        Long currentUserId = (Long) httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sysUserService.create(request, currentUserId));
    }

    /** A3: 查看用户（教师仅见自己创建的学生） */
    @GetMapping("/{id}")
    public ApiResponse<UserDTO> get(@PathVariable Long id, HttpServletRequest request) {
        Long currentUserId = AuthInterceptor.getCurrentUserId(request);
        String operatorRole = AuthInterceptor.getCurrentRole(request);
        UserDTO user = sysUserService.findById(id, currentUserId, operatorRole);
        if (user == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return ApiResponse.ok(user);
    }

    /** A4: 更新用户（超管不可改角色） */
    @PutMapping("/{id}")
    public ApiResponse<UserDTO> update(
            @PathVariable Long id, @Valid @RequestBody UpdateUserRequest request, HttpServletRequest httpRequest) {
        Long currentUserId = (Long) httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(sysUserService.update(id, request, currentUserId));
    }

    /** A5: 删除用户（超管不可删） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long currentUserId = (Long) httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID);
        sysUserService.delete(id, currentUserId);
        return ApiResponse.ok();
    }

    /** A6: 重置密码 */
    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(
            @PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        Long currentUserId = (Long) httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID);
        sysUserService.resetPassword(id, request.newPassword(), currentUserId);
        return ApiResponse.ok();
    }

    /** A7: 启用/禁用用户（超管不可禁用） */
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(
            @PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request, HttpServletRequest httpRequest) {
        Long currentUserId = (Long) httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID);
        sysUserService.updateStatus(id, request.status(), currentUserId);
        return ApiResponse.ok();
    }
}
