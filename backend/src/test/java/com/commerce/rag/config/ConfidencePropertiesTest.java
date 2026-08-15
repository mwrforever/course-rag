package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ConfidenceProperties 单元测试 —— 置信度配置属性验证
 *
 * <p>测试覆盖：默认值 fallback（0 值时）和自定义值正常传入。
 *
 * @author commerce-rag
 */
class ConfidencePropertiesTest {

    @Test
    @DisplayName("默认值 — 0 值时使用默认配置")
    void defaultValues_zeroValues_usesDefaults() {
        ConfidenceProperties props = new ConfidenceProperties(0.0, 0.0, 0);
        assertEquals(0.8, props.searchHighThreshold(), 0.001, "searchHighThreshold=0 时应回退到默认 0.8");
        assertEquals(0.5, props.searchMediumThreshold(), 0.001, "searchMediumThreshold=0 时应回退到默认 0.5");
        assertEquals(2, props.multiSourceMin(), "multiSourceMin=0 时应回退到默认 2");
    }

    @Test
    @DisplayName("自定义值 — 正常传入值被保留")
    void customValues_normalValues_preserved() {
        ConfidenceProperties props = new ConfidenceProperties(0.9, 0.6, 3);
        assertEquals(0.9, props.searchHighThreshold(), 0.001);
        assertEquals(0.6, props.searchMediumThreshold(), 0.001);
        assertEquals(3, props.multiSourceMin());
    }

    @Test
    @DisplayName("负值保护 — 负值时使用默认配置")
    void negativeValues_usesDefaults() {
        ConfidenceProperties props = new ConfidenceProperties(-0.1, -0.1, -1);
        assertEquals(0.8, props.searchHighThreshold(), 0.001, "负值时应回退到默认 0.8");
        assertEquals(0.5, props.searchMediumThreshold(), 0.001, "负值时应回退到默认 0.5");
        assertEquals(2, props.multiSourceMin(), "负值时应回退到默认 2");
    }
}
