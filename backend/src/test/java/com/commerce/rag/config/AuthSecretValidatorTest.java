package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.commerce.rag.properties.AuthProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AuthSecretValidator 单元测试 —— JWT 密钥启动校验（BUG-7）
 *
 * @author commerce-rag
 */
@DisplayName("AuthSecretValidator JWT 密钥启动校验测试")
class AuthSecretValidatorTest {

    /** 构造认证配置（secret 可指定，strictSecret 可指定） */
    private AuthProperties props(String secret, boolean strictSecret) {
        return new AuthProperties(
                secret, 900, 604800L, "commerce_token", "localhost", false, List.of("WEB_DESKTOP"), strictSecret);
    }

    @Test
    @DisplayName("strict-secret=true + 内置默认密钥 → 拒绝启动（抛 IllegalStateException）")
    void strictSecret_trueWithDefaultSecret_throws() {
        AuthSecretValidator validator = new AuthSecretValidator(props(AuthSecretValidator.DEFAULT_SECRET, true));

        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    @DisplayName("strict-secret=true + 密钥缺失（null）→ 拒绝启动")
    void strictSecret_trueWithNullSecret_throws() {
        AuthSecretValidator validator = new AuthSecretValidator(props(null, true));

        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    @DisplayName("strict-secret=true + 已配置非默认密钥 → 通过（严格模式生效）")
    void strictSecret_trueWithCustomSecret_passes() {
        AuthSecretValidator validator = new AuthSecretValidator(props("custom-secret-256bits...", true));

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    @DisplayName("strict-secret=false（默认）+ 内置默认密钥 → 不拦截（仅告警，开发环境可用）")
    void strictSecret_falseWithDefaultSecret_passes() {
        AuthSecretValidator validator = new AuthSecretValidator(props(AuthSecretValidator.DEFAULT_SECRET, false));

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    @DisplayName("strict-secret=false + 自定义密钥 → 通过且不告警")
    void strictSecret_falseWithCustomSecret_passes() {
        AuthSecretValidator validator = new AuthSecretValidator(props("custom-secret-256bits...", false));

        assertDoesNotThrow(validator::afterPropertiesSet);
    }
}
