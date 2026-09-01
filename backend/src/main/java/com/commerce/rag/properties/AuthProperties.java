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
 * @param strictSecret        敏感环境变量严格模式（BUG-7 引入，默认 false；BUG-17 扩展覆盖范围）：
 *                            true 时启动校验 JWT_SECRET / PG_PASSWORD / REDIS_PASSWORD /
 *                            AUTH_ADMIN_SEED_PASSWORD 均已注入且非内置默认值，任一命中缺失
 *                            即汇总抛异常拒绝启动（防生产漏配导致弱口令/可伪造身份）
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
