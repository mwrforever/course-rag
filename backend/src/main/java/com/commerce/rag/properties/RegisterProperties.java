package com.commerce.rag.properties;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 学员邮箱注册配置 —— 验证码生命周期、防频控与邮件文案（宪法 A.2.2 强类型绑定 + 启动期校验）
 *
 * <p>TTL 等阈值全部配置化（禁止散落硬编码）；绑定失败启动即失败。</p>
 *
 * @param codeTtl           验证码有效期（默认 PT15M = 15 分钟，业务要求 15 分钟失效）
 * @param resendInterval    同一邮箱两次发送的最小间隔（默认 PT60S，防刷信箱）
 * @param maxVerifyAttempts 单个验证码最大连续错误次数（默认 5 次，超过即作废防爆破）
 * @param fromName          邮件发件人显示名（品牌署名）
 * @param subject           邮件主题模板
 *
 * @author commerce-rag
 */
@Validated
@ConfigurationProperties(prefix = "register")
public record RegisterProperties(
        @DefaultValue("PT15M") @NotNull Duration codeTtl,
        @DefaultValue("PT60S") @NotNull Duration resendInterval,
        @DefaultValue("5") int maxVerifyAttempts,
        @DefaultValue("问渠学堂") String fromName,
        @DefaultValue("【问渠学堂】注册验证码") String subject) {}
