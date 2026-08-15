# 基线清理修复（S2+S3）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 S2 认证安全三缺陷（登出不吊销 RT、设备互踢缺 PG 审计、THINKING_END 不触发）与 S3 课程 Tab 语义错乱，达成 S1 前置干净基线。

**Architecture:** 四个独立修复任务，安全优先顺序 S2-1→S2-2→S2-3→S3。每任务 TDD：先写失败测试 → 最小实现 → 单类测试通过 → 全量测试 → 提交。不改架构、不动范围外代码。

**Tech Stack:** Java 17 / Spring Boot 3.5.8 / jjwt 0.12（ExpiredJwtException 携带 Claims）/ Mockito + JUnit 5 / MyBatis-Plus LambdaQueryWrapper / Maven（Windows 用 `mvn.cmd`）

## Global Constraints

- 规格源：`docs/superpowers/specs/2026-08-14-baseline-cleanup-fixes.md`（用户已批准），实现不得偏离其锁定方案
- 注释/日志/文档全中文；UTF-8 无 BOM；文件末尾一个换行符
- 测试与实现同一次提交；因改动失效的旧测试直接删除，禁止保留过渡
- Entity 不出数据层；本次改动不涉及 MapStruct
- 工作区有大量与本任务无关的未提交改动，**git add 只加本任务明确列出的文件路径**，禁止 `git add -A` 或 `git add .`
- 测试命令：`cd backend && mvn.cmd test -Dtest=<测试类名>`；全量：`cd backend && mvn.cmd test`
- 提交信息格式参照仓库现有中文风格：`fix: <改动摘要>`

## File Structure

| 文件 | 任务 | 职责 |
|---|---|---|
| `backend/src/main/java/com/commerce/rag/auth/TokenService.java` | T1 修改 | 新增 `parseClaimsLoose`（签名校验、忽略过期） |
| `backend/src/main/java/com/commerce/rag/auth/AuthInterceptor.java` | T1 修改 | `extractToken` 公共静态化 + cookieName 参数化 |
| `backend/src/main/java/com/commerce/rag/controller/AuthController.java` | T1 修改 | logout 自行提取解析 AT，吊销 AT+RT+REVOKED |
| `backend/src/test/java/com/commerce/rag/auth/TokenServiceTest.java` | T1 新建 | parseClaimsLoose 三场景测试 |
| `backend/src/test/java/com/commerce/rag/auth/AuthInterceptorTest.java` | T1 修改 | 构造器同步 3 参 |
| `backend/src/test/java/com/commerce/rag/controller/AuthControllerTest.java` | T1 修改 | logout 测试重写（4 个新用例） |
| `backend/src/main/java/com/commerce/rag/auth/DeviceKickService.java` | T2 修改 | kickAndLogin 成功后 kickPgAudit |
| `backend/src/test/java/com/commerce/rag/auth/DeviceKickServiceTest.java` | T2 新建 | kick 审计三场景测试 |
| `backend/src/main/java/com/commerce/rag/stream/SseEventTransformer.java` | T3 修改 | RunState 双标志 + 补发 THINKING_END |
| `backend/src/test/java/com/commerce/rag/stream/SseEventTransformerTest.java` | T3 修改 | 新增 3 个 THINKING_END 用例 |
| `backend/src/main/java/com/commerce/rag/bot/tool/dto/CourseDetailResult.java` | T4 修改 | 四 Tab 字段重定义 |
| `backend/src/main/java/com/commerce/rag/bot/tool/CourseApiTool.java` | T4 修改 | 映射修正 |
| `backend/src/test/java/com/commerce/rag/bot/tool/CourseApiToolTest.java` | T4 修改 | 4 Tab 断言 |
| `docs/plans/2026-07-16-backend-design.md` | T4 修改 | §2.4 契约表格同步 |

---

### Task 1: S2-1 登出吊销 AT+RT（P1 安全路径）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/auth/TokenService.java`
- Modify: `backend/src/main/java/com/commerce/rag/auth/AuthInterceptor.java:29-117`
- Modify: `backend/src/main/java/com/commerce/rag/controller/AuthController.java:236-261`
- Test: `backend/src/test/java/com/commerce/rag/auth/TokenServiceTest.java`（新建）
- Test: `backend/src/test/java/com/commerce/rag/auth/AuthInterceptorTest.java`（构造器同步）
- Test: `backend/src/test/java/com/commerce/rag/controller/AuthControllerTest.java`（logout 测试重写）

**Interfaces:**
- Consumes: `TokenService.validateToken(String)`（现有）、`AuthProperties.cookieName()`（现有）、`SysLoginRecordMapper.selectOne(Wrapper)`（MyBatis-Plus BaseMapper 现有）、`DeviceKickService.addToBlacklist(String jti, String tokenType, Long userId, Long blacklistedBy, String reason, LocalDateTime expiresAt)`（现有）
- Produces: `TokenService.parseClaimsLoose(String token)` 返回 Claims（签名校验、忽略过期，抛 JwtException）；`AuthInterceptor.extractToken(HttpServletRequest request, String cookieName)` 公共静态方法返回 String 或 null

- [ ] **Step 1: 写失败测试 — TokenServiceTest（新建）**

新建 `backend/src/test/java/com/commerce/rag/auth/TokenServiceTest.java`：

