package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * AdminSeedProperties 配置绑定测试（默认管理员账户种子）
 *
 * <p>经 ApplicationContextRunner 走真实 @ConfigurationProperties 绑定管线
 * （record 构造器绑定），验证 @DefaultValue 默认值与显式配置（等价于
 * {@code AUTH_ADMIN_SEED_*} 环境变量覆盖）均生效；
 * 默认值与 application.yml 中 auth.admin-seed 段一致。
 *
 * @author commerce-rag
 */
@DisplayName("AdminSeedProperties 默认管理员种子配置绑定测试")
class AdminSeedPropertiesTest {

    /** 注册被测属性类的最小配置上下文 */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EnableAdminSeedProperties.class);

    @Test
    @DisplayName("默认值 — 未配置时 username=admin / password=admin123 / display-name=系统管理员")
    void defaults() {
        runner.run(assertSeed("admin", "admin123", "系统管理员"));
    }

    @Test
    @DisplayName("显式覆盖 — auth.admin-seed.* 生效（env 变量 AUTH_ADMIN_SEED_* 同源绑定）")
    void customValues() {
        runner.withPropertyValues(
                        "auth.admin-seed.username=custom-admin",
                        "auth.admin-seed.password=real-secret",
                        "auth.admin-seed.display-name=运维管理员")
                .run(assertSeed("custom-admin", "real-secret", "运维管理员"));
    }

    /** 断言上下文内绑定出的种子配置（绑定失败时上下文启动失败即用例失败） */
    private ContextConsumer<ConfigurableApplicationContext> assertSeed(
            String expectedUsername, String expectedPassword, String expectedDisplayName) {
        return context -> {
            AdminSeedProperties props = context.getBean(AdminSeedProperties.class);
            assertEquals(expectedUsername, props.username());
            assertEquals(expectedPassword, props.password());
            assertEquals(expectedDisplayName, props.displayName());
        };
    }

    /** 最小注册配置（仅启用被测属性类） */
    @EnableConfigurationProperties(AdminSeedProperties.class)
    static class EnableAdminSeedProperties {}
}
