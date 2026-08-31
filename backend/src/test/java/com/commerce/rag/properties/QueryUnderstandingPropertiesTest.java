package com.commerce.rag.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * QueryUnderstandingProperties 配置绑定测试（2026-08-28 评审 C1 新增 stream-timeout）。
 *
 * <p>经 ApplicationContextRunner 走真实 @ConfigurationProperties 绑定管线（record 构造器绑定），
 * 验证 @DefaultValue 默认值（PT60S）、显式配置覆盖与非法值（零/负时长）启动阻断；
 * 补齐 Task 3 修复轮新增字段带来的 JaCoCo 单类覆盖缺口（0.75 &lt; 0.80 门禁）。
 *
 * <p>正性约束由紧凑构造器承载（jakarta 校验无 Duration 支持实现），
 * 非法配置必须在绑定阶段抛 IllegalArgumentException 阻断启动（宪法 A.2.2 语义）。
 *
 * @author commerce-rag
 */
@DisplayName("QueryUnderstandingProperties QU 流式聚合硬超时配置绑定测试")
class QueryUnderstandingPropertiesTest {

    /** 注册被测属性类的最小配置上下文 */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EnableQuProperties.class);

    @Test
    @DisplayName("默认值 — 未配置时流式聚合硬超时 60s（@DefaultValue PT60S 生效）")
    void defaultStreamTimeout() {
        runner.run(context -> {
            QueryUnderstandingProperties props = context.getBean(QueryUnderstandingProperties.class);
            assertThat(props.streamTimeout()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    @DisplayName("显式覆盖 — rag.query-understanding.stream-timeout=PT30S 生效（全配置化）")
    void customStreamTimeout() {
        runner.withPropertyValues("rag.query-understanding.stream-timeout=PT30S")
                .run(context -> {
                    QueryUnderstandingProperties props = context.getBean(QueryUnderstandingProperties.class);
                    assertThat(props.streamTimeout()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    @DisplayName("非法配置阻断启动 — 零/负时长经紧凑构造器抛 IllegalArgumentException fail-fast")
    void invalidStreamTimeout_blocksStartup() {
        for (String bad : new String[] {"PT0S", "PT-5S"}) {
            runner.withPropertyValues("rag.query-understanding.stream-timeout=" + bad)
                    .run(context -> {
                        assertThat(context.getStartupFailure()).isNotNull();
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                    });
        }
    }

    @Test
    @DisplayName("非法构造 — null 时长经紧凑构造器抛 IllegalArgumentException fail-fast")
    void nullStreamTimeout_rejectedByConstructor() {
        // model/maxQueries 传默认兜底值，被测目标仅 null 时长
        assertThatThrownBy(() -> new QueryUnderstandingProperties("qwen3.7-max-2026-06-08", 3, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rag.query-understanding.stream-timeout");
    }

    @Test
    @DisplayName("值语义 — 相同超时时长的实例相等且 toString 含配置值（record 值对象契约）")
    void valueSemantics() {
        QueryUnderstandingProperties a =
                new QueryUnderstandingProperties("qwen3.7-max-2026-06-08", 3, Duration.ofSeconds(60));
        QueryUnderstandingProperties b =
                new QueryUnderstandingProperties("qwen3.7-max-2026-06-08", 3, Duration.ofSeconds(60));
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("PT1M").contains("streamTimeout");
    }

    /** 最小注册配置（仅启用被测属性类） */
    @EnableConfigurationProperties(QueryUnderstandingProperties.class)
    static class EnableQuProperties {}
}