```java
package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TokenService 单元测试 —— 签发/验证/宽松解析
 *
 * <p>使用真实密钥构造 TokenService，验证 JWT 签名与过期行为（非 mock）。
 *
 * @author commerce-rag
 */
@DisplayName("TokenService 签发与解析测试")
class TokenServiceTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-256-bits-long-for-hs256!!";

    private TokenService tokenService;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        AuthProperties props =
                new AuthProperties(SECRET, 900, 604800L, "commerce_token", "localhost", List.of("WEB_DESKTOP"));
        tokenService = new TokenService(props);
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /** 构造一个已过期的 ACCESS token（签名合法） */
    private String buildExpiredAccessToken(String jti) {
        return Jwts.builder()
                .subject("123")
                .claim("userId", 123L)
                .claim("role", "STUDENT")
                .claim("type", "ACCESS")
                .id(jti)
                .issuedAt(new Date(System.currentTimeMillis() - 7200_000L))
                .expiration(new Date(System.currentTimeMillis() - 3600_000L))
                .signWith(signingKey)
                .compact();
    }

    @Test
    @DisplayName("parseClaimsLoose → 有效 token 正常返回 Claims")
    void parseClaimsLoose_validToken_returnsClaims() {
        String token = tokenService.generateAccessToken(123L, "STUDENT", "jti-valid");

        Claims claims = tokenService.parseClaimsLoose(token);

        assertNotNull(claims);
        assertEquals("jti-valid", claims.getId());
        assertEquals("ACCESS", claims.get("type", String.class));
    }

    @Test
    @DisplayName("parseClaimsLoose → 过期 token 仍返回 Claims（登出吊销 RT 依赖）")
    void parseClaimsLoose_expiredToken_returnsClaims() {
        String token = buildExpiredAccessToken("jti-expired");

        Claims claims = tokenService.parseClaimsLoose(token);

        assertNotNull(claims);
        assertEquals("jti-expired", claims.getId());
        assertEquals(123L, tokenService.extractUserId(claims));
    }

    @Test
    @DisplayName("parseClaimsLoose → 签名错误 token 抛出 JwtException")
    void parseClaimsLoose_tamperedToken_throwsJwtException() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "another-secret-key-must-be-at-least-256-bits-long-for-hs256!!"
                        .getBytes(StandardCharsets.UTF_8));
        String tampered = Jwts.builder()
                .subject("123")
                .claim("type", "ACCESS")
                .id("jti-fake")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 900_000L))
                .signWith(wrongKey)
                .compact();

        assertThrows(JwtException.class, () -> tokenService.parseClaimsLoose(tampered));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=TokenServiceTest`
Expected: 编译失败 `cannot find symbol: method parseClaimsLoose`

- [ ] **Step 3: 实现 TokenService.parseClaimsLoose**

在 `TokenService.java` 的 `validateToken` 方法后新增（import 增加 `io.jsonwebtoken.ExpiredJwtException`）：

```java
    /**
     * 宽松解析 Token —— 校验签名，但不校验过期时间
     *
     * <p>登出场景专用：AT 过期后（默认 15min 内）RT（7d）仍有效，
     * 登出必须仍能定位 login_record 吊销 RT。签名始终校验，
     * 伪造 token 抛 SignatureException（JwtException 子类）。
     *
     * @param token JWT 字符串
     * @return Claims（含 userId、jti、type 等）
     * @throws JwtException Token 无效（签名/格式错误）时抛出
     */
    public Claims parseClaimsLoose(String token) throws JwtException {
        try {
            return validateToken(token);
        } catch (ExpiredJwtException e) {
            // 过期 token 允许解析：ExpiredJwtException 携带已解析的 Claims
            return e.getClaims();
        }
    }
```

- [ ] **Step 4: 运行 TokenServiceTest 验证通过**

Run: `cd backend && mvn.cmd test -Dtest=TokenServiceTest`
Expected: 3 个测试 PASS

- [ ] **Step 5: 修改 AuthInterceptor —— extractToken 公共静态化 + cookieName 参数化**

`AuthInterceptor.java` 改动三处：

(1) import 增加 `com.commerce.rag.config.AuthProperties`；(2) 字段与构造器：

```java
    private final TokenService tokenService;
    private final DeviceKickService deviceKickService;
    private final AuthProperties authProperties;

    public AuthInterceptor(
            TokenService tokenService, DeviceKickService deviceKickService, AuthProperties authProperties) {
        this.tokenService = tokenService;
        this.deviceKickService = deviceKickService;
        this.authProperties = authProperties;
    }
```

(3) preHandle 第 1 步调用改为 `String token = extractToken(request, authProperties.cookieName());`，原私有方法替换为（删除硬编码 cookie 名）：

```java
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
```

同步 `AuthInterceptorTest.java`：`@Mock private AuthProperties authProperties;`，setUp 中 `lenient().when(authProperties.cookieName()).thenReturn("commerce_token");` 与 `authInterceptor = new AuthInterceptor(tokenService, deviceKickService, authProperties);`。

- [ ] **Step 6: 重写 AuthController.logout + 新增私有吊销方法**

`AuthController.java`：import 增加 `io.jsonwebtoken.Claims`、`com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper`。将 logout 方法（原 :236-261）整体替换为：

```java
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
```

在 `revokeLoginRecord` 方法前新增：

```java
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
        SysLoginRecord record = loginRecordMapper.selectOne(
                new LambdaQueryWrapper<SysLoginRecord>()
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
```

- [ ] **Step 7: 重写 AuthControllerTest 的 logout 测试**

删除原 `logout_addsToBlacklistAndClearsCookie` 与 `logout_noUserId_returnsSuccess` 两个基于 attribute 的测试（因本次改动失效，直接删除），替换为以下 4 个。import 增加 `io.jsonwebtoken.Claims`、`org.mockito.ArgumentCaptor`（如需要）与 `java.util.Objects` 不必要，保持最小。

