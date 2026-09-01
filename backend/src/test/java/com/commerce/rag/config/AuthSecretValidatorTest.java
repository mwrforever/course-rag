package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.properties.AdminSeedProperties;
import com.commerce.rag.properties.AuthProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

/**
 * AuthSecretValidator 单元测试 —— 敏感环境变量启动校验（BUG-7 JWT + BUG-17 扩展 PG/Redis/超管种子）
 *
 * @author commerce-rag
 */
@DisplayName("AuthSecretValidator 敏感环境变量启动校验测试")
class AuthSecretValidatorTest {

    /** 非默认真实密码样本（模拟生产已注入 env 的值） */
    private static final String REAL_PG_PASSWORD = "prod-pg-real-password";

    private static final String REAL_REDIS_PASSWORD = "prod-redis-real-password";
    private static final String REAL_SEED_PASSWORD = "prod-seed-real-password";

    /** 构造认证配置（secret/strictSecret 可指定，其余取默认） */
    private AuthProperties props(String secret, boolean strictSecret) {
        return new AuthProperties(
                secret, 900, 604800L, "commerce_token", "localhost", false, List.of("WEB_DESKTOP"), strictSecret);
    }

    /** 构造数据源属性（password 可指定，null 表示完全未配置） */
    private DataSourceProperties dataSourceProps(String password) {
        DataSourceProperties props = new DataSourceProperties();
        props.setPassword(password);
        return props;
    }

    /** 构造 Redis 属性（password 可指定，null 表示完全未配置） */
    private RedisProperties redisProps(String password) {
        RedisProperties props = new RedisProperties();
        props.setPassword(password);
        return props;
    }

    /** 构造超管种子属性（password 可指定） */
    private AdminSeedProperties seedProps(String password) {
        return new AdminSeedProperties("admin", password, "系统管理员");
    }

    /**
     * 构造被测校验器：四个敏感项默认全部注入真实值（非默认/非空），
     * 单测按场景逐项覆盖为默认值以隔离验证
     */
    private AuthSecretValidator validator(
            AuthProperties authProps, String pgPassword, String redisPassword, String seedPassword) {
        return new AuthSecretValidator(
                authProps, dataSourceProps(pgPassword), redisProps(redisPassword), seedProps(seedPassword));
    }

    // ── JWT_SECRET（BUG-7 既有语义回归）──

