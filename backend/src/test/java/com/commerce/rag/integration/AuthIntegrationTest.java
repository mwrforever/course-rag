package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.test.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * 认证全链路集成测试（真实连接 Testcontainers PG + Redis）
 *
 * <p>覆盖链路（对应 AuthController + AuthSessionService + DeviceKickService + AuthInterceptor）：
 * <ol>
 *   <li>预置用户（无公开注册端点，直接写 sys_user 表，等价管理端创建）</li>
 *   <li>登录 → 签发 JWT → 携带 token 访问受保护接口（200）</li>
 *   <li>双设备互踢：同用户同设备类型二次登录 → Lua 原子黑名单旧 jti → 旧 token 立即 401，
 *       PG 审计层 sys_login_record 双记录</li>
 *   <li>登出：AT/RT 入黑名单（Redis + PG 双写）→ 旧 token 再访问 401</li>
 * </ol>
 *
 * <p>真实组件验证点：BCrypt 密码校验、JWT 签发/校验、Redis Lua 互踢脚本（kick_and_login.lua）、
 * 黑名单查询（Redis 执法层）、sys_login_record/sys_token_blacklist 审计落盘（PG）。
 *
 * @author commerce-rag
 */
class AuthIntegrationTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(AuthIntegrationTest.class);

    /** 本类预置用户（基类 @BeforeEach 清表后重新插入） */
    private static final String USERNAME = "it_student";

    @BeforeEach
    void setUpAuthUser() {
        registerUser(USERNAME, "STUDENT");
        log.info("认证集成测试用户预置完成: {}", USERNAME);
    }

    /**
     * 登录成功签发 JWT，携带 Access Token 可访问受保护接口（真实 JWT + AuthInterceptor 鉴权链路）。
     *
     * <p>断言链：
     * <ol>
     *   <li>POST /api/v1/auth/login → HTTP 200 + 业务码 0 + accessToken/refreshToken 非空</li>
     *   <li>GET /api/v1/student/courses（Bearer token）→ 200（AuthInterceptor 放行 + @PreAuthorize(STUDENT) 通过）</li>
     *   <li>sys_login_record 落库 1 条 ACTIVE（审计层真实写 PG）</li>
     * </ol>
     */
    @Test
    void 登录成功签发JWT并访问受保护接口() {
        JsonNode loginBody = login(USERNAME, TEST_PASSWORD, DEFAULT_DEVICE);
        assertEquals(0, loginBody.get("code").asInt(), "登录应返回业务码 0");
        JsonNode data = loginBody.get("data");
        assertNotNull(data, "登录响应 data 不应为空");
        String accessToken = data.get("accessToken").asText();
        String refreshToken = data.get("refreshToken").asText();
        assertTrue(accessToken != null && !accessToken.isEmpty(), "应签发 Access Token");
        assertTrue(refreshToken != null && !refreshToken.isEmpty(), "应签发 Refresh Token");
        assertEquals("STUDENT", data.get("role").asText(), "角色应为 STUDENT");
        // R0 契约显式守卫：userId 为雪花 Long，必须以字符串序列化下发（防 JS Number 精度丢失，
        // 前端反馈/会话等一切 id 按 string 消费——回归守卫防全局 Long→String 序列化被误删）
        assertTrue(data.get("userId").isTextual(), "userId 应以字符串下发（R0 Long→String 全局序列化契约）");

        // 携带 token 访问受保护接口（J1 我的课程，空选课表返回空列表）
        ResponseEntity<String> courses = getWithToken("/api/v1/student/courses", accessToken);
        assertEquals(200, courses.getStatusCode().value(), "携带有效 token 访问受保护接口应 200");

        // 审计层：登录记录真实落库 1 条 ACTIVE
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_login_record WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class,
                data.get("userId").asLong());
        assertEquals(1, count, "登录应写入 1 条 ACTIVE 登录记录");
    }

    /**
     * 双设备互踢：同用户同设备类型二次登录后，首 token 立即失效（Redis Lua 黑名单旧 jti）。
     *
     * <p>断言链：
     * <ol>
     *   <li>首设备登录 → token1；再次登录 → token2（kick_and_login.lua 执行，旧 jti 入黑名单）</li>
     *   <li>token1 访问受保护接口 → 401（AuthInterceptor 黑名单检查命中）</li>
     *   <li>token2 访问 → 200（新会话正常）</li>
     *   <li>sys_login_record 共 2 条（审计层双写），最新一条 ACTIVE</li>
     * </ol>
     */
    @Test
    void 第二设备登录互踢首设备Token失效() {
        String token1 = loginAndGetToken(USERNAME, DEFAULT_DEVICE);
        // 同用户同设备类型二次登录 → Lua 原子踢出旧会话
        String token2 = loginAndGetToken(USERNAME, DEFAULT_DEVICE);

        // 旧 token 立即失效（auth:bl:{jti} 命中 → 401）
        ResponseEntity<String> kicked = getWithToken("/api/v1/student/courses", token1);
        assertEquals(401, kicked.getStatusCode().value(), "被互踢的旧 token 应返回 401");
        // 新 token 正常
        ResponseEntity<String> alive = getWithToken("/api/v1/student/courses", token2);
        assertEquals(200, alive.getStatusCode().value(), "互踢后的新 token 应正常访问");

        // PG 审计层：两次登录各落 1 条记录（旧会话被踢不删除记录）
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM sys_login_record WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?) LIMIT 1",
                Long.class,
                USERNAME);
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_login_record WHERE user_id = ?", Integer.class, userId);
        assertEquals(2, total, "两次登录应落 2 条登录记录（互踢不删审计）");
        String latestStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM sys_login_record WHERE user_id = ? ORDER BY id DESC LIMIT 1", String.class, userId);
        assertEquals("ACTIVE", latestStatus, "最新登录记录应保持 ACTIVE");
    }

    /**
     * 登出吊销 Token：AT jti 入黑名单（Redis + PG 双写），旧 token 再访问 → 401。
     *
     * <p>断言链：
     * <ol>
     *   <li>登录 → token；POST /api/v1/auth/logout（Bearer）→ 200</li>
     *   <li>同一 token 再访问受保护接口 → 401（黑名单命中）</li>
     *   <li>sys_token_blacklist 存在该用户吊销记录（PG 审计双写）</li>
     * </ol>
     */
    @Test
    void 登出后Token进入黑名单并失效() {
        String token = loginAndGetToken(USERNAME, DEFAULT_DEVICE);

        // 登出（AuthConfig 排除拦截，controller 自行提取 AT）
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> logoutResp =
                restTemplate.exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertEquals(200, logoutResp.getStatusCode().value(), "登出应返回 200");

        // 登出后同一 token 失效（AT jti 已入黑名单）
        ResponseEntity<String> afterLogout = getWithToken("/api/v1/student/courses", token);
        assertEquals(401, afterLogout.getStatusCode().value(), "登出后的 token 再访问应 401");

        // PG 审计：黑名单记录落库（登出吊销 AT + 对应 RT）
        Integer blacklisted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_token_blacklist WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)",
                Integer.class,
                USERNAME);
        assertTrue(blacklisted != null && blacklisted >= 1, "登出应在 sys_token_blacklist 落库吊销记录");
    }
}
