package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.test.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

/**
 * 安全链路集成测试 —— 401 / 403 / 200 三类 HTTP 语义（真实 SecurityConfig + AuthInterceptor + @PreAuthorize）
 *
 * <p>覆盖链路：
 * <ol>
 *   <li>无 token 访问受保护接口 → 401（AuthInterceptor 未认证拒绝）</li>
 *   <li>密码错误登录 → 401（BizException → GlobalExceptionHandler 真实 HTTP 状态）</li>
 *   <li>STUDENT 访问管理端端点 → 403（@PreAuthorize hasAnyRole('SUPER_ADMIN','TEACHER') 拒绝）</li>
 *   <li>SUPER_ADMIN 访问管理端端点 → 200（角色门禁放行）</li>
 *   <li>me 端点鉴权（M10/R5）：无 token → 401 / 带 token → 200 身份三字段（排除精确化回归锁）</li>
 * </ol>
 *
 * <p>管理端端点选取 GET /api/v1/admin/courses（AdminCourseController 列表，空表返回空分页，不依赖业务数据）。
 *
 * @author commerce-rag
 */
class SecurityIntegrationTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(SecurityIntegrationTest.class);

    private static final String STUDENT = "sec_student";
    private static final String ADMIN = "sec_admin";

    @BeforeEach
    void setUpSecurityUsers() {
        registerUser(STUDENT, "STUDENT");
        registerUser(ADMIN, "SUPER_ADMIN");
        log.info("安全集成测试用户预置完成: student={}, admin={}", STUDENT, ADMIN);
    }

    /**
     * 无 token 访问受保护接口 → 401（AuthInterceptor sendUnauthorized，真实 HTTP 状态码）。
     */
    @Test
    void 无令牌访问受保护接口返回401() {
        ResponseEntity<String> response = getWithToken("/api/v1/student/courses", null);
        assertEquals(401, response.getStatusCode().value(), "无 token 访问受保护接口应 401");
        // 响应体为拦截器直写 JSON（code=401 与 HTTP 状态同值的前端契约）
        assertTrue(response.getBody() != null && response.getBody().contains("\"code\":401"), "401 响应体应包含 code=401");
    }

    /**
     * 密码错误登录 → 401（BizException(UNAUTHORIZED) → GlobalExceptionHandler → HTTP 401）。
     */
    @Test
    void 密码错误登录返回401() {
        JsonNode body = login(STUDENT, "wrong-password", DEFAULT_DEVICE);
        assertEquals(401, body.get("code").asInt(), "密码错误应返回业务码 401");
    }

    /**
     * STUDENT 角色访问管理端端点 → 403（@PreAuthorize 方法级鉴权拒绝，GlobalExceptionHandler 统一 403）。
     */
    @Test
    void 学生访问管理端端点返回403() {
        String token = loginAndGetToken(STUDENT, DEFAULT_DEVICE);
        ResponseEntity<String> response = getWithToken("/api/v1/admin/courses", token);
        assertEquals(403, response.getStatusCode().value(), "STUDENT 访问管理端端点应 403");
    }

    /**
     * SUPER_ADMIN 角色访问管理端端点 → 200（角色门禁放行，空表返回空分页）。
     */
    @Test
    void 超级管理员访问管理端端点返回200() {
        String token = loginAndGetToken(ADMIN, DEFAULT_DEVICE);
        ResponseEntity<String> response = getWithToken("/api/v1/admin/courses", token);
        assertEquals(200, response.getStatusCode().value(), "SUPER_ADMIN 访问管理端端点应 200");
        assertTrue(response.getBody() != null && response.getBody().contains("\"code\":0"), "管理端列表应返回业务码 0");
    }

    /**
     * 无 token 访问 GET /api/v1/auth/me → 401（M10/R5：AuthConfig 排除已精确化为五端点白名单，
     * me 不在排除列表、必走 AuthInterceptor 鉴权——回归锁，防排除模式回退通配后 me 裸奔）。
     */
    @Test
    void 无令牌访问me端点返回401() {
        ResponseEntity<String> response = getWithToken("/api/v1/auth/me", null);
        assertEquals(401, response.getStatusCode().value(), "无 token 访问 me 端点应 401（走拦截器鉴权）");
        assertTrue(response.getBody() != null && response.getBody().contains("\"code\":401"), "401 响应体应包含 code=401");
    }

    /**
     * 带 token 访问 GET /api/v1/auth/me → 200 且返回身份三字段（userId/role/displayName）。
     *
     * <p>userId 断言取预置用户 ID（Long → String 全局序列化契约，前端 MeResponse.userId 为 string）；
     * 预置用户 displayName=username（IntegrationTestBase.registerUser 契约）。
     */
    @Test
    void 带令牌访问me端点返回身份三字段() {
        Long adminId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN);
        String token = loginAndGetToken(ADMIN, DEFAULT_DEVICE);

        ResponseEntity<String> response = getWithToken("/api/v1/auth/me", token);

        assertEquals(200, response.getStatusCode().value(), "带 token 访问 me 端点应 200");
        JsonNode body;
        try {
            body = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("me 响应 JSON 解析失败: " + response.getBody(), e);
        }
        assertEquals(0, body.get("code").asInt(), "me 应返回业务码 0");
        JsonNode data = body.get("data");
        assertTrue(data != null && data.isObject(), "me 响应应包含 data 对象");
        assertEquals(adminId.toString(), data.get("userId").asText(), "userId 应为预置用户 ID（Long 序列化为 string）");
        assertEquals("SUPER_ADMIN", data.get("role").asText(), "role 应为预置角色");
        assertEquals(ADMIN, data.get("displayName").asText(), "displayName 应为预置显示名（=username）");
    }
}