```java
    // ==================== logout() 测试 ====================

    @Test
    @DisplayName("logout → Bearer AT 解析成功：AT+RT 双入黑名单 + login_record REVOKED + 清 cookie")
    void logout_withBearerToken_revokesAtAndRt() {
        Claims claims = mock(Claims.class);
        when(httpRequest.getHeader("Authorization")).thenReturn("Bearer access-token");
        when(tokenService.parseClaimsLoose("access-token")).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("ACCESS");
        when(tokenService.extractUserId(claims)).thenReturn(123L);
        when(tokenService.extractJti(claims)).thenReturn("jti-at");

        SysLoginRecord record = new SysLoginRecord();
        record.setId(10L);
        record.setUserId(123L);
        record.setJtiAt("jti-at");
        record.setJtiRt("jti-rt");
        record.setStatus("ACTIVE");
        record.setExpiresAt(LocalDateTime.now().plusDays(7));
        when(loginRecordMapper.selectOne(any())).thenReturn(record);

        ApiResponse<Void> result = authController.logout(httpRequest, httpResponse);

        assertNotNull(result);
        assertEquals(0, result.code());
        // AT 吊销
        verify(deviceKickService)
                .addToBlacklist(eq("jti-at"), eq("ACCESS"), eq(123L), eq(123L), eq("MANUAL_REVOKE"), any(LocalDateTime.class));
        // RT 吊销（同会话 login_record 的 jti_rt）
        verify(deviceKickService)
                .addToBlacklist(eq("jti-rt"), eq("REFRESH"), eq(123L), eq(123L), eq("MANUAL_REVOKE"), any(LocalDateTime.class));
        // login_record → REVOKED
        verify(loginRecordMapper).update(isNull(), any());
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
        verify(deviceKickService, never())
                .addToBlacklist(anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
        verify(loginRecordMapper, never()).update(any(), any());
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
        verify(deviceKickService, never())
                .addToBlacklist(anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
        verify(httpResponse).addCookie(any(Cookie.class));
    }

    @Test
    @DisplayName("logout → token 解析异常：降级仅清 cookie，不抛异常")
    void logout_parseFailure_onlyClearsCookie() {
        when(httpRequest.getHeader("Authorization")).thenReturn("Bearer broken-token");
        when(tokenService.parseClaimsLoose("broken-token")).thenThrow(new JwtException("签名无效"));

        ApiResponse<Void> result =
                assertDoesNotThrow(() -> authController.logout(httpRequest, httpResponse));

        assertNotNull(result);
        assertEquals(0, result.code());
        verify(deviceKickService, never())
                .addToBlacklist(anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
        verify(httpResponse).addCookie(any(Cookie.class));
    }
```

（import 增加 `io.jsonwebtoken.JwtException`；`assertDoesNotThrow` 已在 `org.junit.jupiter.api.Assertions.*` 静态导入中。）

- [ ] **Step 8: 运行相关测试**

Run: `cd backend && mvn.cmd test -Dtest=TokenServiceTest,AuthInterceptorTest,AuthControllerTest`
Expected: 全部 PASS（AuthControllerTest 旧 logout 测试已删，login 测试不受影响）

- [ ] **Step 9: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS

- [ ] **Step 10: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/java/com/commerce/rag/auth/TokenService.java backend/src/main/java/com/commerce/rag/auth/AuthInterceptor.java backend/src/main/java/com/commerce/rag/controller/AuthController.java backend/src/test/java/com/commerce/rag/auth/TokenServiceTest.java backend/src/test/java/com/commerce/rag/auth/AuthInterceptorTest.java backend/src/test/java/com/commerce/rag/controller/AuthControllerTest.java
git commit -m "fix: S2-1 登出吊销 AT+RT（宽松解析 + login_record 取 jti_rt 双入黑名单）"
```

---

### Task 2: S2-2 设备互踢 Redis 成功路径补 PG 审计

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/auth/DeviceKickService.java:117-135`
- Test: `backend/src/test/java/com/commerce/rag/auth/DeviceKickServiceTest.java`（新建）

**Interfaces:**
- Consumes: `DeviceKickService.addToBlacklistPg(String jti, String tokenType, Long userId, Long blacklistedBy, String reason)`（现有私有方法）、`KickResult(boolean kicked, String oldJtiAt, String oldJtiRt)`（现有 record）
- Produces: 私有方法 `kickPgAudit(Long userId, KickResult result)`（无返回值，异常内部吞掉）

- [ ] **Step 1: 写失败测试 — DeviceKickServiceTest（新建）**

新建 `backend/src/test/java/com/commerce/rag/auth/DeviceKickServiceTest.java`：

