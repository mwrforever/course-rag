package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthSessionService;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.auth.TokenService;
import com.commerce.rag.config.AuthProperties;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.LoginRequest;
import com.commerce.rag.controller.dto.LoginResponse;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.service.SysUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * AuthController 单元测试 —— 登录/刷新/登出端点
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController 认证端点测试")
class AuthControllerTest {

    @Mock
    private SysUserService sysUserService;

    @Mock
    private TokenService tokenService;

    @Mock
    private DeviceKickService deviceKickService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthSessionService authSessionService;

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private HttpServletResponse httpResponse;

    private AuthController authController;
    private AuthProperties authProperties;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties(
                "test-secret-key-must-be-at-least-256-bits-long-for-hs256!!",
                900,
                604800L,
                "commerce_token",
                "localhost",
                List.of("WEB_DESKTOP"));
        authController = new AuthController(
                sysUserService, tokenService, deviceKickService, authProperties, passwordEncoder, authSessionService);

        lenient().when(httpRequest.getHeader("User-Agent")).thenReturn("test-agent");
        lenient().when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    // ==================== login() 测试 ====================

    @Test
    @DisplayName("login → 正常登录返回 AT+RT")
    void login_validCredentials_returnsTokens() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hashed-pass");
        user.setDisplayName("测试用户");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");

        when(sysUserService.findByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("password123", "hashed-pass")).thenReturn(true);
        when(tokenService.generateJti()).thenReturn("jti-at", "jti-rt");
        when(tokenService.generateAccessToken(1L, "STUDENT", "jti-at")).thenReturn("access-token");
        when(tokenService.generateRefreshToken(1L, "jti-rt")).thenReturn("refresh-token");
        SysLoginRecord loginRecord = new SysLoginRecord();
        loginRecord.setId(10L);
        when(authSessionService.createLoginRecord(
                        eq(1L), eq("jti-at"), eq("jti-rt"), eq("WEB_DESKTOP"), eq("test-agent"), eq("127.0.0.1")))
                .thenReturn(loginRecord);

        ApiResponse<LoginResponse> result =
                authController.login(new LoginRequest("testuser", "password123", null), httpRequest, httpResponse);

        assertNotNull(result);
        assertEquals("access-token", result.data().accessToken());
        assertEquals("refresh-token", result.data().refreshToken());
        assertEquals(1L, result.data().userId());
        assertEquals("STUDENT", result.data().role());
        verify(deviceKickService).kickAndLogin(eq(1L), eq("WEB_DESKTOP"), anyString(), anyString(), eq(10L));
        // 登录记录创建下沉 AuthSessionService
        verify(authSessionService)
                .createLoginRecord(
                        eq(1L), eq("jti-at"), eq("jti-rt"), eq("WEB_DESKTOP"), eq("test-agent"), eq("127.0.0.1"));
        verify(httpResponse).addCookie(any(Cookie.class));
    }

    @Test
    @DisplayName("login → 用户不存在抛出 401")
    void login_userNotFound_throws401() {
        when(sysUserService.findByUsername("unknown")).thenReturn(null);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> authController.login(new LoginRequest("unknown", "password", null), httpRequest, httpResponse));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("login → 密码错误抛出 401")
    void login_wrongPassword_throws401() {
        SysUser user = new SysUser();
        user.setUsername("testuser");
        user.setPasswordHash("hashed-pass");
        user.setStatus("ACTIVE");

        when(sysUserService.findByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("wrongpass", "hashed-pass")).thenReturn(false);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> authController.login(new LoginRequest("testuser", "wrongpass", null), httpRequest, httpResponse));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("login → 被禁用用户抛出 403")
    void login_disabledUser_throws403() {
        SysUser user = new SysUser();
        user.setUsername("testuser");
        user.setPasswordHash("hashed-pass");
        user.setStatus("DISABLED");

        when(sysUserService.findByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("password", "hashed-pass")).thenReturn(true);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> authController.login(new LoginRequest("testuser", "password", null), httpRequest, httpResponse));
        assertEquals(403, ex.getStatusCode().value());
    }

    // ==================== logout() 测试 ====================

    @Test
    @DisplayName("logout → Bearer AT 解析成功：调用 revokeOnLogout 吊销会话 + 清 cookie")
    void logout_withBearerToken_callsRevokeOnLogout() {
        Claims claims = mock(Claims.class);
        when(httpRequest.getHeader("Authorization")).thenReturn("Bearer access-token");
        when(tokenService.parseClaimsLoose("access-token")).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("ACCESS");
        when(tokenService.extractUserId(claims)).thenReturn(123L);
        when(tokenService.extractJti(claims)).thenReturn("jti-at");

        ApiResponse<Void> result = authController.logout(httpRequest, httpResponse);

        assertNotNull(result);
        assertEquals(0, result.code());
        // 会话吊销编排下沉 AuthSessionService（黑名单/REVOKED 细节由 AuthSessionServiceTest 覆盖）
        verify(authSessionService).revokeOnLogout(eq(123L), eq("jti-at"));
        // 清除 cookie
        verify(httpResponse).addCookie(any(Cookie.class));
    }

    @Test
    @DisplayName("logout → 无 token：不吊销，仅清 cookie")
    void logout_noToken_onlyClearsCookie() {
        when(httpRequest.getHeader("Authorization")).thenReturn(null);
        when(httpRequest.getCookies()).thenReturn(null);

        ApiResponse<Void> result = authController.logout(httpRequest, httpResponse);

        assertNotNull(result);
        assertEquals(0, result.code());
        verify(authSessionService, never()).revokeOnLogout(any(), any());
        verify(httpResponse).addCookie(any(Cookie.class));
    }

    @Test
    @DisplayName("logout → token 类型为 REFRESH：不吊销，仅清 cookie")
    void logout_withRefreshTokenType_onlyClearsCookie() {
        Claims claims = mock(Claims.class);
        when(httpRequest.getHeader("Authorization")).thenReturn("Bearer refresh-token");
        when(tokenService.parseClaimsLoose("refresh-token")).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("REFRESH");

        ApiResponse<Void> result = authController.logout(httpRequest, httpResponse);

        assertNotNull(result);
        assertEquals(0, result.code());
        verify(authSessionService, never()).revokeOnLogout(any(), any());
        verify(httpResponse).addCookie(any(Cookie.class));
    }

    @Test
    @DisplayName("logout → token 解析异常：降级仅清 cookie，不抛异常")
    void logout_parseFailure_onlyClearsCookie() {
        when(httpRequest.getHeader("Authorization")).thenReturn("Bearer broken-token");
        when(tokenService.parseClaimsLoose("broken-token")).thenThrow(new JwtException("签名无效"));

        ApiResponse<Void> result = assertDoesNotThrow(() -> authController.logout(httpRequest, httpResponse));

        assertNotNull(result);
        assertEquals(0, result.code());
        verify(authSessionService, never()).revokeOnLogout(any(), any());
        verify(httpResponse).addCookie(any(Cookie.class));
    }
}
