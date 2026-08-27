package com.commerce.rag.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 学员邮箱注册配置 —— 验证码生命周期、防频控与邮件文案（宪法 A.2.2 强类型绑定 + 启动期校验）
 *
 * <p>TTL 等阈值全部配置化（禁止散落硬编码）；绑定失败启动即失败。</p>
 *
 * @param codeTtl              验证码有效期（默认 PT15M = 15 分钟，业务要求 15 分钟失效）
 * @param resendInterval       同一邮箱两次发送的最小间隔（默认 PT60S，防刷信箱）
 * @param maxVerifyAttempts    单个验证码最大连续错误次数（默认 5 次，超过即作废防爆破）
 * @param maxSendPerIpPerMinute 单 IP 每分钟最大发码次数（默认 10，跨邮箱批量刷信防护——M2 审查加固）
 * @param fromName             邮件发件人显示名（品牌署名）
 * @param subject              邮件主题模板
 * @param fromEmail            发件邮箱（与 spring.mail.username 同源注入 MAIL_USERNAME；
 *                             独立声明以解除对 MailSender 实现类型的耦合）
 *
 * @author commerce-rag
 */
@Validated
@ConfigurationProperties(prefix = "register")
public record RegisterProperties(
        @DefaultValue("PT15M") @NotNull @Positive Duration codeTtl,
        @DefaultValue("PT60S") @NotNull @Positive Duration resendInterval,
        @DefaultValue("5") @Min(1) int maxVerifyAttempts,
        @DefaultValue("10") @Min(1) @Max(600) int maxSendPerIpPerMinute,
        @DefaultValue("问渠学堂") String fromName,
        @DefaultValue("【问渠学堂】注册验证码") String subject,
        @DefaultValue("") String fromEmail) {}