```java
package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.config.AuthProperties;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.mapper.SysLoginRecordMapper;
import com.commerce.rag.mapper.SysTokenBlacklistMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DeviceKickService 单元测试 —— 设备互踢 Redis 成功路径的 PG 审计落盘
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceKickService 设备互踢审计测试")
class DeviceKickServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private TokenService tokenService;

    @Mock
    private SysLoginRecordMapper loginRecordMapper;

    @Mock
    private SysTokenBlacklistMapper tokenBlacklistMapper;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AuthProperties authProperties;
    private DeviceKickService service;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties(
                "test-secret-key-must-be-at-least-256-bits-long-for-hs256!!",
                900,
                604800L,
                "commerce_token",
                "localhost",
                List.of("WEB_DESKTOP"));
        service = new DeviceKickService(
                redisTemplate, tokenService, authProperties, loginRecordMapper, tokenBlacklistMapper, jdbcTemplate);
    }

    @Test
    @DisplayName("kickAndLogin → Lua 踢出旧设备后，PG 审计落盘（REVOKED + 双 jti 黑名单）")
    void kickAndLogin_kickedTrue_writesPgAudit() {
        // 旧设备存在（触发 TTL 计算分支）
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("old-at|old-rt|1");
        // Lua 返回：踢出成功
        when(redisTemplate.execute(
                        any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("{\"kicked\":true,\"old_jti_at\":\"old-at\",\"old_jti_rt\":\"old-rt\"}");

        DeviceKickService.KickResult result =
                service.kickAndLogin(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L);

        assertTrue(result.kicked());
        // 旧 login_record 置 REVOKED
        verify(jdbcTemplate)
                .update(contains("sys_login_record SET status = 'REVOKED'"), eq(1L), eq("old-at"));
        // 旧 AT/RT 双写 PG 黑名单
        verify(tokenBlacklistMapper, times(2)).insert(any(SysTokenBlacklist.class));
    }

    @Test
    @DisplayName("kickAndLogin → 无旧设备（kicked=false），不做 PG 审计")
    void kickAndLogin_notKicked_noPgAudit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.execute(
                        any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("{\"kicked\":false,\"old_jti_at\":\"\",\"old_jti_rt\":\"\"}");

        DeviceKickService.KickResult result =
                service.kickAndLogin(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L);

        assertFalse(result.kicked());
        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(tokenBlacklistMapper);
    }

    @Test
    @DisplayName("kickAndLogin → PG 审计异常不影响登录主流程（返回正常结果）")
    void kickAndLogin_pgAuditFailure_stillReturnsResult() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("old-at|old-rt|1");
        when(redisTemplate.execute(
                        any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("{\"kicked\":true,\"old_jti_at\":\"old-at\",\"old_jti_rt\":\"old-rt\"}");
        // PG 审计落盘时 DB 故障
        doThrow(new RuntimeException("DB 故障"))
                .when(jdbcTemplate)
                .update(anyString(), any(), any());

        DeviceKickService.KickResult result =
                assertDoesNotThrow(() -> service.kickAndLogin(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L));

        assertTrue(result.kicked());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=DeviceKickServiceTest`
Expected: `kickAndLogin_kickedTrue_writesPgAudit` 与 `kickAndLogin_pgAuditFailure_stillReturnsResult` 失败（verify(jdbcTemplate).update 不被满足 / doThrow 无匹配调用）；`kickAndLogin_notKicked_noPgAudit` 通过

- [ ] **Step 3: 实现 kickPgAudit 并接入 kickAndLogin**

`DeviceKickService.java` 的 `kickAndLogin` 中，将

```java
            log.info("设备互踢 Lua 执行: userId={}, deviceType={}, result={}", userId, deviceType, result);
            return parseKickResult(result);
```

替换为：

```java
            log.info("设备互踢 Lua 执行: userId={}, deviceType={}, result={}", userId, deviceType, result);
            KickResult kickResult = parseKickResult(result);
            // Lua 成功且确实踢出旧设备 → PG 审计落盘（Redis 执法层 + PG 审计层双写）
            if (kickResult.kicked()) {
                kickPgAudit(userId, kickResult);
            }
            return kickResult;
```

在 `disableUserPgAudit` 方法后新增：

```java
    /**
     * PG 审计落盘：设备互踢（Redis Lua 成功路径）
     *
     * <p>与 {@link #disableUserPgAudit} 同款逻辑：旧 login_record 置 REVOKED +
     * 旧 jti 双写 PG 黑名单。审计失败仅告警，不影响登录主流程（Redis 执法层已生效）。
     *
     * @param userId 用户 ID（被踢设备的用户）
     * @param result Lua 返回的踢出结果（kicked=true 时调用）
     */
    private void kickPgAudit(Long userId, KickResult result) {
        try {
            // 1. 旧 login_record → REVOKED（条件 status='ACTIVE'，幂等）
            jdbcTemplate.update(
                    "UPDATE sys_login_record SET status = 'REVOKED', updated_at = now() "
                            + "WHERE user_id = ? AND jti_at = ? AND status = 'ACTIVE'",
                    userId,
                    result.oldJtiAt());

            // 2. 旧 jti 双写 PG 黑名单（addToBlacklistPg 已忽略唯一索引冲突）
            if (!result.oldJtiAt().isEmpty()) {
                addToBlacklistPg(result.oldJtiAt(), "ACCESS", userId, null, "DEVICE_KICKED");
            }
            if (!result.oldJtiRt().isEmpty()) {
                addToBlacklistPg(result.oldJtiRt(), "REFRESH", userId, null, "DEVICE_KICKED");
            }

            log.info("设备互踢 PG 审计落盘: userId={}, oldJtiAt={}", userId, result.oldJtiAt());
        } catch (Exception e) {
            log.warn("设备互踢 PG 审计失败（不影响登录主流程）: userId={}", userId, e);
        }
    }
```

- [ ] **Step 4: 运行 DeviceKickServiceTest 验证通过**

Run: `cd backend && mvn.cmd test -Dtest=DeviceKickServiceTest`
Expected: 3 个测试 PASS

- [ ] **Step 5: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS

- [ ] **Step 6: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/java/com/commerce/rag/auth/DeviceKickService.java backend/src/test/java/com/commerce/rag/auth/DeviceKickServiceTest.java
git commit -m "fix: S2-2 设备互踢 Redis 成功路径补 PG 审计落盘"
```

---

### Task 3: S2-3 流式 thinking→text 切换补发 THINKING_END

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/stream/SseEventTransformer.java:95-116,136-171,334-373`
- Test: `backend/src/test/java/com/commerce/rag/stream/SseEventTransformerTest.java`（新增 3 个用例）

