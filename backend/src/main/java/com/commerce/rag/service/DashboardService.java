package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.UserFeedbackMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 管理端 Dashboard 统计服务（P2-2 契约对齐：前端设计文档 :783-786 三统计接口）
 *
 * <p>统计口径（spec §2.3 定义，前端文档未定义返回 schema）：
 * <ul>
 *   <li>dashboard/stats：文档总数 + 待修正分片数（correction_status=PENDING）</li>
 *   <li>feedback/stats?period=：周期内会话数 + 点赞率（period ∈ today/week/month）</li>
 *   <li>feedback/trend?days=：近 N 天每日反馈数（0 补位升序）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final DocumentMapper documentMapper;

    private final DocumentChunkMapper chunkMapper;

    private final ChatRunMapper chatRunMapper;

    private final UserFeedbackMapper feedbackMapper;

    /** 文档总数 + 待修正分片数（简单计数走 MP Wrappers 链式） */
    public Map<String, Object> dashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("documentCount", documentMapper.selectCount(Wrappers.<Document>lambdaQuery()));
        stats.put(
                "pendingChunkCount",
                chunkMapper.selectCount(
                        Wrappers.<DocumentChunk>lambdaQuery().eq(DocumentChunk::getCorrectionStatus, "PENDING")));
        return stats;
    }

    /**
     * 周期内会话数 + 点赞率
     *
     * @param period today/week/month（默认 today）
     */
    public Map<String, Object> feedbackStats(String period) {
        LocalDateTime start = periodStart(period);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put(
                "sessionCount",
                chatRunMapper.selectCount(Wrappers.<ChatRun>lambdaQuery().ge(ChatRun::getCreatedAt, start)));
        long total =
                feedbackMapper.selectCount(Wrappers.<UserFeedback>lambdaQuery().ge(UserFeedback::getCreatedAt, start));
        long liked = feedbackMapper.selectCount(Wrappers.<UserFeedback>lambdaQuery()
                .ge(UserFeedback::getCreatedAt, start)
                .eq(UserFeedback::getIsLiked, true));
        // 总数 0 时点赞率 0（防除零）
        stats.put("likeRate", total > 0 ? (double) liked / total : 0.0);
        return stats;
    }

    /**
     * 近 N 天每日反馈数（0 补位，日期升序）
     *
     * @param days 天数（1~90 钳位）
     */
    public List<Map<String, Object>> feedbackTrend(int days) {
        int clamped = Math.max(1, Math.min(days, 90));
        LocalDate startDate = LocalDate.now().minusDays(clamped - 1L);
        // 分组聚合 SQL 走 mapper XML 映射（宪法：禁止业务层拼接 SQL 字符串）
        List<Map<String, Object>> rows = feedbackMapper.selectDailyFeedbackCount(startDate.atStartOfDay());

        Map<String, Long> countByDate = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            countByDate.put(String.valueOf(row.get("d")), ((Number) row.get("c")).longValue());
        }
        // 0 补位：近 N 天逐日填充
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < clamped; i++) {
            String date = startDate.plusDays(i).toString();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", date);
            entry.put("count", countByDate.getOrDefault(date, 0L));
            trend.add(entry);
        }
        return trend;
    }

    /** period → 起始时间（today=当天 0 点、week=近 7 天、month=近 30 天，默认 today） */
    private LocalDateTime periodStart(String period) {
        LocalDate today = LocalDate.now();
        return switch (period == null ? "today" : period) {
            case "week" -> today.minusDays(6).atStartOfDay();
            case "month" -> today.minusDays(29).atStartOfDay();
            default -> today.atStartOfDay();
        };
    }
}
