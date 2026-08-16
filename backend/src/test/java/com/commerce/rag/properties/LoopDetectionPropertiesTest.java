package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LoopDetectionProperties 单元测试 —— 双层检测配置属性验证
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>默认值（null 参数时 fallback）</li>
 *   <li>getThreshold 命中/未命中 override</li>
 *   <li>override 字段级 fallback（hardStop=0 时回退到 default）</li>
 *   <li>HashConfig / ToolThreshold 零值/负值保护</li>
 * </ul>
 *
 * @author commerce-rag
 */
class LoopDetectionPropertiesTest {

    // ==================== 默认值测试 ====================

    @Test
    @DisplayName("默认值 — null 参数时使用默认配置")
    void defaultValues_nullParams_usesDefaults() {
        LoopDetectionProperties props = new LoopDetectionProperties(null, null);
        // hash 默认值
        assertEquals(20, props.hash().windowSize(), "默认 windowSize 应为 20");
        assertEquals(3, props.hash().warn(), "默认 warn 应为 3");
        assertEquals(5, props.hash().hardStop(), "默认 hardStop 应为 5");
        // perTool 默认值
        assertEquals(15, props.perTool().defaultThreshold().warn(), "默认 warn 应为 15");
        assertEquals(25, props.perTool().defaultThreshold().hardStop(), "默认 hardStop 应为 25");
        assertTrue(props.perTool().overrides().isEmpty(), "默认 overrides 应为空");
    }

    // ==================== getThreshold 测试 ====================

    @Test
    @DisplayName("getThreshold 命中 override — 返回 override 阈值")
    void getThreshold_hitOverride_returnsOverride() {
        LoopDetectionProperties props = new LoopDetectionProperties(
                new LoopDetectionProperties.HashConfig(20, 3, 5),
                new LoopDetectionProperties.PerToolConfig(
                        new LoopDetectionProperties.ToolThreshold(15, 25),
                        Map.of("searchKnowledge", new LoopDetectionProperties.ToolThreshold(25, 40))));
        LoopDetectionProperties.ToolThreshold threshold = props.getThreshold("searchKnowledge");
        assertEquals(25, threshold.warn(), "命中 override 时 warn 应为 25");
        assertEquals(40, threshold.hardStop(), "命中 override 时 hardStop 应为 40");
    }

    @Test
    @DisplayName("getThreshold 未命中 override — 返回 default 阈值")
    void getThreshold_missOverride_returnsDefault() {
        LoopDetectionProperties props = new LoopDetectionProperties(
                new LoopDetectionProperties.HashConfig(20, 3, 5),
                new LoopDetectionProperties.PerToolConfig(
                        new LoopDetectionProperties.ToolThreshold(15, 25),
                        Map.of("searchKnowledge", new LoopDetectionProperties.ToolThreshold(25, 40))));
        LoopDetectionProperties.ToolThreshold threshold = props.getThreshold("unknownTool");
        assertEquals(15, threshold.warn(), "未命中 override 时 warn 应回退到 default 的 15");
        assertEquals(25, threshold.hardStop(), "未命中 override 时 hardStop 应回退到 default 的 25");
    }

    @Test
    @DisplayName("getThreshold override 字段级 fallback — hardStop=0 时回退到 default")
    void getThreshold_partialOverride_fallbackToDefault() {
        // override 只配 warn=10, hardStop=0 → hardStop 应 fallback 到 default 的 25
        LoopDetectionProperties props = new LoopDetectionProperties(
                new LoopDetectionProperties.HashConfig(20, 3, 5),
                new LoopDetectionProperties.PerToolConfig(
                        new LoopDetectionProperties.ToolThreshold(15, 25),
                        Map.of("partialTool", new LoopDetectionProperties.ToolThreshold(10, 0))));
        LoopDetectionProperties.ToolThreshold threshold = props.getThreshold("partialTool");
        assertEquals(10, threshold.warn(), "override warn=10 应保留");
        assertEquals(25, threshold.hardStop(), "override hardStop=0 应 fallback 到 default 的 25");
    }

    // ==================== HashConfig 默认值测试 ====================

    @Test
    @DisplayName("HashConfig 默认值 — 0 值时使用默认配置")
    void hashConfig_defaults_zeroValues() {
        LoopDetectionProperties.HashConfig config = new LoopDetectionProperties.HashConfig(0, 0, 0);
        assertEquals(20, config.windowSize(), "windowSize=0 时应回退到默认 20");
        assertEquals(3, config.warn(), "warn=0 时应回退到默认 3");
        assertEquals(5, config.hardStop(), "hardStop=0 时应回退到默认 5");
    }

    // ==================== ToolThreshold 负值保护测试 ====================

    @Test
    @DisplayName("ToolThreshold 负值保护 — 负值时使用默认配置")
    void toolThreshold_negativeValues_usesDefaults() {
        LoopDetectionProperties.ToolThreshold threshold = new LoopDetectionProperties.ToolThreshold(-1, -1);
        assertEquals(15, threshold.warn(), "warn=-1 时应回退到默认 15");
        assertEquals(25, threshold.hardStop(), "hardStop=-1 时应回退到默认 25");
    }

    // ==================== PerToolConfig 默认值测试 ====================

    @Test
    @DisplayName("PerToolConfig 默认值 — null 参数时使用默认配置")
    void perToolConfig_defaults_nullParams() {
        LoopDetectionProperties.PerToolConfig config = new LoopDetectionProperties.PerToolConfig(null, null);
        assertEquals(15, config.defaultThreshold().warn(), "null defaultThreshold 时应回退到默认 15");
        assertEquals(25, config.defaultThreshold().hardStop(), "null defaultThreshold 时应回退到默认 25");
        assertNotNull(config.overrides(), "null overrides 应初始化为空 Map");
        assertTrue(config.overrides().isEmpty(), "默认 overrides 应为空");
    }
}