**Interfaces:**
- Consumes: `RunState.create(String runId, String sessionId, String model)`（现有工厂，签名不变——现有测试全部走工厂，record 构造器新增字段不影响）
- Produces: `RunState` 新增方法 `markThinkingSent()` / `isThinkingSent()` / `markThinkingEndSent()`（均返回 boolean，CAS 语义）

- [ ] **Step 1: 写失败测试 — SseEventTransformerTest 新增 3 个用例**

在 `SseEventTransformerTest.java` 的 `AGENT_MODEL_STREAMING` 区块末尾（`transform_modelStreamingNullMessage_returnsEmptyList` 之后）新增：

```java
    @Test
    @DisplayName("thinking→text 切换：补发 THINKING_END，且位于首条 DELTA 之前")
    void transform_thinkingThenText_emitsThinkingEndBeforeDelta() {
        // Given: 先推 thinking chunk，再推 text chunk（同一 runState）
        AssistantMessage thinkingMsg = mock(AssistantMessage.class);
        StreamingOutput<?> thinkingOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");
        when(thinkingOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(thinkingOutput.message()).thenReturn(thinkingMsg);
        when(thinkingMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "思考中"));

        AssistantMessage textMsg = mock(AssistantMessage.class);
        StreamingOutput<?> textOutput = mock(StreamingOutput.class);
        when(textOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(textOutput.message()).thenReturn(textMsg);
        when(textMsg.getMetadata()).thenReturn(Map.of());
        when(textMsg.getText()).thenReturn("答案是42");

        // When
        List<SseEvent> first = transformer.transform(thinkingOutput, runState);
        List<SseEvent> second = transformer.transform(textOutput, runState);

        // Then: THINKING → THINKING_END → DELTA
        assertEquals(1, first.size());
        assertEquals(SseEventType.THINKING, first.get(0).type());
        assertEquals(2, second.size());
        assertEquals(SseEventType.THINKING_END, second.get(0).type());
        assertEquals("{}", second.get(0).payload());
        assertEquals(SseEventType.DELTA, second.get(1).type());
    }

    @Test
    @DisplayName("纯文本流（无 thinking）：text 与 FINISHED 均不产生 THINKING_END")
    void transform_textOnly_noThinkingEnd() {
        // Given: 先推 text chunk（无 thinking 历史），再推 FINISHED（无 reasoning）
        AssistantMessage textMsg = mock(AssistantMessage.class);
        StreamingOutput<?> textOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");
        when(textOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(textOutput.message()).thenReturn(textMsg);
        when(textMsg.getMetadata()).thenReturn(Map.of());
        when(textMsg.getText()).thenReturn("直接回答");

        AssistantMessage finMsg = mock(AssistantMessage.class);
        StreamingOutput<?> finOutput = mock(StreamingOutput.class);
        when(finOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_FINISHED);
        when(finOutput.message()).thenReturn(finMsg);
        when(finMsg.getMetadata()).thenReturn(Map.of());
        lenient().when(finMsg.hasToolCalls()).thenReturn(false);

        // When
        List<SseEvent> streamingEvents = transformer.transform(textOutput, runState);
        List<SseEvent> finishedEvents = transformer.transform(finOutput, runState);

        // Then: 无 THINKING_END
        assertEquals(1, streamingEvents.size());
        assertEquals(SseEventType.DELTA, streamingEvents.get(0).type());
        assertTrue(finishedEvents.stream().noneMatch(e -> e.type() == SseEventType.THINKING_END));
    }

    @Test
    @DisplayName("流式已补发 THINKING_END 后，FINISHED 带 reasoning 不重复发")
    void transform_finishedAfterStreamingThinkingEnd_noDuplicate() {
        // Given: thinking → text（已补发 THINKING_END）→ FINISHED 仍带 reasoningContent
        AssistantMessage thinkingMsg = mock(AssistantMessage.class);
        StreamingOutput<?> thinkingOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");
        when(thinkingOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(thinkingOutput.message()).thenReturn(thinkingMsg);
        when(thinkingMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "思考中"));

        AssistantMessage textMsg = mock(AssistantMessage.class);
        StreamingOutput<?> textOutput = mock(StreamingOutput.class);
        when(textOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(textOutput.message()).thenReturn(textMsg);
        when(textMsg.getMetadata()).thenReturn(Map.of());
        when(textMsg.getText()).thenReturn("答案");

        AssistantMessage finMsg = mock(AssistantMessage.class);
        StreamingOutput<?> finOutput = mock(StreamingOutput.class);
        when(finOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_FINISHED);
        when(finOutput.message()).thenReturn(finMsg);
        when(finMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "思考完毕"));
        lenient().when(finMsg.hasToolCalls()).thenReturn(false);

        // When
        transformer.transform(thinkingOutput, runState);
        transformer.transform(textOutput, runState);
        List<SseEvent> finishedEvents = transformer.transform(finOutput, runState);

        // Then: FINISHED 不再重复发 THINKING_END（无事件）
        assertTrue(finishedEvents.isEmpty());
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=SseEventTransformerTest`
Expected: 新增 3 个用例失败——`thinkingThenText` 期望 2 事件实得 1；`textOnly` 期望无 THINKING_END 的 FINISHED 用例中 FINISHED 无 reasoning 本应通过，但依赖新逻辑后行为一致（可能通过）；`finishedAfterStreamingThinkingEnd` 期望空实得 1 个 THINKING_END。至少 2 个失败，且编译通过（RunState 旧构造参数数不变）

