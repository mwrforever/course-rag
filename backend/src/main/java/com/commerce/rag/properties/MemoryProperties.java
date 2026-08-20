package com.commerce.rag.properties;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆体系配置（spec §7.8/§8.3 —— 阈值/权重/预算/曲线全部配置化，零硬编码）
 *
 * <p>绑定 YAML 路径 {@code memory.*}：extraction=提取流水线（模型/防抖/超时/线程），
 * preference=偏好决策阈值权重（本计划 4/5 消费）；episodic 段留待计划 5/5。
 *
 * @author commerce-rag
 */
@Data
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    /** 偏好提取流水线配置 */
    private Extraction extraction = new Extraction();

    /** 偏好记忆决策配置 */
    private Preference preference = new Preference();

    @Data
    public static class Extraction {
        /** 偏好/记忆提取模型（spec §7.6：qwen3.7-flash） */
        private String model = "qwen3.7-flash";
        /** run 完成后投递的防抖窗口（秒），同用户窗口内消息合并 */
        private int debounceWindowSeconds = 30;
        /** 提取 LLM 调用超时（毫秒），超时丢弃本批不重试 */
        private long timeoutMs = 10_000L;
        /** 防抖调度线程数（独立小线程池，不占主链路线程） */
        private int threads = 2;
    }

    @Data
    public static class Preference {
        /** write_score 直接写阈值（≥ 写 active），spec §7.3 */
        private double writeHigh = 0.75;
        /** write_score 观察池阈值（< writeHigh 且 ≥ 此值进 observing），spec §7.3 */
        private double observeLow = 0.50;
        /** 单值冲突「直接 UPDATE」的 explicitness 门槛（≥ 0.8 明确改变），spec §7.5 */
        private double explicitUpdate = 0.80;
        /** 观察晋升最低计数（count≥N 且 write_score≥promoteMinScore → active），spec §7.5 */
        private int promoteMinCount = 5;
        /** 观察晋升最低 write_score（spec §7.5：晋升统一用 write_score 一个标尺） */
        private double promoteMinScore = 0.75;
        /** stability 曲线基数 min(1, base + count*step)，spec §7.3 */
        private double stabilityBase = 0.1;
        /** stability 曲线步进（1 次=0.25，3 次=0.55，5 次=0.85） */
        private double stabilityStep = 0.15;
        /** write_score 权重：explicitness */
        private double weightExplicitness = 0.4;
        /** write_score 权重：stability */
        private double weightStability = 0.4;
        /** write_score 权重：confidence */
        private double weightConfidence = 0.2;
        /** 注入预算：硬偏好保底 500 token（先注入），spec §7.8 */
        private int tokenGuaranteed = 500;
        /** 注入预算：其余偏好按 write_score 降序 1500 token（用完截断），spec §7.8 */
        private int tokenExtended = 1500;
        /** 偏好块缓存（冻结）失效分钟数（spec §7.8 防 prefix cache 破坏 30min） */
        private int cacheExpireMinutes = 30;
        /** 偏好块 Caffeine 缓存条数 */
        private int cacheMaxSize = 256;
        /** 枚举型 key 的 value 归一化词表（key → {原始值 → 规范值}），spec §7.4-② */
        private Map<String, Map<String, String>> valueSynonyms = new HashMap<>();
    }
}
