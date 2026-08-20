package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.constants.PreferenceKeys;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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

    @Test
    @DisplayName("episodic 默认 — writeHigh 0.7、权重 0.4/0.3/0.3、typeWeights、预算 1200、召回 5/0.30/10（spec §8.3）")
    void episodicDefaults() {
        var e = props.getEpisodic();
        assertEquals(0.7, e.getWriteHigh());
        assertEquals(0.4, e.getWeightExplicitness());
        assertEquals(0.3, e.getWeightConfidence());
        assertEquals(0.3, e.getWeightImportance());
        assertEquals(1200, e.getTokenBudget());
        assertEquals(5, e.getRecallTopK());
        assertEquals(0.30, e.getRecallMinScore());
        assertEquals(10, e.getPrefetchTopK());
        assertEquals(1.0, e.getTypeWeights().get("learning_goal"));
        assertEquals(0.95, e.getTypeWeights().get("resolved_question"));
        assertEquals(0.9, e.getTypeWeights().get("learning_progress"));
        assertEquals(0.8, e.getTypeWeights().get("personal_context"));
    }

    @Test
    @DisplayName("episodic Binder 绑定 yml 片段 — kebab-case→camelCase、type-weights Map 解析")
    void bindsYmlValues() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "memory.episodic.write-high", "0.7",
                "memory.episodic.recall-top-k", "5",
                "memory.episodic.type-weights.learning_goal", "1.0",
                "memory.episodic.type-weights.personal_context", "0.8"));
        MemoryProperties bound =
                new Binder(source).bind("memory", MemoryProperties.class).orElse(new MemoryProperties());
        assertEquals(0.7, bound.getEpisodic().getWriteHigh());
        assertEquals(5, bound.getEpisodic().getRecallTopK());
        assertTrue(bound.getEpisodic().getTypeWeights().containsKey("learning_goal"));
        assertEquals(1.0, bound.getEpisodic().getTypeWeights().get("learning_goal"));
    }
}
