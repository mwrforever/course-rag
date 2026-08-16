package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 认证安全配置属性。
 * 绑定 application.yml 中 auth.* 配置块。
 *
 * @param secret              JWT 签名密钥（HS256，至少 256 bits）
 * @param accessTokenExpiry   Access Token 有效期（秒，默认 900=15min）
 * @param refreshTokenExpiry  Refresh Token 有效期（秒，默认 604800=7d）
 * @param cookieName          Cookie 名称
 * @param cookieDomain        Cookie 域名
 * @param cookieSecure        Cookie 是否仅 HTTPS 传输（Secure 标记，默认 false；生产 HTTPS 应配 true，防中间人窃取 AT）
 * @param deviceTypes         支持的设备类型列表
 * @param strictSecret        JWT 密钥严格模式（BUG-7，默认 false）：true 时启动校验
 *                            JWT_SECRET 已配置且非内置默认值，否则拒绝启动（防生产漏配导致可伪造任意身份）
 */
@Validated
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        @NotBlank String secret,
        @Min(1) int accessTokenExpiry,
        @Min(1) long refreshTokenExpiry,
        @NotBlank String cookieName,
        String cookieDomain,
        @DefaultValue("false") boolean cookieSecure,
        List<String> deviceTypes,
        @DefaultValue("false") boolean strictSecret) {}