- [ ] **Step 3: 实现 RunState 双标志 + 转换逻辑**

`SseEventTransformer.java` 三处修改：

(1) `RunState` record 定义（:343-344）与工厂（:370-372）替换为：

```java
    public record RunState(
            String runId,
            String sessionId,
            String model,
            AtomicLong seqCounter,
            AtomicBoolean deltaSent,
            AtomicBoolean thinkingSent,
            AtomicBoolean thinkingEndSent) {

        /**
         * 递增并返回下一个 SSE 事件序号。
         */
        public long nextSeq() {
            return seqCounter.incrementAndGet();
        }

        /**
         * 标记本 run 已发送过 DELTA 事件，返回是否首次标记成功。
         */
        public boolean markDeltaSent() {
            return deltaSent.compareAndSet(false, true);
        }

        /**
         * 是否已发送过 DELTA 事件。
         */
        public boolean isDeltaSent() {
            return deltaSent.get();
        }

        /**
         * 标记本 run 已发送过 THINKING 事件（thinkingEndSent 补发的前置条件）。
         */
        public boolean markThinkingSent() {
            return thinkingSent.compareAndSet(false, true);
        }

        /**
         * 是否已发送过 THINKING 事件。
         */
        public boolean isThinkingSent() {
            return thinkingSent.get();
        }

        /**
         * 标记本 run 已发送过 THINKING_END 事件，返回是否首次标记成功（CAS 去重）。
         */
        public boolean markThinkingEndSent() {
            return thinkingEndSent.compareAndSet(false, true);
        }

        /**
         * 工厂方法：创建初始 seqId=0、三标志均为 false 的 RunState。
         */
        public static RunState create(String runId, String sessionId, String model) {
            return new RunState(
                    runId,
                    sessionId,
                    model,
                    new AtomicLong(0),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false));
        }
    }
```

（record 参数注释块同步更新：`@param deltaSent` / `@param thinkingSent` / `@param thinkingEndSent` 各一行说明。）

(2) `transformModelStreaming`（:95-116）替换为：

```java
    private List<SseEvent> transformModelStreaming(StreamingOutput<?> chunk, RunState runState) {
        Message message = chunk.message();
        if (message == null) {
            return List.of();
        }

        // 1. 检查 reasoningContent → thinking 事件
        String reasoning = extractReasoningContent(message);
        if (reasoning != null && !reasoning.isEmpty()) {
            runState.markThinkingSent(); // 标记已发 THINKING，text 阶段补 THINKING_END 的前置条件
            return List.of(makeEvent(SseEventType.THINKING, runState, Map.of("delta", reasoning)));
        }

        // 2. 取文本 delta → 先补 THINKING_END（若有思考且未发），再发 DELTA
        String text = message.getText();
        if (text != null && !text.isEmpty()) {
            List<SseEvent> events = new ArrayList<>();
            // qwen 思考模型 thinking/text 两阶段互斥：首条 text 即思考结束信号，
            // 必须补发 THINKING_END 再发 DELTA，保证前端退出"思考中"状态
            if (runState.isThinkingSent() && runState.markThinkingEndSent()) {
                events.add(makeEvent(SseEventType.THINKING_END, runState, Map.of()));
            }
            runState.markDeltaSent(); // 标记本 run 已发 DELTA，FINISHED 时不再补发
            events.add(makeEvent(SseEventType.DELTA, runState, Map.of("text", text)));
            return events;
        }

        // 3. 空 delta（仅有 toolCalls 的中间 chunk 等），跳过
        return List.of();
    }
```

(3) `transformModelFinished`（:136-171）的 THINKING_END 逻辑（原步骤 2）调整为放在 DELTA 补发（原步骤 1）之前，且 CAS 去重：

```java
    private List<SseEvent> transformModelFinished(StreamingOutput<?> chunk, RunState runState) {
        List<SseEvent> events = new ArrayList<>();
        Message message = chunk.message();

        if (message == null) {
            return events;
        }

        // 1. THINKING_END：FINISHED 累积消息仍带 reasoningContent → 补发一次
        //    （流式阶段 thinking→text 已补发过的场景由 CAS 去重，不会重复）
        String reasoning = extractReasoningContent(message);
        if (reasoning != null && !reasoning.isEmpty()) {
            runState.markThinkingSent();
            if (runState.markThinkingEndSent()) {
                events.add(makeEvent(SseEventType.THINKING_END, runState, Map.of()));
            }
        }

        // 2. 补发 DELTA：若本 run 未发过 DELTA（无流式 chunk），FINISHED 时补发完整 text（§3.7）
        if (!runState.isDeltaSent()) {
            String fullText = message.getText();
            if (fullText != null && !fullText.isEmpty()) {
                events.add(makeEvent(SseEventType.DELTA, runState, Map.of("text", fullText)));
                runState.markDeltaSent();
            }
        }

        // 3. TOOL_CALL：提取工具调用列表
        if (message instanceof AssistantMessage am && am.hasToolCalls()) {
            for (AssistantMessage.ToolCall toolCall : am.getToolCalls()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolCallId", toolCall.id());
                payload.put("toolName", toolCall.name());
                payload.put("input", toolCall.arguments());
                events.add(makeEvent(SseEventType.TOOL_CALL, runState, payload));
            }
        }

        return events;
    }
```

类注释（:27）中「AGENT_MODEL_FINISHED → …+ THINKING_END（如有思考内容）」保持有效，无需改。

