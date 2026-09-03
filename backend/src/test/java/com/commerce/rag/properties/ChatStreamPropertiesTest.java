package com.commerce.rag.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ChatStreamProperties 单元测试 —— 对话流式链路配置紧凑构造器校验（M3/M7 共用属性）。
 *
 * <p>测试覆盖（正常/边界/异常三类）：
 * <ul>
 *   <li>正常：合法四参构造 + record accessor 全取值透传断言；</li>
 *   <li>边界：三个数值下限恰好合法（sourcesReadyWaitMs=0 / stallTimeoutMs=1000 /
 *       retryBackoffMs=100）不触发拦截；</li>
 *   <li>异常：sourcesReadyWaitMs 为负 / stallTimeoutMs 低于 1000 / retryBackoffMs
 *       低于 100 各自抛 IllegalArgumentException 并给出配置键中文提示。</li>
 * </ul>
 *
 * <p>背景：T9=M3 派遣时仅测了 worker 侧取值，紧凑构造器三个 throw 分支未被覆盖，
 * 导致 jacoco CLASS 行覆盖 62.5%（5/8）低于 80% 门禁；本用例补齐至 100%。
 * 注：autoRetryMax 无构造器校验（@Min(0) 仅配置绑定期生效），不构造其异常用例。
 *
 * @author commerce-rag
 */
@DisplayName("ChatStreamProperties 对话流式链路配置紧凑构造器校验测试")
class ChatStreamPropertiesTest {

    /** 与生产默认值一致的合法四参（2000/45000/3/2000） */
    private static ChatStreamProperties valid() {
        return new ChatStreamProperties(2000, 45000, 3, 2000);
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("合法配置：四参构造原样透传，accessor 全取值一致")
    void validConfiguration_bindsAsIs() {
        ChatStreamProperties props = valid();
        assertThat(props.sourcesReadyWaitMs()).isEqualTo(2000);
        assertThat(props.stallTimeoutMs()).isEqualTo(45000);
        assertThat(props.autoRetryMax()).isEqualTo(3);
        assertThat(props.retryBackoffMs()).isEqualTo(2000);
    }

    // ==================== 边界条件 ====================

    @Test
    @DisplayName("边界下限恰好合法：0/1000/0/100 全下限构造不抛且取值保留")
    void lowerBoundValues_accepted() {
        // sourcesReadyWaitMs=0（@Min(0) 下限，允许零等待）、stallTimeoutMs=1000、
        // autoRetryMax=0（关闭自动重试）、retryBackoffMs=100 均为各字段合法下限
        ChatStreamProperties props = new ChatStreamProperties(0, 1000, 0, 100);
        assertThat(props.sourcesReadyWaitMs()).isZero();
        assertThat(props.stallTimeoutMs()).isEqualTo(1000);
        assertThat(props.autoRetryMax()).isZero();
        assertThat(props.retryBackoffMs()).isEqualTo(100);
    }

    // ==================== 异常场景 ====================

    @Test
    @DisplayName("sourcesReadyWaitMs 为负：紧凑构造器阻断并提示 rag.chat-stream.sources-ready-wait-ms")
    void negativeSourcesReadyWaitMs_failFast() {
        assertThatThrownBy(() -> new ChatStreamProperties(-1, 45000, 3, 2000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rag.chat-stream.sources-ready-wait-ms 不得为负");
    }

    @Test
    @DisplayName("stallTimeoutMs 低于 1000：紧凑构造器阻断并提示 rag.chat-stream.stall-timeout-ms")
    void stallTimeoutMsBelow1000_failFast() {
        assertThatThrownBy(() -> new ChatStreamProperties(2000, 999, 3, 2000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rag.chat-stream.stall-timeout-ms 不得低于 1000");
    }

    @Test
    @DisplayName("retryBackoffMs 低于 100：紧凑构造器阻断并提示 rag.chat-stream.retry-backoff-ms")
    void retryBackoffMsBelow100_failFast() {
        assertThatThrownBy(() -> new ChatStreamProperties(2000, 45000, 3, 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rag.chat-stream.retry-backoff-ms 不得低于 100");
    }

    // ==================== 校验独立性佐证 ====================

    @Test
    @DisplayName("单字段非法即拦截：stallTimeoutMs 合法时其余字段仍逐项独立校验")
    void eachGuardEvaluatesIndependently() {
        // stallTimeoutMs 取合法下限 1000，验证 retryBackoffMs 守卫不依赖前序分支
        assertThatThrownBy(() -> new ChatStreamProperties(2000, 1000, 3, 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rag.chat-stream.retry-backoff-ms 不得低于 100");
    }
}
