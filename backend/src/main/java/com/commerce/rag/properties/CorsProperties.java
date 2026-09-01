package com.commerce.rag.properties;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * CORS 跨域来源配置属性（BUG-12 @Value 收敛）。
 * 绑定 application.yml 中 {@code cors.allowed-origins} 配置项，供
 * {@code config/CorsConfig} 消费。
 *
 * <p>原 {@code CorsConfig} 字段级 {@code @Value} 散落注入，现收敛为属性类统一强类型
 * 绑定（宪法 A.2.2）。与 httpOnly cookie 协同需 allowCredentials=true，此时来源
 * 不能用 "*"，必须指定具体来源列表。
 *
 * <p>默认值为本机三端口开发兜底（与原 {@code @Value} 兜底值相同）；生产经
 * CORS_ALLOWED_ORIGINS 环境变量注入真实前端域名。
 *
 * <pre>
 * cors:
 *   allowed-origins: http://localhost:5000,http://localhost:5001
 * </pre>
 *
 * @param allowedOrigins 允许的跨域来源列表（逗号分隔绑定，allowCredentials=true 时禁止 *；
 *                       非空——至少一个来源，空列表无跨域语义）
 */
@Validated
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(
        @NotEmpty @DefaultValue("http://localhost:3000,http://localhost:3001,http://localhost:5173")
                String[] allowedOrigins) {}
