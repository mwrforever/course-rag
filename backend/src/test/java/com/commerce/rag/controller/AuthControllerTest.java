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
import com.commerce.rag.controller.dto.RefreshRequest;
import com.commerce.rag.controller.dto.UserDTO;
import com.commerce.rag.service.AuthUserView;
import com.commerce.rag.service.SysUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
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
                false,
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
        AuthUserView user = new AuthUserView(1L, "testuser", "hashed-pass", "STUDENT", "测试用户", "ACTIVE");

        when(sysUserService.findAuthViewByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("password123", "hashed-pass")).thenReturn(true);
        when(tokenService.generateJti()).thenReturn("jti-at", "jti-rt");
        when(tokenService.generateAccessToken(1L, "STUDENT", "jti-at")).thenReturn("access-token");
        when(tokenService.generateRefreshToken(1L, "jti-rt")).thenReturn("refresh-token");
        // createLoginRecord 返回登录记录主键（Entity 不出 service 边界）
        when(authSessionService.createLoginRecord(
                        eq(1L), eq("jti-at"), eq("jti-rt"), eq("WEB_DESKTOP"), eq("test-agent"), eq("127.0.0.1")))
                .thenReturn(10L);

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
        when(sysUserService.findAuthViewByUsername("unknown")).thenReturn(null);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> authController.login(new LoginRequest("unknown", "password", null), httpRequest, httpResponse));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("login → 密码错误抛出 401")
    void login_wrongPassword_throws401() {
        AuthUserView user = new AuthUserView(1L, "testuser", "hashed-pass", "STUDENT", "测试用户", "ACTIVE");

        when(sysUserService.findAuthViewByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("wrongpass", "hashed-pass")).thenReturn(false);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> authController.login(new LoginRequest("testuser", "wrongpass", null), httpRequest, httpResponse));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("login → 被禁用用户抛出 403")
    void login_disabledUser_throws403() {
        AuthUserView user = new AuthUserView(1L, "testuser", "hashed-pass", "STUDENT", "测试用户", "DISABLED");

        when(sysUserService.findAuthViewByUsername("testuser")).thenReturn(user);
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

    // ==================== refresh() 测试 ====================

    @Test
    @DisplayName("refresh → RT 有效且未使用：原子标记成功 + 黑名单检查通过，返回新 Token 对")
    void refresh_validRt_returnsNewTokens() {
        Claims claims = mock(Claims.class);
        when(tokenService.validateToken("valid-rt")).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("REFRESH");
        when(tokenService.extractJti(claims)).thenReturn("old-jti-rt");
        when(tokenService.extractUserId(claims)).thenReturn(1L);
        // 原子检查+置位成功（首次使用抢占成功）
        when(deviceKickService.markRefreshTokenUsedAtomic("old-jti-rt")).thenReturn(true);
        when(deviceKickService.isBlacklisted("old-jti-rt")).thenReturn(false);
        when(sysUserService.findById(1L))
                .thenReturn(new UserDTO(1L, "testuser", "测试用户", "STUDENT", "ACTIVE", LocalDateTime.now()));
        when(tokenService.generateJti()).thenReturn("new-jti-at", "new-jti-rt");
        when(tokenService.generateAccessToken(1L, "STUDENT", "new-jti-at")).thenReturn("new-access-token");
        when(tokenService.generateRefreshToken(1L, "new-jti-rt")).thenReturn("new-refresh-token");
        when(claims.getExpiration()).thenReturn(Date.from(Instant.now().plusSeconds(604800L)));

        ApiResponse<LoginResponse> result = authController.refresh(new RefreshRequest("valid-rt"), httpResponse);

        assertNotNull(result);
        assertEquals("new-access-token", result.data().accessToken());
        assertEquals("new-refresh-token", result.data().refreshToken());
        // 步骤 3：原子检查并标记 RT 已使用（先于黑名单检查）
        verify(deviceKickService).markRefreshTokenUsedAtomic("old-jti-rt");
        // 步骤 4：黑名单检查保留在原子标记之后
        verify(deviceKickService).isBlacklisted("old-jti-rt");
        // 步骤 8：旧 RT 入黑名单
        verify(deviceKickService)
                .addToBlacklist(
                        eq("old-jti-rt"), eq("REFRESH"), eq(1L), eq(1L), eq("TOKEN_REUSE"), any(LocalDateTime.class));
        // 步骤 9：登录记录更新下沉 AuthSessionService
        verify(authSessionService)
                .updateLoginRecordOnRefresh(eq(1L), eq("old-jti-rt"), eq("new-jti-at"), eq("new-jti-rt"));
        verify(httpResponse).addCookie(any(Cookie.class));
    }

    @Test
    @DisplayName("refresh → RT 复用（原子标记失败）：401 + disableUser 全量作废")
    void refresh_rtReuse_throws401AndDisablesUser() {
        Claims claims = mock(Claims.class);
        when(tokenService.validateToken("reused-rt")).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("REFRESH");
        when(tokenService.extractJti(claims)).thenReturn("old-jti-rt");
        when(tokenService.extractUserId(claims)).thenReturn(1L);
        // 原子标记返回 false（RT 已被使用）
        when(deviceKickService.markRefreshTokenUsedAtomic("old-jti-rt")).thenReturn(false);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> authController.refresh(new RefreshRequest("reused-rt"), httpResponse));

        assertEquals(401, ex.getStatusCode().value());
        // RT 复用 → 全量作废该用户所有 Token
        verify(deviceKickService).disableUser(1L, 1L);
        // 短路：黑名单检查不再执行、未生成新 Token
        verify(deviceKickService, never()).isBlacklisted(anyString());
        verify(tokenService, never()).generateJti();
    }
}
