package com.commerce.rag.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.commerce.rag.test.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 学员邮箱注册全链路集成测试 —— Testcontainers 真实 PG/Redis，覆盖两段式注册契约
 *
 * <p>JavaMailSender 以 @MockitoBean 整体替换（不外呼真实 SMTP），通过捕获真实 MimeMessage
 * 反解出六位验证码驱动后续步骤；频控/原子消费走真实 Redis Lua，落库唯一索引走真实 PG。</p>
 */
class RegisterIntegrationTest extends IntegrationTestBase {

    private static final Pattern CODE_IN_DIV = Pattern.compile(">\\s*(\\d{6})\\s*</div>");

    /** 注册验证码邮件发送器依赖的 JavaMailSender：集成态整体替换为可捕获的 mock */
    @MockitoBean
    private JavaMailSender mailSender;

    /**
     * 自给发件地址：本类以 @MockitoBean 替换 JavaMailSender，resolveFromAddress 的
     * 「回退读 sender 用户名」分支永不生效；CI 环境亦无 .env 提供 MAIL_USERNAME。
     * 显式注入使正常路径用例聚焦注册契约本身（「未配置 fail-fast」由
     * RegisterMailSenderTest 单测独立覆盖）。
     */
    @org.springframework.test.context.DynamicPropertySource
    static void registerTestProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("register.from-email", () -> "noreply-wenqu@test.example");
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("注册全流程：发码→反解验证码→完成注册→双 Token 签发且可用邮箱直接登录")
    void registerFlow_happyPathEndsWithEmailLoginWorking() throws Exception {
        allowMailDelivery();
        String email = "happy.path@example.com";

        // 1. 第一步：请求验证码（真实 Redis 频控锁 + 真实模板组装）
        ResponseEntity<String> codeResp = postJson("/api/v1/auth/register/code", "{\"email\":\"" + email + "\"}");
        assertThat(codeResp.getStatusCode().value()).isEqualTo(200);

        // 2. 从邮件正文反解六位验证码
        String code = extractCapturedCode();

        // 3. 第二步：携码完成注册（昵称缺省走邮箱前缀）
        String registerBody =
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"password\":\"Super-Secret-88\"}";
        ResponseEntity<String> regResp = postJson("/api/v1/auth/register", registerBody);
        assertThat(regResp.getStatusCode().value())
                .as("注册响应: %s", regResp.getBody())
                .isEqualTo(200);
        JsonNode regJson = readTree(regResp.getBody());
        assertThat(regJson.get("code").asInt()).isZero();
        assertThat(regJson.at("/data/accessToken").asText()).isNotBlank();
        assertThat(regJson.at("/data/refreshToken").asText()).isNotBlank();
        assertThat(regJson.at("/data/role").asText()).isEqualTo("STUDENT");

        // 4. 落库复核：email 列与角色正确写入 sys_user（V15 迁移生效证据）
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE email = ? AND role = 'STUDENT'", Integer.class, email);
        assertThat(userCount).isEqualTo(1);

        // 5. 邮箱登录回退路径可用（username 字段传邮箱）
        ResponseEntity<String> loginResp =
                postJson("/api/v1/auth/login", "{\"username\":\"" + email + "\",\"password\":\"Super-Secret-88\"}");
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        assertThat(readTree(loginResp.getBody()).at("/data/displayName").asText())
                .isEqualTo("happypath");
    }

    @Test
    @DisplayName("频控契约：重发间隔窗口内的第二次发码请求被拒 409")
    void registerFlow_resendInsideIntervalIsRejected() {
        allowMailDelivery();
        postJson("/api/v1/auth/register/code", mapJson("email", "freq@example.com"));

        ResponseEntity<String> second = postJson("/api/v1/auth/register/code", mapJson("email", "freq@example.com"));

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(readTree(second.getBody()).get("message").asText()).contains("频繁");
    }