- [ ] **Step 4: 运行 SseEventTransformerTest 验证通过**

Run: `cd backend && mvn.cmd test -Dtest=SseEventTransformerTest`
Expected: 全部 PASS（原有 16 个 + 新增 3 个；原有 FINISHED 相关用例的 THINKING_END 顺序断言不受影响——`transform_modelFinishedWithReasoningAndToolCalls_returnsMultipleEvents` 仍满足 THINKING_END 在 TOOL_CALL 前）

- [ ] **Step 5: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS

- [ ] **Step 6: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/java/com/commerce/rag/stream/SseEventTransformer.java backend/src/test/java/com/commerce/rag/stream/SseEventTransformerTest.java
git commit -m "fix: S2-3 流式 thinking→text 切换补发 THINKING_END（双标志 CAS 去重）"
```

---

### Task 4: S3 CourseApiTool 4 Tab 与 DTO 字段语义对齐

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/bot/tool/dto/CourseDetailResult.java`
- Modify: `backend/src/main/java/com/commerce/rag/bot/tool/CourseApiTool.java:91-112,219-233`
- Test: `backend/src/test/java/com/commerce/rag/bot/tool/CourseApiToolTest.java`
- Modify: `docs/plans/2026-07-16-backend-design.md`（§2.4 契约表格行）

**Interfaces:**
- Consumes: `CourseQueryService.findContentsByCourseId(String)`（现有）、`CourseContent.getContentType()`（现有，值域 intro/syllabus/instructor/faq）
- Produces: `CourseDetailResult(String summary 内嵌, ScheduleInfo, InstructorInfo, String introContent, String syllabusContent, String instructorContent, String faqContent, String enrollmentUrl, List<String> tags)`——record 构造器参数顺序即此

- [ ] **Step 1: 写失败测试 — CourseApiToolTest 修改 2 个用例**

`CourseApiToolTest.java` 中替换 `queryCourseDetail_aggregatesAllData` 与 `queryCourseDetail_notFound_returnsEmptyDetail`：

```java
    @Test
    @DisplayName("queryCourseDetail — 4 Tab 内容与 DTO 字段一一对应（intro/syllabus/instructor/faq）")
    void queryCourseDetail_aggregatesAllData() {
        // Given: 4 个 Tab fixture（db-schema 权威枚举：intro / syllabus / instructor / faq）
        CourseInfo info = mockCourseInfo();
        CourseContent introContent = mockContent("intro", "本课程涵盖Java核心知识");
        CourseContent syllabusContent = mockContent("syllabus", "第一章 Java基础\n第二章 面向对象");
        CourseContent instructorTab = mockContent("instructor", "张老师：10年大型系统架构经验");
        CourseContent faqTab = mockContent("faq", "Q1：需要什么基础？\nA1：无基础要求");
        CourseSchedule schedule = mockSchedule();

        when(courseQueryService.findCourseById("123")).thenReturn(info);
        when(courseQueryService.findContentsByCourseId("123"))
                .thenReturn(List.of(introContent, syllabusContent, instructorTab, faqTab));
        when(courseQueryService.findNextSchedule("123")).thenReturn(schedule);

        // When
        CourseDetailResult result = tool.queryCourseDetail("123");

        // Then: 每个 DTO 字段内容 = 对应 Tab 内容（不再有 prerequisites/targetAudience 错位）
        assertNotNull(result);
        assertEquals("123", result.summary().courseId());
        assertEquals("Java入门到精通", result.summary().title());
        assertEquals("本课程涵盖Java核心知识", result.introContent());
        assertEquals("第一章 Java基础\n第二章 面向对象", result.syllabusContent());
        assertEquals("张老师：10年大型系统架构经验", result.instructorContent());
        assertEquals("Q1：需要什么基础？\nA1：无基础要求", result.faqContent());
        assertEquals("12周", result.schedule().duration());
        assertNotNull(result.enrollmentUrl());
        assertEquals("https://enroll.example.com/course/123", result.enrollmentUrl());
        assertEquals("张老师", result.instructor().name());
        assertNotNull(result.schedule().nextStartDate());
    }

    @Test
    @DisplayName("queryCourseDetail 课程不存在 — 返回空详情")
    void queryCourseDetail_notFound_returnsEmptyDetail() {
        when(courseQueryService.findCourseById("999")).thenReturn(null);

        CourseDetailResult result = tool.queryCourseDetail("999");

        assertNotNull(result);
        assertEquals("999", result.summary().courseId());
        assertTrue(result.introContent().isEmpty());
        assertTrue(result.syllabusContent().isEmpty());
        assertTrue(result.instructorContent().isEmpty());
        assertTrue(result.faqContent().isEmpty());
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=CourseApiToolTest`
Expected: 编译失败 `cannot find symbol: method introContent()` 等

- [ ] **Step 3: 修改 CourseDetailResult —— 四 Tab 字段重定义**

`CourseDetailResult.java` 全文替换为（字段名与 DB Tab 对齐，删除 prerequisites/targetAudience）：

