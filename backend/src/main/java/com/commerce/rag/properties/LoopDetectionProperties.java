package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * F#3 死循环检测配置属性 —— 双层检测策略
 *
 * <p><b>Layer 1（hash）：</b>SHA-256 hash 滑动窗口，检测模型输出重复内容。
 * 当窗口内相同 hash 出现次数达到 {@code warn} 时告警，达到 {@code hardStop} 时触发软停。
 *
 * <p><b>Layer 2（per-tool）：</b>按工具分别计数调用频率。
 * 每个工具可单独配置 {@code warn}/{@code hardStop} 阈值，
 * 未在 {@code overrides} 中配置的工具 fallback 到 {@code default} 阈值。
 * Override 未配的单个字段也 fallback 到 default 对应字段。
 *
 * <p>绑定 YAML 路径：{@code rag.loop-detection.*}
 *
 * @author commerce-rag
 */
@Validated
@ConfigurationProperties(prefix = "rag.loop-detection")
public record LoopDetectionProperties(

        /** Layer 1: hash 滑动窗口配置 */
        HashConfig hash,

        /** Layer 2: per-tool 调用频率配置 */
        PerToolConfig perTool) {

    /**
     * 提供默认值，防止 YAML 配置缺失时 NPE
     */
    public LoopDetectionProperties {
        if (hash == null) {
            hash = new HashConfig(20, 3, 5);
        }
        if (perTool == null) {
            perTool = new PerToolConfig(new ToolThreshold(15, 25), new HashMap<>());
        }
    }

    /**
     * Layer 1: hash 滑动窗口配置
     *
     * @param windowSize 滑动窗口大小（保留最近 N 条模型输出的 hash）
     * @param warn       触发警告的重复次数
     * @param hardStop   触发软停的重复次数
     */
    public record HashConfig(@Min(1) int windowSize, @Min(1) int warn, @Min(1) int hardStop) {
        public HashConfig {
            if (windowSize <= 0) windowSize = 20;
            if (warn <= 0) warn = 3;
            if (hardStop <= 0) hardStop = 5;
        }
    }

    /**
     * Layer 2: per-tool 调用频率配置
     *
     * @param defaultThreshold 默认阈值（所有工具的兜底值）
     * @param overrides        按工具名覆盖的阈值（key = 工具名）
     */
    public record PerToolConfig(

            /** 默认阈值，YAML 键为 "default"（Java 关键字，用 @Name 绑定） */
            @Name("default") ToolThreshold defaultThreshold,

            /** 按工具名覆盖的阈值映射 */
            Map<String, ToolThreshold> overrides) {

        public PerToolConfig {
            if (defaultThreshold == null) {
                defaultThreshold = new ToolThreshold(15, 25);
            }
            if (overrides == null) {
                overrides = new HashMap<>();
            }
        }
    }

    /**
     * 工具调用频率阈值
     *
     * @param warn     触发警告的调用次数
     * @param hardStop 触发软停的调用次数
     */
    public record ToolThreshold(@Min(0) int warn, @Min(0) int hardStop) {
        public ToolThreshold {
            if (warn < 0) warn = 15;
            if (hardStop < 0) hardStop = 25;
        }
    }

    /**
     * 获取指定工具的告警/硬停阈值
     *
     * <p>查找优先级：
     * <ol>
     *   <li>从 {@code overrides} 中查找工具名对应的阈值</li>
     *   <li>override 中未配的字段（值为 0）fallback 到 default 对应字段</li>
     *   <li>工具名未命中 overrides，直接返回 default 阈值</li>
     * </ol>
     *
     * @param toolName 工具名称
     * @return 该工具的有效阈值
     */
    public ToolThreshold getThreshold(String toolName) {
        ToolThreshold defaultThreshold = perTool().defaultThreshold();
        if (perTool() != null && perTool().overrides() != null) {
            ToolThreshold override = perTool().overrides().get(toolName);
            if (override != null) {
                // 字段级 fallback：override 中值为 0 的字段用 default 的值
                int warn = override.warn() > 0 ? override.warn() : defaultThreshold.warn();
                int hardStop = override.hardStop() > 0 ? override.hardStop() : defaultThreshold.hardStop();
                return new ToolThreshold(warn, hardStop);
            }
        }
        return defaultThreshold;
    }
}
