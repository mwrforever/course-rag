package com.commerce.rag.feedback.analyzer;

import com.commerce.rag.feedback.entity.FeedbackStats;
import com.commerce.rag.feedback.FeedbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 反馈质量分析器
 *
 * 分析用户反馈数据，发现知识库质量问题和优化方向：
 * - 各模式满意率趋势
 * - 低分回答集中分析（知识库缺口识别）
 * - 高频"未找到信息"场景统计
 */
@Slf4j
@Component
public class FeedbackAnalyzer {

    private final FeedbackService feedbackService;

    public FeedbackAnalyzer(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * 生成反馈分析报告
     *
     * @return 分析报告文本
     */
    public String generateReport() {
        List<FeedbackStats> stats = feedbackService.getStats();

        StringBuilder report = new StringBuilder();
        report.append("=== 用户反馈分析报告 ===\n\n");

        long totalFeedbacks = stats.stream().mapToLong(FeedbackStats::totalCount).sum();
        report.append(String.format("总反馈数: %d\n\n", totalFeedbacks));

        for (FeedbackStats stat : stats) {
            double positiveRate = stat.totalCount() > 0
                    ? (double) stat.positiveCount() / stat.totalCount() * 100 : 0;
            double negativeRate = stat.totalCount() > 0
                    ? (double) stat.negativeCount() / stat.totalCount() * 100 : 0;

            report.append(String.format(
                    "[%s] 总数=%d, 平均分=%.2f, 好评率=%.1f%%, 差评率=%.1f%%\n",
                    stat.intentType(), stat.totalCount(), stat.avgRating(),
                    positiveRate, negativeRate
            ));
        }

        // 检查低分反馈
        long lowRatedCount = feedbackService.getLowRatedFeedbacks(1000).size();
        if (lowRatedCount > 0) {
            report.append(String.format("\n待复核低分回答数: %d\n", lowRatedCount));
        }

        log.info("反馈分析报告生成完成");
        return report.toString();
    }
}
