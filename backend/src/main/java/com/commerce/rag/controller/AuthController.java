package com.commerce.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.auth.TokenService;
import com.commerce.rag.config.AuthProperties;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.LoginRequest;
import com.commerce.rag.controller.dto.LoginResponse;
import com.commerce.rag.controller.dto.RefreshRequest;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.mapper.SysLoginRecordMapper;
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
    private final SysLoginRecordMapper loginRecordMapper;

    public AuthController(
            SysUserService sysUserService,
            TokenService tokenService,
            DeviceKickService deviceKickService,
            AuthProperties authProperties,
            PasswordEncoder passwordEncoder,
            SysLoginRecordMapper loginRecordMapper) {
        this.sysUserService = sysUserService;
        this.tokenService = tokenService;
        this.deviceKickService = deviceKickService;
        this.authProperties = authProperties;
        this.passwordEncoder = passwordEncoder;
        this.loginRecordMapper = loginRecordMapper;
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

        // 6. 创建登录记录
        SysLoginRecord loginRecord = new SysLoginRecord();
        loginRecord.setUserId(user.getId());
        loginRecord.setJtiAt(jtiAt);
        loginRecord.setJtiRt(jtiRt);
        loginRecord.setDeviceType(deviceType);
        loginRecord.setDeviceInfo(httpRequest.getHeader("User-Agent"));
        loginRecord.setIpAddress(getClientIp(httpRequest));
        loginRecord.setExpiresAt(LocalDateTime.now().plusSeconds(authProperties.refreshTokenExpiry()));
        loginRecord.setStatus("ACTIVE");
        loginRecordMapper.insert(loginRecord);

        // 7. 设备互踢
        deviceKickService.kickAndLogin(user.getId(), deviceType, jtiAt, jtiRt, loginRecord.getId());

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
     *   <li>检查 RT 是否已被使用（一次性旋转）</li>
     *   <li>标记旧 RT 为已使用</li>
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

        // 3. 检查 RT 是否已被使用（一次性旋转）
        if (deviceKickService.isRefreshTokenUsed(oldJtiRt)) {
            // RT 复用 → 全量作废该用户所有 Token
            log.warn("RT 复用检测: userId={}, jtiRt={}", userId, oldJtiRt);
            deviceKickService.disableUser(userId, userId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh Token 已被使用，请重新登录");
        }

        // 4. 检查 RT 是否在黑名单中
        if (deviceKickService.isBlacklisted(oldJtiRt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh Token 已被吊销");
        }

        // 5. 标记旧 RT 为已使用
        deviceKickService.markRefreshTokenUsed(oldJtiRt);

        // 6. 获取用户最新信息（角色可能已变更）
        com.commerce.rag.controller.dto.UserDTO userDto = sysUserService.findById(userId);
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

        // 9. 更新 login_record
        updateLoginRecordOnRefresh(userId, oldJtiRt, newJtiAt, newJtiRt);

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
                    revokeTokensOnLogout(tokenService.extractUserId(claims), tokenService.extractJti(claims));
                }
            } catch (Exception e) {
                log.warn("登出 token 解析失败，仅清除 cookie: {}", e.getMessage());
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
        cookie.setSecure(false);
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

    private void updateLoginRecordOnRefresh(Long userId, String oldJtiRt, String newJtiAt, String newJtiRt) {
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysLoginRecord> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysLoginRecord>()
                            .eq(SysLoginRecord::getUserId, userId)
                            .eq(SysLoginRecord::getJtiRt, oldJtiRt)
                            .eq(SysLoginRecord::getStatus, "ACTIVE")
                            .set(SysLoginRecord::getJtiAt, newJtiAt)
                            .set(SysLoginRecord::getJtiRt, newJtiRt)
                            .set(SysLoginRecord::getUpdatedAt, LocalDateTime.now());
            loginRecordMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("刷新时更新 login_record 失败: userId={}, oldJtiRt={}", userId, oldJtiRt, e);
        }
    }

    /**
     * 登出吊销：AT jti + 同会话 RT jti 双入黑名单，login_record 置 REVOKED
     *
     * <p>RT 必须吊销：RT 有效期（7d）远长于 AT（15min），
     * 否则登出后旧 RT 仍可 refresh 出全新 Token 对。
     *
     * @param userId 用户 ID（来自 AT claims）
     * @param jtiAt  AT 的 jti（来自 AT claims）
     */
    private void revokeTokensOnLogout(Long userId, String jtiAt) {
        // 1. AT jti 入黑名单
        deviceKickService.addToBlacklist(
                jtiAt,
                "ACCESS",
                userId,
                userId,
                "MANUAL_REVOKE",
                LocalDateTime.now().plusSeconds(authProperties.accessTokenExpiry()));

        // 2. 查该会话 ACTIVE login_record 取 jti_rt（软删由 @TableLogic 自动过滤）
        SysLoginRecord record = loginRecordMapper.selectOne(new LambdaQueryWrapper<SysLoginRecord>()
                .eq(SysLoginRecord::getUserId, userId)
                .eq(SysLoginRecord::getJtiAt, jtiAt)
                .eq(SysLoginRecord::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (record != null && record.getJtiRt() != null && !record.getJtiRt().isEmpty()) {
            // RT 入黑名单，TTL 取 login_record 记录的真实过期时间
            deviceKickService.addToBlacklist(
                    record.getJtiRt(),
                    "REFRESH",
                    userId,
                    userId,
                    "MANUAL_REVOKE",
                    record.getExpiresAt() != null
                            ? record.getExpiresAt()
                            : LocalDateTime.now().plusSeconds(authProperties.refreshTokenExpiry()));
        }

        // 3. login_record → REVOKED（复用现有方法，幂等）
        revokeLoginRecord(userId, jtiAt);

        log.info("用户登出: userId={}, jtiAt={}", userId, jtiAt);
    }

    private void revokeLoginRecord(Long userId, String jtiAt) {
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysLoginRecord> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysLoginRecord>()
                            .eq(SysLoginRecord::getUserId, userId)
                            .eq(SysLoginRecord::getJtiAt, jtiAt)
                            .eq(SysLoginRecord::getStatus, "ACTIVE")
                            .set(SysLoginRecord::getStatus, "REVOKED")
                            .set(SysLoginRecord::getUpdatedAt, LocalDateTime.now());
            loginRecordMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("登出时更新 login_record 失败: userId={}, jtiAt={}", userId, jtiAt, e);
        }
    }
}
