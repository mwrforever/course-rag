package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TokenBudgetProperties 单元测试 —— Token 预算配置属性验证
 *
 * <p>测试覆盖：默认值 fallback（0 值时）和自定义值正常传入。
 *
 * @author commerce-rag
 */
class TokenBudgetPropertiesTest {

    @Test
    @DisplayName("默认值 — 0 值时使用默认配置")
    void defaultValues_zeroValues_usesDefaults() {
        TokenBudgetProperties props = new TokenBudgetProperties(0L, 0.0, 0.0);
        assertEquals(200000L, props.maxTokensPerRun(), "maxTokensPerRun=0 时应回退到默认 200000");
        assertEquals(0.8, props.warnRatio(), 0.001, "warnRatio=0 时应回退到默认 0.8");
        assertEquals(1.0, props.hardStopRatio(), 0.001, "hardStopRatio=0 时应回退到默认 1.0");
    }

    @Test
    @DisplayName("自定义值 — 正常传入值被保留")
    void customValues_normalValues_preserved() {
        TokenBudgetProperties props = new TokenBudgetProperties(1000L, 0.5, 0.9);
        assertEquals(1000L, props.maxTokensPerRun());
        assertEquals(0.5, props.warnRatio(), 0.001);
        assertEquals(0.9, props.hardStopRatio(), 0.001);
    }

    @Test
    @DisplayName("负值保护 — 负值时使用默认配置")
    void negativeValues_usesDefaults() {
        TokenBudgetProperties props = new TokenBudgetProperties(-1L, -0.1, -0.1);
        assertEquals(200000L, props.maxTokensPerRun(), "负值时应回退到默认 200000");
        assertEquals(0.8, props.warnRatio(), 0.001, "负值时应回退到默认 0.8");
        assertEquals(1.0, props.hardStopRatio(), 0.001, "负值时应回退到默认 1.0");
    }
}