    @Test
    @DisplayName("防爆破契约：连续错误四次后第五次触发锁定作废，正确码亦失效")
    void registerFlow_locksOutAfterMaxAttemptsAndInvalidatesCode() throws Exception {
        allowMailDelivery();
        String email = "lockout@example.com";
        postJson("/api/v1/auth/register/code", mapJson("email", email));
        String correctCode = extractCapturedCode();

        // 错误尝试 ×4（普通计数阶段）
        for (int i = 0; i < 4; i++) {
            ResponseEntity<String> resp = postJson("/api/v1/auth/register", registerBodyFor(email, "000000"));
            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            assertThat(readTree(resp.getBody()).get("message").asText()).isEqualTo("验证码错误");
        }
        // 第 5 次错误：达到 max-verify-attempts → LOCKED
        ResponseEntity<String> fifth = postJson("/api/v1/auth/register", registerBodyFor(email, "000001"));
        assertThat(readTree(fifth.getBody()).get("message").asText()).isEqualTo("尝试次数过多，验证码已失效，请重新获取");

        // 原本正确的码也已随锁定被删除 → EXPIRED
        ResponseEntity<String> withCorrect = postJson("/api/v1/auth/register", registerBodyFor(email, correctCode));
        assertThat(readTree(withCorrect.getBody()).get("message").asText()).isEqualTo("验证码已过期，请重新获取");

        // 未产生任何用户
        Integer users =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE email = ?", Integer.class, email);
        assertThat(users).isZero();
    }

    @Test
    @DisplayName("重复注册拦截：已注册邮箱再次申请验证码被拒 409")
    void registerFlow_duplicateEmailRejectedOnSecondSend() throws Exception {
        allowMailDelivery();
        String email = "dup.flow@example.com";
        postJson("/api/v1/auth/register/code", mapJson("email", email));
        String regCode = extractCapturedCode();
        ResponseEntity<String> regResp = postJson("/api/v1/auth/register", registerBodyFor(email, regCode));
        assertThat(regResp.getStatusCode().value())
                .as("重复邮箱注册响应: %s", regResp.getBody())
                .isEqualTo(200);

        ResponseEntity<String> again = postJson("/api/v1/auth/register/code", mapJson("email", email));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(readTree(again.getBody()).get("message").asText()).contains("已注册");
    }

    @Test
    @DisplayName("SMTP 故障语义：发送失败转 503 且未创建用户")
    void registerFlow_smtpFailureReturns503WithoutUserCreation() {
        Mockito.doReturn(new org.springframework.mail.javamail.JavaMailSenderImpl().createMimeMessage())
                .when(mailSender)
                .createMimeMessage();
        doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(MimeMessage.class));

        ResponseEntity<String> resp = postJson("/api/v1/auth/register/code", mapJson("email", "smtp.fail@example.com"));

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        Integer users = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE email = 'smtp.fail@example.com'", Integer.class);
        assertThat(users).isZero();
    }

    // ==================== 测试基础设施 ====================

    /** 允许邮件进入成功分支：createMimeMessage 返回真实可用对象（Mock 默认 null 会导致组装 NPE） */
    private void allowMailDelivery() {
        Mockito.doReturn(new org.springframework.mail.javamail.JavaMailSenderImpl().createMimeMessage())
                .when(mailSender)
                .createMimeMessage();
        doNothing().when(mailSender).send(any(MimeMessage.class));
    }

    /** 发起 JSON POST 并返回原始响应体字符串 */
    private ResponseEntity<String> postJson(String path, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(path, new HttpEntity<>(jsonBody, headers), String.class);
    }

    /** 单键值 JSON 工具（仅服务本类最简场景） */
    private String mapJson(String k1, String v1) {
        return "{\"" + k1 + "\":\"" + v1 + "\"}";
    }

    /** 构造四字段注册请求体（首两项 email/code 来自 DTO 契约） */
    private String registerBodyFor(String email, String code) {
        return "{\"email\":\"" + email + "\",\"code\":\"" + code
                + "\",\"password\":\"Reg-Password-2026\",\"nickname\":null}";
    }

    /** 解析响应 JSON 为树（失败给出明确上下文） */
    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应非 JSON: " + body, e);
        }
    }

    /** 提取最近一次捕获邮件中的六位验证码（无捕获或无匹配时抛出明确异常） */
    private String extractCapturedCode() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        Mockito.verify(mailSender, Mockito.atLeastOnce()).send(captor.capture());
        String content = dumpContent(captor.getValue());
        Matcher m = CODE_IN_DIV.matcher(content);
        if (!m.find()) {
            throw new IllegalStateException("邮件正文未找到六位验证码");
        }
        return m.group(1);
    }

    /** 递归展开邮件 Part 内容为字符串（multipart / 文本双兼容） */
    private String dumpContent(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof Multipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                sb.append(dumpContent(mp.getBodyPart(i)));
            }
            return sb.toString();
        }
        return "";
    }
}
