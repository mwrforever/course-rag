package com.commerce.rag.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.auth.SecurityConfig;
import com.commerce.rag.auth.TokenService;
import com.commerce.rag.service.SysUserService;
import io.jsonwebtoken.Claims;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 权限桥接集成测试 —— 验证 AuthInterceptor 写入 SecurityContext 后 @PreAuthorize 真正生效
 *
 * <p>@WebMvcTest 切片：加载 AdminUserController + SecurityConfig（方法级鉴权）+ AuthConfig/AuthInterceptor（MVC 拦截器）。
 * 场景：TEACHER 合法 token → 200；STUDENT → 403；无 token → 401。
 *
 * @author commerce-rag
 */
@WebMvcTest(controllers = AdminUserController.class)
@Import({SecurityConfig.class, AuthInterceptor.class})
@DisplayName("权限桥接 SecurityContext 集成测试")
class AdminUserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private DeviceKickService deviceKickService;

    @MockBean
    private SysUserService sysUserService;

    // RETURNS_DEEP_STUBS：@WebMvcTest 切片不加载 MyBatis 自动配置（无真实 SqlSessionFactory），
    // 但主类 @MapperScan 仍会注册全部 Mapper bean，其初始化链路会调用
    // getConfiguration().getDefaultExecutorType()/getEnvironment().getDataSource() ——
    // 深桩 mock 让 Mapper 可初始化（本测试不触达数据库，故无需真实工厂）
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    private SqlSessionFactory sqlSessionFactory;

    private Claims stubToken(String role) throws Exception {
        Claims claims = mock(Claims.class);
        when(tokenService.validateToken("test-token")).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("ACCESS");
        when(tokenService.extractUserId(claims)).thenReturn(123L);
        when(tokenService.extractRole(claims)).thenReturn(role);
        when(tokenService.extractJti(claims)).thenReturn("jti-1");
        when(deviceKickService.isBlacklisted("jti-1")).thenReturn(false);
        return claims;
    }

    @Test
    @DisplayName("TEACHER 携带合法 token 访问 admin 端点 → 200")
    void teacherWithValidToken_returns200() throws Exception {
        stubToken("TEACHER");
        // P0-2f：findPage 为 6 参新签名（含 currentUserId / operatorRole）
        when(sysUserService.findPage(anyInt(), anyInt(), isNull(), isNull(), any(), any()))
                .thenReturn(new Page<>());

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("STUDENT 携带合法 token 访问 admin 端点 → 403")
    void studentWithValidToken_returns403() throws Exception {
        stubToken("STUDENT");

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("无 token 访问 admin 端点 → 401")
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
    }
}