    @Test
    @DisplayName("strict-secret=true + 内置默认密钥 → 拒绝启动（抛 IllegalStateException 且指明 JWT_SECRET）")
    void strictSecret_trueWithDefaultSecret_throws() {
        AuthSecretValidator validator = validator(
                props(AuthSecretValidator.DEFAULT_SECRET, true),
                REAL_PG_PASSWORD,
                REAL_REDIS_PASSWORD,
                REAL_SEED_PASSWORD);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("JWT_SECRET"), "异常消息应指明缺失的 JWT_SECRET");
    }

    @Test
    @DisplayName("strict-secret=true + 密钥缺失（null）→ 拒绝启动")
    void strictSecret_trueWithNullSecret_throws() {
        AuthSecretValidator validator =
                validator(props(null, true), REAL_PG_PASSWORD, REAL_REDIS_PASSWORD, REAL_SEED_PASSWORD);

        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    @DisplayName("strict-secret=true + 已配置非默认密钥 → 通过（严格模式生效）")
    void strictSecret_trueWithCustomSecret_passes() {
        AuthSecretValidator validator = validator(
                props("custom-secret-256bits...", true), REAL_PG_PASSWORD, REAL_REDIS_PASSWORD, REAL_SEED_PASSWORD);

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    @DisplayName("strict-secret=false（默认）+ 内置默认密钥 → 不拦截（仅告警，开发环境可用）")
    void strictSecret_falseWithDefaultSecret_passes() {
        AuthSecretValidator validator = validator(
                props(AuthSecretValidator.DEFAULT_SECRET, false),
                REAL_PG_PASSWORD,
                REAL_REDIS_PASSWORD,
                REAL_SEED_PASSWORD);

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    @DisplayName("strict-secret=false + 自定义密钥 → 通过且不告警")
    void strictSecret_falseWithCustomSecret_passes() {
        AuthSecretValidator validator = validator(
                props("custom-secret-256bits...", false), REAL_PG_PASSWORD, REAL_REDIS_PASSWORD, REAL_SEED_PASSWORD);

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    // ── PG_PASSWORD（BUG-17 新增）──

    @Test
    @DisplayName("strict-secret=true + PG 密码为内置默认值 → 拒绝启动且指明 PG_PASSWORD")
    void strictSecret_trueWithDefaultPgPassword_throws() {
        AuthSecretValidator validator = validator(
                props("custom-secret-256bits...", true),
                AuthSecretValidator.DEFAULT_DATASOURCE_PASSWORD,
                REAL_REDIS_PASSWORD,
                REAL_SEED_PASSWORD);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("PG_PASSWORD"), "异常消息应指明缺失的 PG_PASSWORD");
    }

    @Test
    @DisplayName("strict-secret=true + PG 密码为空（未注入）→ 拒绝启动")
    void strictSecret_trueWithBlankPgPassword_throws() {
        AuthSecretValidator validator =
                validator(props("custom-secret-256bits...", true), "", REAL_REDIS_PASSWORD, REAL_SEED_PASSWORD);

        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    // ── REDIS_PASSWORD（BUG-17 新增）──

    @Test
    @DisplayName("strict-secret=true + Redis 密码为内置默认值 → 拒绝启动且指明 REDIS_PASSWORD")
    void strictSecret_trueWithDefaultRedisPassword_throws() {
        AuthSecretValidator validator = validator(
                props("custom-secret-256bits...", true),
                REAL_PG_PASSWORD,
                AuthSecretValidator.DEFAULT_REDIS_PASSWORD,
                REAL_SEED_PASSWORD);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("REDIS_PASSWORD"), "异常消息应指明缺失的 REDIS_PASSWORD");
    }

    // ── AUTH_ADMIN_SEED_PASSWORD（BUG-17 新增）──

    @Test
    @DisplayName("strict-secret=true + 超管种子密码为出厂默认 admin123 → 拒绝启动且指明 AUTH_ADMIN_SEED_PASSWORD")
    void strictSecret_trueWithDefaultSeedPassword_throws() {
        AuthSecretValidator validator = validator(
                props("custom-secret-256bits...", true),
                REAL_PG_PASSWORD,
                REAL_REDIS_PASSWORD,
                AdminSeedInitializer.FACTORY_DEFAULT_PASSWORD);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("AUTH_ADMIN_SEED_PASSWORD"), "异常消息应指明缺失的 AUTH_ADMIN_SEED_PASSWORD");
    }

    // ── 汇总与放行语义 ──

    @Test
    @DisplayName("strict-secret=true + 多项默认值 → 一次抛出并完整列出全部缺失 env（免运维逐项试错）")
    void strictSecret_trueWithMultipleDefaults_listsAllMissing() {
        AuthSecretValidator validator = validator(
                props(AuthSecretValidator.DEFAULT_SECRET, true),
                AuthSecretValidator.DEFAULT_DATASOURCE_PASSWORD,
                AuthSecretValidator.DEFAULT_REDIS_PASSWORD,
                AdminSeedInitializer.FACTORY_DEFAULT_PASSWORD);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("JWT_SECRET"));
        assertTrue(ex.getMessage().contains("PG_PASSWORD"));
        assertTrue(ex.getMessage().contains("REDIS_PASSWORD"));
        assertTrue(ex.getMessage().contains("AUTH_ADMIN_SEED_PASSWORD"));
    }

    @Test
    @DisplayName("strict-secret=true + 四项敏感值均已注入真实值 → 通过")
    void strictSecret_trueWithAllRealValues_passes() {
        AuthSecretValidator validator = validator(
                props("custom-secret-256bits...", true), REAL_PG_PASSWORD, REAL_REDIS_PASSWORD, REAL_SEED_PASSWORD);

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    @DisplayName("strict-secret=false + 全部内置默认值 → 不拦截（开发/CI 零行为变化，仅逐项告警）")
    void strictSecret_falseWithAllDefaults_passes() {
        AuthSecretValidator validator = validator(
                props(AuthSecretValidator.DEFAULT_SECRET, false),
                AuthSecretValidator.DEFAULT_DATASOURCE_PASSWORD,
                AuthSecretValidator.DEFAULT_REDIS_PASSWORD,
                AdminSeedInitializer.FACTORY_DEFAULT_PASSWORD);

        assertDoesNotThrow(validator::afterPropertiesSet);
    }
}
