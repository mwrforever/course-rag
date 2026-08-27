package com.commerce.rag.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 注册配置校验单元测试 —— 紧凑构造器正性/上限约束的启动期拒绝语义（审查 m2 对应实现）
 *
 * <p>注：jakarta 校验无 Duration 支持实现，正性约束由 record 紧凑构造器承载；
 * 本用例同时验证合法路径的字段透传。</p>
 */
class RegisterPropertiesTest {

    /** 与生产一致的全部合法参数 */
    private static RegisterProperties valid() {
        return new RegisterProperties(Duration.ofMinutes(15), Duration.ofSeconds(60), 5, 10, "问渠学堂", "主题", "");
    }

    @Test
    @DisplayName("合法配置：字段原样透传")
    void validConfiguration_bindsAsIs() {
        RegisterProperties properties = valid();
        assertThat(properties.codeTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.resendInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.maxVerifyAttempts()).isEqualTo(5);
        assertThat(properties.maxSendPerIpPerMinute()).isEqualTo(10);
    }

    @Test
    @DisplayName("非法时长（null/零/负）逐项阻断启动并给出键名提示")
    void invalidDurations_failFastWithKeyName() {
        for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new RegisterProperties(bad, Duration.ofSeconds(60), 5, 10, "名", "题", ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("register.code-ttl");
            assertThatThrownBy(() -> new RegisterProperties(Duration.ofMinutes(15), bad, 5, 10, "名", "题", ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("register.resend-interval");
        }
        assertThatThrownBy(() -> new RegisterProperties(null, null, 5, 10, "名", "题", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("register.code-ttl");
    }

    @Test
    @DisplayName("计数类阈值越界阻断启动：验证码尝试次数与 IP 分钟配额各自校验")
    void invalidCounters_failFast() {
        assertThatThrownBy(() ->
                        new RegisterProperties(Duration.ofMinutes(15), Duration.ofSeconds(60), 0, 10, "名", "题", ""))
                .hasMessageContaining("max-verify-attempts");

        for (int bad : new int[] {0, 601}) {
            final int quota = bad;
            assertThatThrownBy(() -> new RegisterProperties(
                            Duration.ofMinutes(15), Duration.ofSeconds(60), 5, quota, "名", "题", ""))
                    .hasMessageContaining("max-send-per-ip-per-minute");
        }
    }
}
