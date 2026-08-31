package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * MilvusProperties 配置绑定测试（PERF-04 rpcDeadlineMs 配置化）
 *
 * <p>经 ApplicationContextRunner 走真实 @ConfigurationProperties 绑定管线
 * （record 构造器绑定），验证 @DefaultValue 默认值、显式配置覆盖与非法值启动拦截；
 * 默认值与 application.yml 中 milvus 段一致。
 *
 * @author commerce-rag
 */
@DisplayName("MilvusProperties rpcDeadlineMs 配置绑定测试")
class MilvusPropertiesTest {

    /** 注册被测属性类的最小配置上下文 */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EnableMilvusProperties.class);

    @Test
    @DisplayName("默认值 — 未配置时 rpcDeadlineMs=30000（30s，@DefaultValue 生效）")
    void defaultDeadline() {
        runner.run(assertDeadline(30000L));
    }

    @Test
    @DisplayName("显式覆盖 — milvus.rpc-deadline-ms=60000 生效（全配置化）")
    void customDeadline() {
        runner.withPropertyValues("milvus.rpc-deadline-ms=60000").run(assertDeadline(60000L));
    }

    @Test
    @DisplayName("非法值拦截 — rpcDeadlineMs=0 低于 @Min 下限时上下文启动失败（fail-fast）")
    void invalidDeadlineRejected() {
        runner.withPropertyValues("milvus.rpc-deadline-ms=0")
                .run(context -> assertEquals(true, context.getStartupFailure() != null));
    }

    /** 断言上下文内绑定出的 RPC 截止时间值（绑定失败时上下文启动失败即用例失败） */
    private ContextConsumer<ConfigurableApplicationContext> assertDeadline(long expected) {
        return context -> {
            MilvusProperties props = context.getBean(MilvusProperties.class);
            assertEquals(expected, props.rpcDeadlineMs());
        };
    }

    /** 最小注册配置（仅启用被测属性类） */
    @EnableConfigurationProperties(MilvusProperties.class)
    static class EnableMilvusProperties {}
}
