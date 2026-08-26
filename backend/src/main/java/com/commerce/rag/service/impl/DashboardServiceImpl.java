package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.mapper.SysUserMapper;
import com.commerce.rag.mapper.UserFeedbackMapper;
import com.commerce.rag.service.IDashboardService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 管理端 Dashboard 统计服务（P2-2 契约对齐 + 2026-08-15 用户裁决：全局统计口径）
 *
 * <p>统计口径（用户 2026-08-15 定稿，替代原「全局会话数」口径——会话数无业务意义；
 * 2026-08-15 晚再裁决：所有人都能看到，不区分是谁的学生，TEACHER 与 SUPER_ADMIN 所见一致）：
 * <ul>
 *   <li>dashboard/stats：文档总数 + 待修正分片数 + 知识库数（全局，无 created_by 过滤）</li>
 *   <li>feedback/stats?period=：周期内学生数 + 反馈数 + 点赞率（全局）</li>
 *   <li>feedback/trend?days=：近 N 天每日反馈数（0 补位升序，全局）</li>
 * </ul>
 *
 * <p>缓存（2026-08-25 宪法 A.5.4 分层）：三端点为简单场景，走 {@code @Cacheable} 注解 +
 * RedisCacheManager（缓存区 TTL 经 cache.ttl.dashboard-stats 配置化，60 秒兜底）；
 * 写方（文档上传/ETL 终态/反馈提交/用户变更）经 DashboardCacheEvictor 统一失效（先写 DB 后失效铁律）。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements IDashboardService {

    private final DocumentMapper documentMapper;

    private final DocumentChunkMapper chunkMapper;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final UserFeedbackMapper feedbackMapper;

    private final SysUserMapper sysUserMapper;

    /**
     * 文档/分片/知识库统计（全局口径，无 created_by 过滤——用户 2026-08-15 裁决）
     *
     * <p>统计结果缓存 60 秒（单键，全局唯一视图），文档上传/删除/重解析时由写方失效
     * （先写 DB 后失效铁律）。
     *
     * @return 统计结果 {documentCount, pendingChunkCount, knowledgeBaseCount}
     */
    @Cacheable(cacheNames = "dashboard:stats", key = "'stats'")
    public Map<String, Object> dashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        // 全局统计：文档总数（deleted=0 由 @TableLogic 自动过滤）
        stats.put("documentCount", documentMapper.selectCount(Wrappers.<Document>lambdaQuery()));
        // 全局统计：待修正分片数（correction_status=PENDING）
        stats.put(
                "pendingChunkCount",
                chunkMapper.selectCount(
                        Wrappers.<DocumentChunk>lambdaQuery().eq(DocumentChunk::getCorrectionStatus, "PENDING")));
        // 全局统计：知识库总数
        stats.put("knowledgeBaseCount", knowledgeBaseMapper.selectCount(Wrappers.<KnowledgeBase>lambdaQuery()));
        return stats;
    }

    /**
     * 周期内学生数 + 反馈数 + 点赞率（全局口径，无 created_by 过滤——用户 2026-08-15 裁决）
     *
     * <p>统计结果缓存 60 秒（键含 period），反馈提交/删除时由写方失效。
     *
     * @param period today/week/month（默认 today）
     * @return 统计结果 {studentCount, feedbackCount, likeRate}
     */
    @Cacheable(cacheNames = "dashboard:feedback-stats", key = "#period")
    public Map<String, Object> feedbackStats(String period) {
        LocalDateTime start = periodStart(period);
        Map<String, Object> stats = new LinkedHashMap<>();
        // 学生数：全部 STUDENT 角色用户（用户裁决：替代无业务意义的会话数）
        stats.put(
                "studentCount",
                sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getRole, "STUDENT")));
        // 反馈数 + 点赞数：单条 SQL（XML），全局统计
        Map<String, Object> feedbackRow = feedbackMapper.selectFeedbackStatsByPeriod(start);
        long total = feedbackRow != null && feedbackRow.get("total_count") != null
                ? ((Number) feedbackRow.get("total_count")).longValue()
                : 0L;
        long liked = feedbackRow != null && feedbackRow.get("liked_count") != null
                ? ((Number) feedbackRow.get("liked_count")).longValue()
                : 0L;
        stats.put("feedbackCount", total);
        // 总数 0 时点赞率 0（防除零）
        stats.put("likeRate", total > 0 ? (double) liked / total : 0.0);
        return stats;
    }

    /**
     * 近 N 天每日反馈数（0 补位，日期升序；全局口径——用户 2026-08-15 裁决）
     *
     * <p>统计结果缓存 60 秒（键含 days），反馈提交/删除时由写方失效。
     *
     * @param days 天数（1~90 钳位）
     * @return 每日反馈数列表 {date, count}，升序
     */
    @Cacheable(cacheNames = "dashboard:feedback-trend", key = "#days")
    public List<Map<String, Object>> feedbackTrend(int days) {
        int clamped = Math.max(1, Math.min(days, 90));
        LocalDate startDate = LocalDate.now().minusDays(clamped - 1L);
        // 分组聚合 SQL 走 mapper XML 映射（宪法：禁止业务层拼接 SQL 字符串；全局统计无 created_by 过滤）
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
