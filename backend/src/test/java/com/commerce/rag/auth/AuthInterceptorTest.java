package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.properties.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * AuthInterceptor 单元测试 —— 请求拦截鉴权逻辑
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthInterceptor 请求拦截鉴权测试")
class AuthInterceptorTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private DeviceKickService deviceKickService;

    @Mock
    private AuthProperties authProperties;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private AuthInterceptor authInterceptor;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(authProperties.cookieName()).thenReturn("commerce_token");
        authInterceptor = new AuthInterceptor(tokenService, deviceKickService, authProperties);

        // response.getWriter() mock（lenient，非所有测试都用到）
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));
    }

    /** 每个用例结束后清理 SecurityContext，防止断言失败时 ThreadLocal 泄漏污染其他测试 */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("preHandle → 无 Token 返回 false + 401")
    void preHandle_noToken_returns401() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getCookies()).thenReturn(null);

        boolean result = authInterceptor.preHandle(request, response, null);

        assertFalse(result, "无 Token 应返回 false");
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("preHandle → Bearer Token 验证通过 + 注入 userId/role/jti")
    void preHandle_validBearerToken_injectsAttributes() throws Exception {
        String token = "valid.token.here";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        Claims claims = mock(Claims.class);
        when(tokenService.validateToken(token)).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("ACCESS");
        when(deviceKickService.isBlacklisted(anyString())).thenReturn(false);
        when(tokenService.extractUserId(claims)).thenReturn(123L);
        when(tokenService.extractRole(claims)).thenReturn("TEACHER");
        when(tokenService.extractJti(claims)).thenReturn("jti-abc");

        boolean result = authInterceptor.preHandle(request, response, null);

        assertTrue(result);
        verify(request).setAttribute(AuthInterceptor.ATTR_USER_ID, 123L);
        verify(request).setAttribute(AuthInterceptor.ATTR_ROLE, "TEACHER");
        verify(request).setAttribute(AuthInterceptor.ATTR_JTI, "jti-abc");
    }

    @Test
    @DisplayName("preHandle → Cookie 中提取 Token 验证通过")
    void preHandle_validCookieToken_injectsAttributes() throws Exception {
        String token = "valid.token.from.cookie";
        when(request.getHeader("Authorization")).thenReturn(null);
        Cookie cookie = new Cookie("commerce_token", token);
        when(request.getCookies()).thenReturn(new Cookie[] {cookie});

        Claims claims = mock(Claims.class);
        when(tokenService.validateToken(token)).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("ACCESS");
        when(deviceKickService.isBlacklisted(anyString())).thenReturn(false);
        when(tokenService.extractUserId(claims)).thenReturn(456L);
        when(tokenService.extractRole(claims)).thenReturn("STUDENT");
        when(tokenService.extractJti(claims)).thenReturn("jti-xyz");

        boolean result = authInterceptor.preHandle(request, response, null);

        assertTrue(result);
        verify(request).setAttribute(AuthInterceptor.ATTR_USER_ID, 456L);
    }

    @Test
    @DisplayName("preHandle → 无效 Token 返回 false + 401")
    void preHandle_invalidToken_returns401() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid.token");

        when(tokenService.validateToken("invalid.token")).thenThrow(new JwtException("invalid token"));

        boolean result = authInterceptor.preHandle(request, response, null);

        assertFalse(result);
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("preHandle → REFRESH 类型 Token 被拒绝")
    void preHandle_refreshTokenRejected_returns401() throws Exception {
        String token = "refresh.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        Claims claims = mock(Claims.class);
        when(tokenService.validateToken(token)).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("REFRESH");

        boolean result = authInterceptor.preHandle(request, response, null);

        assertFalse(result);
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("preHandle → 黑名单 Token 被拒绝")
    void preHandle_blacklistedToken_returns401() throws Exception {
        String token = "blacklisted.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        Claims claims = mock(Claims.class);
        when(tokenService.validateToken(token)).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("ACCESS");
        when(tokenService.extractJti(claims)).thenReturn("jti-blacklisted");
        when(deviceKickService.isBlacklisted(anyString())).thenReturn(true);

        boolean result = authInterceptor.preHandle(request, response, null);

        assertFalse(result);
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("preHandle 校验通过后 SecurityContext 写入 ROLE_ 前缀 authority")
    void preHandle_writesSecurityContext() throws Exception {
        String token = "valid.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        Claims claims = mock(Claims.class);
        when(tokenService.validateToken(token)).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("ACCESS");
        when(deviceKickService.isBlacklisted(anyString())).thenReturn(false);
        when(tokenService.extractUserId(claims)).thenReturn(123L);
        when(tokenService.extractRole(claims)).thenReturn("TEACHER");
        when(tokenService.extractJti(claims)).thenReturn("jti-abc");

        boolean result = authInterceptor.preHandle(request, response, null);

        assertTrue(result);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication, "校验通过后应写入 SecurityContext");
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> "ROLE_TEACHER".equals(a.getAuthority())));
        // 清理（afterCompletion 的行为单独断言）
        authInterceptor.afterCompletion(request, response, null, null);
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "afterCompletion 应清空 SecurityContext");
    }
}
