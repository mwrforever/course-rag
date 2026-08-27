package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.auth.AuthSessionService;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.auth.RegisterMailSender;
import com.commerce.rag.auth.TokenService;
import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.LoginRequest;
import com.commerce.rag.dto.LoginResponse;
import com.commerce.rag.dto.RefreshRequest;
import com.commerce.rag.dto.RegisterCodeRequest;
import com.commerce.rag.dto.RegisterRequest;
import com.commerce.rag.dto.UserDTO;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.AuthProperties;
import com.commerce.rag.record.AuthUserView;
import com.commerce.rag.record.RegisterResult;
import com.commerce.rag.service.IRegisterService;
import com.commerce.rag.service.ISysUserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 Controller —— 登录/学员注册/刷新/登出
 *
 * <p>端点：
 * <ul>
 *   <li>POST /api/v1/auth/login — 登录（用户名或邮箱 + 密码 → AT+RT+cookie）</li>
 *   <li>POST /api/v1/auth/register/code — 学员注册第一步：发送邮箱验证码（HTML 邮件，15 分钟有效）</li>
 *   <li>POST /api/v1/auth/register — 学员注册第二步：校验验证码并开户（成功即自动签发会话）</li>
 *   <li>POST /api/v1/auth/refresh — 刷新 AT（RT → 新 AT+RT）</li>
 *   <li>POST /api/v1/auth/logout — 登出（jti 入黑名单 + cookie 清除）</li>
 * </ul>
 *
 * <p>分层约束：login_record 的创建/刷新更新/登出吊销编排下沉至
 * {@link AuthSessionService}（controller → service → mapper），本类不直调 mapper；
 * 注册业务逻辑（验证码/查重/建户）下沉至 {@link IRegisterService}，本类只做会话签发编排。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final ISysUserService sysUserService;
    private final TokenService tokenService;
    private final DeviceKickService deviceKickService;
    private final AuthProperties authProperties;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;
    private final IRegisterService registerService;

    public AuthController(
            ISysUserService sysUserService,
            TokenService tokenService,
            DeviceKickService deviceKickService,
            AuthProperties authProperties,
            PasswordEncoder passwordEncoder,
            AuthSessionService authSessionService,
            IRegisterService registerService) {
        this.sysUserService = sysUserService;
        this.tokenService = tokenService;
        this.deviceKickService = deviceKickService;
        this.authProperties = authProperties;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
        this.registerService = registerService;
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

        // 1. 查找用户（认证视图，Entity 不出 service 边界）：优先用户名；形如邮箱时回退邮箱查询
        //    （V15 起学员自注册账户以邮箱绑定，登录表单字段「用户名或邮箱」双轨识别）
        String loginName = request.username();
        AuthUserView user = sysUserService.findAuthViewByUsername(loginName);
        if (user == null && loginName != null && loginName.contains("@")) {
            user = sysUserService.findAuthViewByEmail(loginName.trim().toLowerCase());
        }
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 2. 验证密码
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 3. 检查用户状态
        if (!"ACTIVE".equals(user.status())) {
            throw new BizException(ErrorCode.FORBIDDEN, "用户已被禁用");
        }

        // 4. 会话签发（双 Token / 互踢 / 审计 / cookie 收口——与注册成功路径共用同一实现防语义漂移）
        return ApiResponse.ok(issueSession(
                user.id(), user.role(), user.displayName(), request.deviceType(), httpRequest, httpResponse));
    }

    /**
     * 发送学员注册验证码（注册流程第一步）
     *
     * <p>业务规则（详见 RegisterServiceImpl/RegisterProperties）：同邮箱 60s 重发间隔（SET NX 原子抢占）、
     * 已注册邮箱拒绝（409）、HTML 邮件 15 分钟有效。业务失败语义：409 频控或已注册 / 503 SMTP 故障。</p>
     */
    @PostMapping("/register/code")
    public ApiResponse<Void> sendRegisterCode(
            @Valid @RequestBody RegisterCodeRequest request, HttpServletRequest httpRequest) {
        log.info("收到注册验证码发送请求: email={}", RegisterMailSender.maskEmail(request.email()));
        // 安全执法维度必须绑定攻击者无法伪造的标识：直连场景 getClientIp 信任的
        // X-Forwarded-For 可被任意请求轮换出无限独立配额桶（复用登录审计的信任模型
        // 会错配安全语义），故此处固定使用 TCP 对端地址 remoteAddr；反代环境经
        // Tomcat 前置剥离伪头后仍落到本进程可信值（审查 F1 方案 1）
        registerService.sendRegisterCode(request.email(), httpRequest.getRemoteAddr());
        return ApiResponse.ok();
    }

    /**
     * 完成学员注册（注册流程第二步）：校验验证码 → 创建学生账户 → 直接签发会话
     *
     * <p>响应契约与 /login 同构（LoginResponse + httpOnly cookie），前端可按「已登录」直接流转；
     * 业务失败语义：400 验证码过期/错误/锁定，409 并发抢注。</p>
     *
     * @param request      注册请求（email/code/password/nickname）
     * @param httpRequest  取 UA 与客户端 IP 写登录审计
     * @param httpResponse 写 AT cookie
     * @return 双 Token 与新用户视图
     */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        // 1. 业务段（service 层）：消费验证码 + 创建 STUDENT 账户
        RegisterResult result = registerService.register(request);

        // 2. 会话段（与登录共用收口）：双 Token / 互踢 / 登录审计 / cookie —— 自注册即自动登录
        LoginResponse response =
                issueSession(result.userId(), result.role(), result.displayName(), null, httpRequest, httpResponse);

        log.info("学员注册完成并自动登录: userId={}", result.userId());
        return ApiResponse.ok(response);
    }

    /**
     * 刷新 Token（RT 一次性旋转）
     *
     * <p>流程：
     * <ol>
     *   <li>验证 RT 签名 + 过期</li>
     *   <li>原子检查并标记旧 RT 已使用（一次性旋转，P3 A11 Lua 单条脚本消除 TOCTOU）</li>
     *   <li>检查 RT 是否在黑名单中</li>
     *   <li>校验用户状态（B1-2：非 ACTIVE 拒绝刷新并吊销全部活跃会话）</li>
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
            throw new BizException(ErrorCode.UNAUTHORIZED, "Refresh Token 无效或已过期");
        }

        // 2. 检查类型
        if (!"REFRESH".equals(tokenService.extractTokenType(claims))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌类型错误");
        }

        String oldJtiRt = tokenService.extractJti(claims);
        Long userId = tokenService.extractUserId(claims);

        // 3. 原子检查并标记 RT 已使用（P3 A11：Lua 单条脚本消除检查/置位 TOCTOU——并发 refresh 仅一个成功）
        if (!deviceKickService.markRefreshTokenUsedAtomic(oldJtiRt)) {
            // RT 复用 → 全量作废该用户所有 Token
            log.warn("RT 复用检测: userId={}, jtiRt={}", userId, oldJtiRt);
            deviceKickService.disableUser(userId, userId);
            throw new BizException(ErrorCode.UNAUTHORIZED, "Refresh Token 已被使用，请重新登录");
        }

        // 4. 检查 RT 是否在黑名单中（保留）
        if (deviceKickService.isBlacklisted(oldJtiRt)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "Refresh Token 已被吊销");
        }

        // 5.（原 markRefreshTokenUsed 已合并进步骤 3 的原子脚本）

        // 6. 获取用户最新信息（角色可能已变更）+ 用户状态校验（B1-2：对齐 login 的 ACTIVE 校验，
        //    禁用用户拒绝刷新并吊销全部活跃会话，防止凭未吊销 RT 无限续命）
        UserDTO userDto = sysUserService.findById(userId);
        authSessionService.assertUserActiveOnRefresh(userDto);
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

    /**
     * 统一签发登录会话 —— 登录与自注册成功两条入口的共用收口
     *
     * <p>流程（承接原登录步骤 4-8，语义零变更）：生成独立 jti 的 AT+RT → 创建登录审计记录（PG）→
     * 设备互踢 Lua 执法（互踢指针 + 新 jti 白名单）→ 设置 httpOnly AT cookie。</p>
     *
     * @param userId        用户 ID（注册路径来自 service 结果，登录路径来自认证视图）
     * @param role          用户角色
     * @param displayName   显示昵称
     * @param deviceTypeRaw 设备类型原文（可空 → 默认 WEB_DESKTOP；来自请求体）
     * @return 会话响应体（AT/RT 明文走 JSON 体，AT 另写 cookie 兜底 middleware 门卫）
     */
    private LoginResponse issueSession(
            Long userId,
            String role,
            String displayName,
            String deviceTypeRaw,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        // 双 Token 各持独立 jti：AT 黑名单粒度=单次登出会话，RT 粒度=一次性旋转链
        String jtiAt = tokenService.generateJti();
        String jtiRt = tokenService.generateJti();
        String accessToken = tokenService.generateAccessToken(userId, role, jtiAt);
        String refreshToken = tokenService.generateRefreshToken(userId, jtiRt);

        // 设备类型缺省 WEB_DESKTOP（历史契约：请求体可显式覆盖）
        String deviceType = (deviceTypeRaw == null || deviceTypeRaw.isEmpty()) ? "WEB_DESKTOP" : deviceTypeRaw;

        Long loginRecordId = authSessionService.createLoginRecord(
                userId, jtiAt, jtiRt, deviceType, httpRequest.getHeader("User-Agent"), getClientIp(httpRequest));
        deviceKickService.kickAndLogin(userId, deviceType, jtiAt, jtiRt, loginRecordId);
        setCookie(httpResponse, accessToken);

        log.info("会话已签发: userId={}, role={}, deviceType={}", userId, role, deviceType);
        return new LoginResponse(accessToken, refreshToken, userId, role, displayName);
    }

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