```java
package com.commerce.rag.bot.tool.dto;

import java.util.List;

/**
 * 课程详情 DTO —— queryCourseDetail(courseId) 的聚合返回
 *
 * <p>聚合课程摘要、排期、讲师与课程正文四 Tab 内容。
 * 四 Tab 字段与 db-schema course_content.content_type 枚举一一对应：
 * intro / syllabus / instructor / faq（本 spec S3 语义对齐，废弃 prerequisites/targetAudience 错位字段）。
 *
 * @param summary            课程摘要（与列表页同类型）
 * @param schedule           排期信息（含课时时长）
 * @param instructor         讲师档案（姓名/头衔/简介，来自 course_info.instructor_name 纯展示文本）
 * @param introContent       课程介绍（intro Tab，Markdown）
 * @param syllabusContent    课程大纲（syllabus Tab，Markdown）
 * @param instructorContent  讲师详细介绍（instructor Tab，Markdown）
 * @param faqContent         常见问题（faq Tab，Markdown）
 * @param enrollmentUrl      报名链接
 * @param tags               课程标签
 */
public record CourseDetailResult(
        CourseListResult.CourseSummary summary,
        ScheduleInfo schedule,
        InstructorInfo instructor,
        String introContent,
        String syllabusContent,
        String instructorContent,
        String faqContent,
        String enrollmentUrl,
        List<String> tags) {

    /**
     * 课程排期信息。
     *
     * @param nextStartDate 下期开班日期（ISO-8601 字符串，可为 null）
     * @param duration      课时时长标签（如 "12 weeks"）
     * @param totalLessons  总课时数
     * @param schedule      上课节奏描述（如 "Tue/Thu 19:00-21:00"）
     */
    public record ScheduleInfo(String nextStartDate, String duration, int totalLessons, String schedule) {}

    /**
     * 讲师档案。
     *
     * @param name  讲师姓名
     * @param title 讲师头衔（如 "Senior Architect"）
     * @param bio   讲师简介（简短）
     */
    public record InstructorInfo(String name, String title, String bio) {}
}
```

- [ ] **Step 4: 修改 CourseApiTool —— 映射修正**

`CourseApiTool.java` 的 `queryCourseDetail` 中，将（:91-95）

```java
        // 按内容类型提取（与设计文档一致的枚举值：intro / syllabus / instructor / faq）
        String description = extractContent(contents, "intro");
        String syllabus = extractContent(contents, "syllabus");
        String prerequisites = extractContent(contents, "instructor");
        String targetAudience = extractContent(contents, "faq");
```

替换为：

```java
        // 按内容类型提取：四 Tab 与 DTO 字段一一对应（db-schema 权威枚举：intro / syllabus / instructor / faq）
        String introContent = extractContent(contents, "intro");
        String syllabusContent = extractContent(contents, "syllabus");
        String instructorContent = extractContent(contents, "instructor");
        String faqContent = extractContent(contents, "faq");
```

并将构造调用（:102-111）

```java
        return new CourseDetailResult(
                summary,
                scheduleInfo,
                instructorInfo,
                description,
                syllabus,
                prerequisites,
                targetAudience,
                info.getEnrollmentLink() != null ? info.getEnrollmentLink() : "",
                parseTags(info.getTags()));
```

替换为：

```java
        return new CourseDetailResult(
                summary,
                scheduleInfo,
                instructorInfo,
                introContent,
                syllabusContent,
                instructorContent,
                faqContent,
                info.getEnrollmentLink() != null ? info.getEnrollmentLink() : "",
                parseTags(info.getTags()));
```

`emptyDetail`（:219-233）的构造同步替换为：

```java
    private CourseDetailResult emptyDetail(String courseId) {
        return new CourseDetailResult(
                new CourseSummary(courseId, "", "", "", null, null, "", null, Collections.emptyList()),
                new ScheduleInfo(null, "", 0, ""),
                new InstructorInfo("", "", ""),
                "",
                "",
                "",
                "",
                "",
                Collections.emptyList());
    }
```

- [ ] **Step 5: 运行 CourseApiToolTest 验证通过**

Run: `cd backend && mvn.cmd test -Dtest=CourseApiToolTest`
Expected: 全部 PASS

- [ ] **Step 6: 同步 backend-design.md §2.4 契约**

`docs/plans/2026-07-16-backend-design.md` 中，将

```markdown
| `CourseDetailResult` | 含 `ScheduleInfo`（nextStartDate/duration/totalLessons/schedule）+ `InstructorInfo`（name/title/bio）+ description/syllabus/prerequisites/targetAudience/enrollmentUrl/tags |
```

替换为：

```markdown
| `CourseDetailResult` | 含 `ScheduleInfo`（nextStartDate/duration/totalLessons/schedule）+ `InstructorInfo`（name/title/bio）+ introContent/syllabusContent/instructorContent/faqContent/enrollmentUrl/tags（四 Tab 与 course_content.content_type 枚举一一对应） |
```

- [ ] **Step 7: 残留检查 + 全量测试**

Run: `cd backend && grep -rn "prerequisites\|targetAudience" src/main/java src/test/java`（Expected: 无输出）
Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS

- [ ] **Step 8: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/java/com/commerce/rag/bot/tool/dto/CourseDetailResult.java backend/src/main/java/com/commerce/rag/bot/tool/CourseApiTool.java backend/src/test/java/com/commerce/rag/bot/tool/CourseApiToolTest.java docs/plans/2026-07-16-backend-design.md
git commit -m "fix: S3 CourseApiTool 四 Tab 与 DTO 字段语义对齐（intro/syllabus/instructor/faq）"
```

---

## 收尾（四任务全部完成后）

- [ ] **更新进度文档**：`docs/progress/2026-08-14-多模态rag重构spec定稿.md` §2.2 标记 S6 核验结论（已修复）、S2/S3 完成、S5 移出、P0 待决策
- [ ] **全量回归**：`cd backend && mvn.cmd test` 全过 = 干净基线快照
- [ ] 向用户汇报：干净基线达成；遗留决策（P0 立项、S5 brainstorm）等待用户拍板
