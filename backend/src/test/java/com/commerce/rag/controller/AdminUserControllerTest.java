package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.*;
import com.commerce.rag.dto.*;
import com.commerce.rag.service.SysUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * AdminUserController 单元测试 —— 用户管理 CRUD 端点
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserController 用户管理端点测试")
class AdminUserControllerTest {

    @Mock
    private SysUserService sysUserService;

    @Mock
    private HttpServletRequest httpRequest;

    @InjectMocks
    private AdminUserController controller;

    @Test
    @DisplayName("create → 调用 sysUserService.create 返回 DTO")
    void create_callsServiceCreate() {
        CreateUserRequest request = new CreateUserRequest("newuser", "pass123", "新用户", "STUDENT");
        UserDTO dto = new UserDTO(1L, "newuser", "新用户", "STUDENT", "ACTIVE", null);
        // P2-2: create 不再读取 token 角色（按 DB 最新角色判定），仅注入 userId
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(100L);
        when(sysUserService.create(request, 100L)).thenReturn(dto);

        ApiResponse<UserDTO> result = controller.create(request, httpRequest);

        assertEquals(0, result.code());
        assertEquals(1L, result.data().id());
    }

    @Test
    @DisplayName("get → 调用 sysUserService.findById 返回 DTO")
    void get_callsServiceFindById() {
        UserDTO dto = new UserDTO(1L, "user", "用户", "TEACHER", "ACTIVE", null);
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(100L);
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("SUPER_ADMIN");
        when(sysUserService.findById(1L, 100L, "SUPER_ADMIN")).thenReturn(dto);

        ApiResponse<UserDTO> result = controller.get(1L, httpRequest);

        assertEquals(1L, result.data().id());
    }

    @Test
    @DisplayName("get → 服务返回 null（不存在/无归属权）时抛 ResponseStatusException 404（P1-3 真实 HTTP 状态码）")
    void get_serviceReturnsNull_throws404() {
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(100L);
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("TEACHER");
        when(sysUserService.findById(999L, 100L, "TEACHER")).thenReturn(null);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.get(999L, httpRequest));

        assertEquals(404, ex.getStatusCode().value());
        assertEquals("用户不存在", ex.getReason());
    }

    @Test
    @DisplayName("list → 调用 sysUserService.findPage 传入 userId/role")
    void list_callsServiceFindPage() {
        // P2-2: findPage 不再读取 token 角色（按 DB 最新角色判定），仅注入 userId
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(100L);
        when(sysUserService.findPage(1, 20, null, null, 100L)).thenReturn(new Page<UserDTO>(1, 20));

        ApiResponse<PageResponse<UserDTO>> result = controller.list(1, 20, null, null, httpRequest);

        assertEquals(0, result.code());
        verify(sysUserService).findPage(1, 20, null, null, 100L);
    }

    @Test
    @DisplayName("delete → 调用 sysUserService.delete")
    void delete_callsServiceDelete() {
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(100L);

        ApiResponse<Void> result = controller.delete(1L, httpRequest);

        assertEquals(0, result.code());
        verify(sysUserService).delete(1L, 100L);
    }

    @Test
    @DisplayName("resetPassword → 调用 sysUserService.resetPassword")
    void resetPassword_callsServiceResetPassword() {
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(100L);

        ApiResponse<Void> result = controller.resetPassword(1L, new ResetPasswordRequest("newpass"), httpRequest);

        assertEquals(0, result.code());
        verify(sysUserService).resetPassword(1L, "newpass", 100L);
    }

    @Test
    @DisplayName("updateStatus → 调用 sysUserService.updateStatus")
    void updateStatus_callsServiceUpdateStatus() {
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(100L);

        ApiResponse<Void> result = controller.updateStatus(1L, new UpdateStatusRequest("DISABLED"), httpRequest);

        assertEquals(0, result.code());
        verify(sysUserService).updateStatus(1L, "DISABLED", 100L);
    }
}
