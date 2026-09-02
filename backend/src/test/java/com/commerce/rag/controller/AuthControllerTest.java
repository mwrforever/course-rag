package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.auth.AuthSessionService;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.auth.TokenService;
import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.LoginRequest;
import com.commerce.rag.dto.LoginResponse;
import com.commerce.rag.dto.RefreshRequest;
import com.commerce.rag.dto.UserDTO;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.AuthProperties;
import com.commerce.rag.record.AuthUserView;
import com.commerce.rag.service.IRegisterService;
import com.commerce.rag.service.ISysUserService;
import com.commerce.rag.vo.MeVO;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * AuthController 单元测试 —— 登录/刷新/登出端点
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController 认证端点测试")
class AuthControllerTest {

    @Mock
    private ISysUserService sysUserService;

    @Mock
    private TokenService tokenService;

    @Mock
    private DeviceKickService deviceKickService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthSessionService authSessionService;

    @Mock
    private IRegisterService registerService;

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
                List.of("WEB_DESKTOP"),
                false);
        authController = new AuthController(
                sysUserService,
                tokenService,
                deviceKickService,
                authProperties,
                passwordEncoder,
                authSessionService,
                registerService);

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

        BizException ex = assertThrows(
                BizException.class,
                () -> authController.login(new LoginRequest("unknown", "password", null), httpRequest, httpResponse));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("login → 密码错误抛出 401")
    void login_wrongPassword_throws401() {
        AuthUserView user = new AuthUserView(1L, "testuser", "hashed-pass", "STUDENT", "测试用户", "ACTIVE");

        when(sysUserService.findAuthViewByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("wrongpass", "hashed-pass")).thenReturn(false);

        BizException ex = assertThrows(
                BizException.class,
                () -> authController.login(new LoginRequest("testuser", "wrongpass", null), httpRequest, httpResponse));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("login → 被禁用用户抛出 403")
    void login_disabledUser_throws403() {
        AuthUserView user = new AuthUserView(1L, "testuser", "hashed-pass", "STUDENT", "测试用户", "DISABLED");

        when(sysUserService.findAuthViewByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("password", "hashed-pass")).thenReturn(true);

        BizException ex = assertThrows(
                BizException.class,
                () -> authController.login(new LoginRequest("testuser", "password", null), httpRequest, httpResponse));
        assertEquals(403, ex.getCode());
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

    @Test
    @DisplayName("logout → cookie-secure=true 时清除指令同样携带 Secure 属性（BUG-10：与 setCookie 对称）")
    void logout_clearCookie_carriesSecureFlag_symmetricWithSetCookie() {
        // 生产 HTTPS 配置（auth.cookie-secure=true）：Secure cookie 的覆盖/删除须同样带
        // Secure 属性（RFC 6265bis），清除指令与写入不对称可能导致登出后浏览器残留 AT cookie
        AuthProperties secureProps = new AuthProperties(
                "test-secret-key-must-be-at-least-256-bits-long-for-hs256!!",
                900,
                604800L,
                "commerce_token",
                "localhost",
                true,
                List.of("WEB_DESKTOP"),
                false);
        AuthController secureController = new AuthController(
                sysUserService,
                tokenService,
                deviceKickService,
                secureProps,
                passwordEncoder,
                authSessionService,
                registerService);
        when(httpRequest.getHeader("Authorization")).thenReturn(null);
        when(httpRequest.getCookies()).thenReturn(null);

        secureController.logout(httpRequest, httpResponse);

        // 清除 cookie 契约：空值 + maxAge=0 + Secure 标记与写入路径对称
        ArgumentCaptor<Cookie> captor = ArgumentCaptor.forClass(Cookie.class);
        verify(httpResponse).addCookie(captor.capture());
        Cookie cleared = captor.getValue();
        assertEquals("", cleared.getValue());
        assertEquals(0, cleared.getMaxAge());
        assertTrue(cleared.getSecure(), "清除指令应与写入同样携带 Secure 属性（BUG-10）");
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
        // 步骤 8：旧 RT 入黑名单（BUG-13：正常旋转 reason 语义化写 ROTATED，
        // 与真实复用检测（disableUser 全量吊销写 USER_DISABLED）在审计层区分）
        verify(deviceKickService)
                .addToBlacklist(
                        eq("old-jti-rt"), eq("REFRESH"), eq(1L), eq(1L), eq("ROTATED"), any(LocalDateTime.class));
        // 步骤 9：登录记录更新下沉 AuthSessionService
        verify(authSessionService)
                .updateLoginRecordOnRefresh(eq(1L), eq("old-jti-rt"), eq("new-jti-at"), eq("new-jti-rt"));
        verify(httpResponse).addCookie(any(Cookie.class));
    }

    @Test
    @DisplayName("refresh → Redis 故障降级 fail-open：标记放行 + 黑名单降级查 PG 未命中 → 刷新成功（BUG-1 回归保护）")
    void refresh_redisFailure_failOpenSucceeds() {
        // 模拟 Redis 故障场景：markRefreshTokenUsedAtomic 内部捕获 Redis 异常降级放行（返回 true），
        // isBlacklisted 降级查 PG 无 TOKEN_REUSE 行（返回 false）——refresh 必须成功而非 401 自拦截
        Claims claims = mock(Claims.class);
        when(tokenService.validateToken("failover-rt")).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("REFRESH");
        when(tokenService.extractJti(claims)).thenReturn("old-jti-rt");
        when(tokenService.extractUserId(claims)).thenReturn(1L);
        when(deviceKickService.markRefreshTokenUsedAtomic("old-jti-rt")).thenReturn(true);
        when(deviceKickService.isBlacklisted("old-jti-rt")).thenReturn(false);
        when(sysUserService.findById(1L))
                .thenReturn(new UserDTO(1L, "testuser", "测试用户", "STUDENT", "ACTIVE", LocalDateTime.now()));
        when(tokenService.generateJti()).thenReturn("new-jti-at", "new-jti-rt");
        when(tokenService.generateAccessToken(1L, "STUDENT", "new-jti-at")).thenReturn("new-access-token");
        when(tokenService.generateRefreshToken(1L, "new-jti-rt")).thenReturn("new-refresh-token");
        when(claims.getExpiration()).thenReturn(Date.from(Instant.now().plusSeconds(604800L)));

        ApiResponse<LoginResponse> result = authController.refresh(new RefreshRequest("failover-rt"), httpResponse);

        // 刷新成功（不 401、不触发全量作废）
        assertNotNull(result);
        assertEquals("new-access-token", result.data().accessToken());
        verify(deviceKickService, never()).disableUser(anyLong(), anyLong());
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

        BizException ex = assertThrows(
                BizException.class, () -> authController.refresh(new RefreshRequest("reused-rt"), httpResponse));

        assertEquals(401, ex.getCode());
        // RT 复用 → 全量作废该用户所有 Token
        verify(deviceKickService).disableUser(1L, 1L);
        // 短路：黑名单检查不再执行、未生成新 Token
        verify(deviceKickService, never()).isBlacklisted(anyString());
        verify(tokenService, never()).generateJti();
    }

    @Test
    @DisplayName("refresh → 禁用用户：403 拒绝刷新并吊销，不签发新 Token（B1-2）")
    void refresh_disabledUser_throws403AndRevokes() {
        Claims claims = mock(Claims.class);
        when(tokenService.validateToken("disabled-rt")).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("REFRESH");
        when(tokenService.extractJti(claims)).thenReturn("old-jti-rt");
        when(tokenService.extractUserId(claims)).thenReturn(1L);
        when(deviceKickService.markRefreshTokenUsedAtomic("old-jti-rt")).thenReturn(true);
        when(deviceKickService.isBlacklisted("old-jti-rt")).thenReturn(false);
        // 用户已被禁用（原实现不校验状态，禁用用户可无限旋转续命）——
        // 状态校验+吊销编排下沉 AuthSessionService，此处 stub 其拒绝行为（403）
        when(sysUserService.findById(1L))
                .thenReturn(new UserDTO(1L, "testuser", "测试用户", "STUDENT", "DISABLED", LocalDateTime.now()));
        doThrow(new BizException(ErrorCode.FORBIDDEN, "用户已被禁用"))
                .when(authSessionService)
                .assertUserActiveOnRefresh(any(UserDTO.class));

        BizException ex = assertThrows(
                BizException.class, () -> authController.refresh(new RefreshRequest("disabled-rt"), httpResponse));

        assertEquals(403, ex.getCode());
        // 状态校验编排下沉 AuthSessionService（内部 disableUser 全量吊销活跃会话）
        verify(authSessionService).assertUserActiveOnRefresh(any(UserDTO.class));
        // 短路：不生成新 Token、不写黑名单、不更新 login_record
        verify(tokenService, never()).generateJti();
        verify(deviceKickService, never())
                .addToBlacklist(anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
        verify(authSessionService, never())
                .updateLoginRecordOnRefresh(anyLong(), anyString(), anyString(), anyString());
    }

    // ==================== me() 测试 ====================

    @Test
    @DisplayName("me → 拦截器已注入 userId 时返回三字段（userId/role/displayName，取库内最新值）")
    void me_withUserId_returnsIdentity() {
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        when(sysUserService.findById(1L))
                .thenReturn(new UserDTO(1L, "teacher01", "张老师", "TEACHER", "ACTIVE", LocalDateTime.now()));

        ApiResponse<MeVO> response = authController.me(httpRequest);

        assertNotNull(response);
        assertEquals(0, response.code());
        assertEquals(1L, response.data().userId());
        assertEquals("TEACHER", response.data().role());
        assertEquals("张老师", response.data().displayName());
    }

    @Test
    @DisplayName("me → 无认证上下文（未登录）返回 401")
    void me_withoutUserId_throws401() {
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> authController.me(httpRequest));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        // 短路：未查库（未登录请求不得触发数据库访问）
        verify(sysUserService, never()).findById(any());
    }

    @Test
    @DisplayName("me → 用户已禁用/删除：按未登录处理返回 401（不给禁用账户恢复身份，对齐 B1-2 语义）")
    void me_disabledUser_throws401() {
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        when(sysUserService.findById(1L))
                .thenReturn(new UserDTO(1L, "teacher01", "张老师", "TEACHER", "DISABLED", LocalDateTime.now()));

        BizException ex = assertThrows(BizException.class, () -> authController.me(httpRequest));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("me → 用户已不存在（findById 返回 null）：按未登录处理返回 401（覆盖 user == null 首析取项）")
    void me_userDeleted_throws401() {
        when(httpRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        when(sysUserService.findById(1L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> authController.me(httpRequest));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }
}
