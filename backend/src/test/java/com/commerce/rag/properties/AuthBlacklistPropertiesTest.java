package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * AuthBlacklistProperties 配置绑定测试（B1-4 黑名单自动清理间隔）
 *
 * <p>经 ApplicationContextRunner 走真实 @ConfigurationProperties 绑定管线
 * （record 构造器绑定），验证 @DefaultValue 默认值与显式配置覆盖均生效；
 * 默认值与 application.yml 中 auth.blacklist 段一致。
 *
 * @author commerce-rag
 */
@DisplayName("AuthBlacklistProperties 黑名单清理配置绑定测试")
class AuthBlacklistPropertiesTest {

    /** 注册被测属性类的最小配置上下文 */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EnableBlacklistProperties.class);

    @Test
    @DisplayName("默认值 — 未配置时清理间隔 3600s（1h，@DefaultValue 生效）")
    void defaultInterval() {
        runner.run(assertInterval(3600L));
    }

    @Test
    @DisplayName("显式覆盖 — auth.blacklist.cleanup-interval-seconds=86400 生效（全配置化）")
    void customInterval() {
        runner.withPropertyValues("auth.blacklist.cleanup-interval-seconds=86400")
                .run(assertInterval(86400L));
    }

    /** 断言上下文内绑定出的清理间隔值（绑定失败时上下文启动失败即用例失败） */
    private ContextConsumer<ConfigurableApplicationContext> assertInterval(long expected) {
        return context -> {
            AuthBlacklistProperties props = context.getBean(AuthBlacklistProperties.class);
            assertEquals(expected, props.cleanupIntervalSeconds());
        };
    }

    /** 最小注册配置（仅启用被测属性类） */
    @EnableConfigurationProperties(AuthBlacklistProperties.class)
    static class EnableBlacklistProperties {}
}
