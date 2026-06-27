package com.commerce.rag.retrieval.scoring;

import com.commerce.rag.config.RetrievalConfigProperties;
import org.springframework.stereotype.Component;

/**
 * 多因子综合评分计算器
 *
 * 将检索分数与热度加权组合为最终分数。
 *
 * 计算公式：
 * 1. semantic = alpha_prop * child_score + (1 - alpha_prop) * parent_score
 * 2. hotness = sigmoid(log1p(active_count)) * exp(-lambda * age_days)
 * 3. final = (1 - alpha_hot) * semantic + alpha_hot * hotness
 *
 * 当前简化版：parent_score 暂未接入（需要层级检索支持），
 * 热度因子通过 active_count 体现。
 */
@Component
public class FinalScoreCalculator {

    private final RetrievalConfigProperties config;

    public FinalScoreCalculator(RetrievalConfigProperties config) {
        this.config = config;
    }

    /**
     * 计算最终综合分数（简化版，不含父分传播和热度）
     *
     * 当前直接使用原始检索分数，后续接入完整多因子评分时扩展此方法。
     *
     * @param rawScore 原始检索分数（RRF 或 rerank 后的分数）
     * @return 最终分数
     */
    public double calculate(double rawScore) {
        // 当前简化：直接返回原始分数
        // 完整版需要传入 active_count 和 updated_at
        if (!Double.isFinite(rawScore)) return 0.0;
        return Math.max(0.0, rawScore);
    }

    /**
     * 计算完整多因子评分
     *
     * @param childScore   子节点分数（检索分数）
     * @param parentScore  父节点分数（目录级别，可为 null）
     * @param activeCount  访问热度计数
     * @param ageDays      内容年龄（天），null 表示未知
     * @return 最终综合分数
     */
    public double calculateFull(double childScore, Double parentScore,
                                 int activeCount, Double ageDays) {
        // ① 传播：父分按 1-α_prop 加权
        double semantic;
        if (parentScore != null && config.getScorePropagationAlpha() < 1.0) {
            semantic = config.getScorePropagationAlpha() * childScore
                    + (1 - config.getScorePropagationAlpha()) * parentScore;
        } else {
            semantic = childScore;
        }

        // ② 热度
        double hotness = 0.0;
        if (config.getHotnessAlpha() > 0 && ageDays != null) {
            double freq = 1.0 / (1.0 + Math.exp(-Math.log1p(activeCount)));
            double recency = Math.exp(-Math.log(2) / config.getHalfLifeDays() * ageDays);
            hotness = freq * recency;
        }

        // ③ 最终分数
        double alphaHot = config.getHotnessAlpha();
        double finalScore = (1 - alphaHot) * semantic + alphaHot * hotness;
        return Double.isFinite(finalScore) ? finalScore : 0.0;
    }
}
