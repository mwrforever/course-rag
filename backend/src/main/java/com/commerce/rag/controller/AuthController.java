package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.auth.AuthSessionService;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.auth.TokenService;
import com.commerce.rag.config.AuthProperties;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.LoginRequest;
import com.commerce.rag.controller.dto.LoginResponse;
import com.commerce.rag.controller.dto.RefreshRequest;
import com.commerce.rag.controller.dto.UserDTO;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.service.SysUserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 认证 Controller —— 登录/刷新/登出
 *
 * <p>端点：
 * <ul>
 *   <li>POST /api/v1/auth/login — 登录（用户名+密码 → AT+RT+cookie）</li>
 *   <li>POST /api/v1/auth/refresh — 刷新 AT（RT → 新 AT+RT）</li>
 *   <li>POST /api/v1/auth/logout — 登出（jti 入黑名单 + cookie 清除）</li>
 * </ul>
 *
 * <p>分层约束：login_record 的创建/刷新更新/登出吊销编排下沉至
 * {@link AuthSessionService}（controller → service → mapper），本类不直调 mapper。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final SysUserService sysUserService;
    private final TokenService tokenService;
    private final DeviceKickService deviceKickService;
    private final AuthProperties authProperties;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;

    public AuthController(
            SysUserService sysUserService,
            TokenService tokenService,
            DeviceKickService deviceKickService,
            AuthProperties authProperties,
            PasswordEncoder passwordEncoder,
            AuthSessionService authSessionService) {
        this.sysUserService = sysUserService;
        this.tokenService = tokenService;
        this.deviceKickService = deviceKickService;
        this.authProperties = authProperties;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
    }

    /**
     * 登录
     *
     * <p>流程：
     * <ol>
     *   <li>验证用户名 + 密码</li>
     *   <li>生成 AT（15min）+ RT（7d），各带独立 jti</li>
     *   <li>执行设备互踢（kickAndLogin）</li>
     *   <li>写入 sys_login_record（PG 审计层）</li>
     *   <li>设置 httpOnly cookie</li>
     *   <li>返回 LoginResponse</li>
     * </ol>
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        // 1. 查找用户
        SysUser user = sysUserService.findByUsername(request.username());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        // 2. 验证密码
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        // 3. 检查用户状态
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "用户已被禁用");
        }

        // 4. 生成 jti + Token
        String jtiAt = tokenService.generateJti();
        String jtiRt = tokenService.generateJti();
        String accessToken = tokenService.generateAccessToken(user.getId(), user.getRole(), jtiAt);
        String refreshToken = tokenService.generateRefreshToken(user.getId(), jtiRt);

        // 5. 设备类型（默认 WEB_DESKTOP）
        String deviceType = request.deviceType();
        if (deviceType == null || deviceType.isEmpty()) {
            deviceType = "WEB_DESKTOP";
        }

        // 6. 创建登录记录（下沉 AuthSessionService，controller 不直调 mapper、不接触 Entity）
        Long loginRecordId = authSessionService.createLoginRecord(
                user.getId(), jtiAt, jtiRt, deviceType, httpRequest.getHeader("User-Agent"), getClientIp(httpRequest));

        // 7. 设备互踢
        deviceKickService.kickAndLogin(user.getId(), deviceType, jtiAt, jtiRt, loginRecordId);

        // 8. 设置 httpOnly cookie
        setCookie(httpResponse, accessToken);

        log.info("用户登录: userId={}, username={}, deviceType={}", user.getId(), user.getUsername(), deviceType);

        return ApiResponse.ok(
                new LoginResponse(accessToken, refreshToken, user.getId(), user.getRole(), user.getDisplayName()));
    }

    /**
     * 刷新 Token（RT 一次性旋转）
     *
     * <p>流程：
     * <ol>
     *   <li>验证 RT 签名 + 过期</li>
     *   <li>原子检查并标记旧 RT 已使用（一次性旋转，P3 A11 Lua 单条脚本消除 TOCTOU）</li>
     *   <li>检查 RT 是否在黑名单中</li>
     *   <li>生成新 AT + RT</li>
     *   <li>更新 login_record 的 jti_at + jti_rt</li>
     *   <li>旧 jti 入黑名单</li>
     *   <li>返回新 Token 对</li>
     * </ol>
     */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(
            @Valid @RequestBody RefreshRequest request, HttpServletResponse httpResponse) {

        // 1. 验证 RT
        Claims claims;
        try {
            claims = tokenService.validateToken(request.refreshToken());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh Token 无效或已过期");
        }

        // 2. 检查类型
        if (!"REFRESH".equals(tokenService.extractTokenType(claims))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "令牌类型错误");
        }

        String oldJtiRt = tokenService.extractJti(claims);
        Long userId = tokenService.extractUserId(claims);

        // 3. 原子检查并标记 RT 已使用（P3 A11：Lua 单条脚本消除检查/置位 TOCTOU——并发 refresh 仅一个成功）
        if (!deviceKickService.markRefreshTokenUsedAtomic(oldJtiRt)) {
            // RT 复用 → 全量作废该用户所有 Token
            log.warn("RT 复用检测: userId={}, jtiRt={}", userId, oldJtiRt);
            deviceKickService.disableUser(userId, userId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh Token 已被使用，请重新登录");
        }

        // 4. 检查 RT 是否在黑名单中（保留）
        if (deviceKickService.isBlacklisted(oldJtiRt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh Token 已被吊销");
        }

        // 5.（原 markRefreshTokenUsed 已合并进步骤 3 的原子脚本）

        // 6. 获取用户最新信息（角色可能已变更）
        UserDTO userDto = sysUserService.findById(userId);
        String role = userDto.role();
        String displayName = userDto.displayName();

        // 7. 生成新 AT + RT
        String newJtiAt = tokenService.generateJti();
        String newJtiRt = tokenService.generateJti();
        String newAccessToken = tokenService.generateAccessToken(userId, role, newJtiAt);
        String newRefreshToken = tokenService.generateRefreshToken(userId, newJtiRt);

        // 8. 旧 RT jti 入黑名单
        deviceKickService.addToBlacklist(
                oldJtiRt,
                "REFRESH",
                userId,
                userId,
                "TOKEN_REUSE",
                LocalDateTime.ofInstant(claims.getExpiration().toInstant(), ZoneId.systemDefault()));

        // 9. 更新 login_record（下沉 AuthSessionService）
        authSessionService.updateLoginRecordOnRefresh(userId, oldJtiRt, newJtiAt, newJtiRt);

        // 10. 设置 cookie
        setCookie(httpResponse, newAccessToken);

        log.info("刷新 Token: userId={}, newJtiAt={}", userId, newJtiAt);

        return ApiResponse.ok(new LoginResponse(newAccessToken, newRefreshToken, userId, role, displayName));
    }

    /**
     * 登出
     *
     * <p>本端点被 AuthConfig 排除在 AuthInterceptor 外，request attribute 恒为 null，
     * 必须自行提取 AT 并宽松解析（允许 AT 已过期——RT 7d 仍有效，登出必须吊销 RT）。
     *
     * <p>流程：
     * <ol>
     *   <li>自行提取 AT（header Bearer 优先，cookie 兜底）</li>
     *   <li>宽松解析（签名校验、忽略过期），tokenType 必须为 ACCESS</li>
     *   <li>AT jti 入黑名单 + 查 login_record 取 jti_rt 一并入黑名单 + REVOKED</li>
     *   <li>清除 cookie（幂等：无论吊销是否成功都执行）</li>
     * </ol>
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String token = AuthInterceptor.extractToken(httpRequest, authProperties.cookieName());
        if (token != null && !token.isEmpty()) {
            try {
                Claims claims = tokenService.parseClaimsLoose(token);
                if ("ACCESS".equals(tokenService.extractTokenType(claims))) {
                    authSessionService.revokeOnLogout(
                            tokenService.extractUserId(claims), tokenService.extractJti(claims));
                }
            } catch (Exception e) {
                log.warn("登出 token 解析失败，仅清除 cookie", e);
            }
        }

        // 清除 cookie（幂等：无论吊销是否成功都执行）
        clearCookie(httpResponse);

        return ApiResponse.ok();
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    private void setCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(authProperties.cookieName(), token);
        cookie.setHttpOnly(true);
        // P2-11: Secure 标记配置化（生产 HTTPS 环境配 auth.cookie-secure=true，防止 AT 明文经 HTTP 传输被窃取）
        cookie.setSecure(authProperties.cookieSecure());
        cookie.setPath("/");
        if (authProperties.cookieDomain() != null
                && !authProperties.cookieDomain().isEmpty()) {
            cookie.setDomain(authProperties.cookieDomain());
        }
        cookie.setMaxAge(authProperties.accessTokenExpiry());
        response.addCookie(cookie);
    }

    private void clearCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(authProperties.cookieName(), "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        if (authProperties.cookieDomain() != null
                && !authProperties.cookieDomain().isEmpty()) {
            cookie.setDomain(authProperties.cookieDomain());
        }
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
