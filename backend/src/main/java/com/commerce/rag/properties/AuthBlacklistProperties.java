package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Token 黑名单维护配置属性（B1-4）。
 * 绑定 application.yml 中 {@code auth.blacklist.*} 配置块。
 *
 * <pre>
 * auth:
 *   blacklist:
 *     cleanup-interval-seconds: 3600
 * </pre>
 *
 * @param cleanupIntervalSeconds 过期黑名单自动清理的调度间隔（秒，默认 3600=1h；
 *                               清理动作为软删 expires_at &lt; now() 的 sys_token_blacklist 行，
 *                               由 TokenBlacklistCleanupScheduler 定时触发，行本身已过 token
 *                               原始有效期，软删后不再参与认证降级查询）
 */
@Validated
@ConfigurationProperties(prefix = "auth.blacklist")
public record AuthBlacklistProperties(@Min(1) @DefaultValue("3600") long cleanupIntervalSeconds) {}
