package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.constants.PreferenceKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MemoryProperties 默认值测试（与 application.yml memory 段一致） */
class MemoryPropertiesTest {

    private final MemoryProperties props = new MemoryProperties();

    @Test
    @DisplayName("extraction 默认 — 模型 qwen3.7-flash/防抖 30s/超时 10s/线程 2")
    void extractionDefaults() {
        assertEquals("qwen3.7-flash", props.getExtraction().getModel());
        assertEquals(30, props.getExtraction().getDebounceWindowSeconds());
        assertEquals(10_000L, props.getExtraction().getTimeoutMs());
        assertEquals(2, props.getExtraction().getThreads());
    }

    @Test
    @DisplayName("preference 默认 — 阈值 0.75/0.50/0.80、晋升 count≥5、曲线 0.1+0.15、预算 500/1500、缓存 30min")
    void preferenceDefaults() {
        var p = props.getPreference();
        assertEquals(0.75, p.getWriteHigh());
        assertEquals(0.50, p.getObserveLow());
        assertEquals(0.80, p.getExplicitUpdate());
        assertEquals(5, p.getPromoteMinCount());
        assertEquals(0.75, p.getPromoteMinScore());
        assertEquals(0.1, p.getStabilityBase());
        assertEquals(0.15, p.getStabilityStep());
        assertEquals(0.4, p.getWeightExplicitness());
        assertEquals(0.4, p.getWeightStability());
        assertEquals(0.2, p.getWeightConfidence());
        assertEquals(500, p.getTokenGuaranteed());
        assertEquals(1500, p.getTokenExtended());
        assertEquals(30, p.getCacheExpireMinutes());
        assertEquals(256, p.getCacheMaxSize());
    }

    @Test
    @DisplayName("PreferenceKeys 白名单/单值多值/标签 — 与 spec §7.2 一致")
    void preferenceKeys() {
        assertTrue(PreferenceKeys.isKnown("response_verbosity"));
        assertTrue(PreferenceKeys.isMultiValue("course_direction"));
        assertFalse(PreferenceKeys.isMultiValue("response_language"));
        assertEquals("回答语言", PreferenceKeys.LABELS.get("response_language"));
    }
}
