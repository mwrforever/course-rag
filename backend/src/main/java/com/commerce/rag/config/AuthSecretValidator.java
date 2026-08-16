package com.commerce.rag.config;

import com.commerce.rag.properties.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * JWT 签名密钥启动校验（BUG-7 修复）
 *
 * <p>application.yml 中 {@code auth.secret} 带内置默认值（漏配 JWT_SECRET 环境变量时兜底），
 * 生产环境若漏配，任何人可用公开密钥伪造任意身份/角色的 Token 绕过鉴权。
 *
 * <p>校验策略（全配置化）：
 * <ul>
 *   <li>{@code auth.strict-secret=true}（生产建议）：secret 为空或等于内置默认值时拒绝启动</li>
 *   <li>{@code auth.strict-secret=false}（默认，开发可用）：仅当 secret 为内置默认值时打 WARN 提醒</li>
 * </ul>
 *
 * <p>依赖注入：Lombok @RequiredArgsConstructor（单依赖 AuthProperties）。
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class AuthSecretValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AuthSecretValidator.class);

    /** application.yml 中 auth.secret 的内置默认值（与 ${JWT_SECRET:...} 兜底值保持一致） */
    static final String DEFAULT_SECRET = "commerce-rag-secret-key-2026-must-be-at-least-256-bits-long-for-hs256";

    private final AuthProperties authProperties;

    /**
     * 启动校验：严格模式拒绝默认/缺失密钥；非严格模式仅告警提醒。
     *
     * @throws IllegalStateException strict-secret=true 且密钥缺失或为内置默认值时拒绝启动
     */
    @Override
    public void afterPropertiesSet() {
        String secret = authProperties.secret();
        boolean usingDefault = DEFAULT_SECRET.equals(secret);
        if (authProperties.strictSecret()) {
            if (secret == null || secret.isBlank() || usingDefault) {
                throw new IllegalStateException(
                        "auth.strict-secret=true 时 JWT_SECRET 必须配置且不能为内置默认值（生产漏配可被伪造任意身份 Token）");
            }
            log.info("JWT 密钥严格模式已生效（使用非默认密钥）");
        } else if (usingDefault) {
            log.warn("JWT 签名密钥为内置默认值（仅限开发环境；生产请配置 JWT_SECRET 环境变量并设 auth.strict-secret=true）");
        }
    }
}
