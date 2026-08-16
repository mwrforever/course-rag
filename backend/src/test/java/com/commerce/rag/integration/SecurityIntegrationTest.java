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
}
