package com.commerce.rag.auth;

import com.commerce.rag.properties.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器 —— Spring MVC HandlerInterceptor
 *
 * <p>preHandle 流程：
 * <ol>
 *   <li>从 cookie/header 提取 Access Token</li>
 *   <li>验证 Token 签名 + 过期时间</li>
 *   <li>检查 Token 是否在黑名单中</li>
 *   <li>注入 userId 到 request attribute</li>
 * </ol>
 *
 * <p>排除 /api/v1/auth/** 路径（由 AuthConfig 配置）。
 *
 * @author commerce-rag
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** Request attribute key: 当前用户 ID */
    public static final String ATTR_USER_ID = "currentUserId";
    /** Request attribute key: 当前用户角色 */
    public static final String ATTR_ROLE = "currentUserRole";
    /** Request attribute key: 当前 jti */
    public static final String ATTR_JTI = "currentJti";

    private final TokenService tokenService;
    private final DeviceKickService deviceKickService;
    private final AuthProperties authProperties;

    public AuthInterceptor(
            TokenService tokenService, DeviceKickService deviceKickService, AuthProperties authProperties) {
        this.tokenService = tokenService;
        this.deviceKickService = deviceKickService;
        this.authProperties = authProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 1. 提取 AT
        String token = extractToken(request, authProperties.cookieName());
        if (token == null || token.isEmpty()) {
            sendUnauthorized(response, "未提供认证令牌");
            return false;
        }

        // 2. 验证 Token
        Claims claims;
        try {
            claims = tokenService.validateToken(token);
        } catch (JwtException e) {
            sendUnauthorized(response, "认证令牌无效或已过期");
            return false;
        }

        // 3. 检查类型（必须是 ACCESS）
        String tokenType = tokenService.extractTokenType(claims);
        if (!"ACCESS".equals(tokenType)) {
            sendUnauthorized(response, "令牌类型错误");
            return false;
        }

        // 4. 检查黑名单
        String jti = tokenService.extractJti(claims);
        if (deviceKickService.isBlacklisted(jti)) {
            sendUnauthorized(response, "令牌已被吊销");
            return false;
        }

        // 5. 注入 userId / role / jti 到 request attribute
        Long userId = tokenService.extractUserId(claims);
        String role = tokenService.extractRole(claims);

        request.setAttribute(ATTR_USER_ID, userId);
        request.setAttribute(ATTR_ROLE, role);
        request.setAttribute(ATTR_JTI, jti);

        // 6. 权限桥接：将 JWT 鉴权结果写入 Spring Security 上下文，
        //    供 @PreAuthorize 方法级鉴权读取（hasAnyRole 自动补 ROLE_ 前缀）
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));

        log.debug("认证通过: userId={}, role={}, jti={}", userId, role, jti);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清理 SecurityContext：请求结束后必须清空，防止线程池复用串上下文
        SecurityContextHolder.clearContext();
    }

    /**
     * 从 Cookie 或 Authorization header 提取 Token（公共静态，供登出等拦截器外场景复用）
     *
     * <p>优先从 Authorization header（Bearer xxx）提取，其次从 Cookie 提取。
     *
     * @param request    当前 HTTP 请求
     * @param cookieName 认证 Cookie 名称（由 AuthProperties 注入）
     * @return Token 字符串；两者皆无返回 null
     */
    public static String extractToken(HttpServletRequest request, String cookieName) {
        // 1. Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        // 2. Cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }

    /**
     * 发送 401 Unauthorized 响应
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
    }

    /**
     * 从 request attribute 获取当前用户 ID
     */
    public static Long getCurrentUserId(HttpServletRequest request) {
        return (Long) request.getAttribute(ATTR_USER_ID);
    }

    /**
     * 从 request attribute 获取当前用户角色
     */
    public static String getCurrentRole(HttpServletRequest request) {
        return (String) request.getAttribute(ATTR_ROLE);
    }

    /**
     * 从 request attribute 获取当前 jti
     */
    public static String getCurrentJti(HttpServletRequest request) {
        return (String) request.getAttribute(ATTR_JTI);
    }
}
