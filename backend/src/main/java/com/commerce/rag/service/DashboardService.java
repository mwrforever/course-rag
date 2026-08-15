package com.commerce.rag.service;

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
import com.github.benmanes.caffeine.cache.Cache;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 管理端 Dashboard 统计服务（P2-2 契约对齐 + 2026-08-15 用户裁决：教师数据隔离）
 *
 * <p>统计口径（用户 2026-08-15 裁决，替代原「全局会话数」口径——会话数无业务意义）：
 * <ul>
 *   <li>dashboard/stats：文档总数 + 待修正分片数 + 知识库数——教师视角 = 自己创建的数据，超管 = 全部</li>
 *   <li>feedback/stats?period=：周期内学生数 + 反馈数 + 点赞率——教师 = 自己创建的学生及其反馈，超管 = 全部</li>
 *   <li>feedback/trend?days=：近 N 天每日反馈数（0 补位升序）——教师 = 自己学生的反馈，超管 = 全部</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DocumentMapper documentMapper;

    private final DocumentChunkMapper chunkMapper;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final UserFeedbackMapper feedbackMapper;

    private final SysUserMapper sysUserMapper;

    /** Dashboard 统计缓存（TTL 60 秒，见 CacheConfig.dashboardStatsCache bean；文档上传/ETL 终态/反馈提交时由写方 invalidateAll） */
    @Qualifier("dashboardStatsCache")
    private final Cache<String, Object> dashboardStatsCache;

    /**
     * 文档/分片/知识库统计（教师视角按 created_by 隔离，超管不限制）
     *
     * <p>统计结果缓存 60 秒（键含 operatorId 与 isAdmin，教师/超管视角互不串扰），
     * 文档上传/删除/重解析时由写方失效。
     *
     * @param operatorId 当前操作者 ID（教师过滤用）
     * @param isAdmin    是否为超管（true=统计全部数据）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dashboardStats(Long operatorId, boolean isAdmin) {
        // 缓存读：键 = 操作者 + 视角（教师/超管数据隔离，键不同互不命中）
        String key = "dashboardStats:" + operatorId + ":" + isAdmin;
        Map<String, Object> cached = (Map<String, Object>) dashboardStatsCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        // 教师仅统计自己创建的文档；超管统计全部
        stats.put(
                "documentCount",
                documentMapper.selectCount(
                        isAdmin
                                ? Wrappers.<Document>lambdaQuery()
                                : Wrappers.<Document>lambdaQuery().eq(Document::getCreatedBy, operatorId)));
        stats.put(
                "pendingChunkCount",
                isAdmin
                        ? chunkMapper.selectCount(
                                Wrappers.<DocumentChunk>lambdaQuery().eq(DocumentChunk::getCorrectionStatus, "PENDING"))
                        : chunkMapper.selectPendingChunkCountByTeacher(operatorId));
        stats.put(
                "knowledgeBaseCount",
                knowledgeBaseMapper.selectCount(
                        isAdmin
                                ? Wrappers.<KnowledgeBase>lambdaQuery()
                                : Wrappers.<KnowledgeBase>lambdaQuery().eq(KnowledgeBase::getCreatedBy, operatorId)));
        // 缓存写：Caffeine 禁止 null 值，统计结果不可能为 null，守卫仅防御未来改动
        if (stats != null) {
            dashboardStatsCache.put(key, stats);
        }
        return stats;
    }

    /**
     * 周期内学生数 + 反馈数 + 点赞率（教师视角 = 自己创建的学生及其反馈，超管不限制）
     *
     * <p>统计结果缓存 60 秒（键含 period/operatorId/isAdmin），反馈提交/删除时由写方失效。
     *
     * @param period     today/week/month（默认 today）
     * @param operatorId 当前操作者 ID（教师过滤用）
     * @param isAdmin    是否为超管（true=统计全部数据）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> feedbackStats(String period, Long operatorId, boolean isAdmin) {
        // 缓存读：键 = 周期 + 操作者 + 视角
        String key = "feedbackStats:" + period + ":" + operatorId + ":" + isAdmin;
        Map<String, Object> cached = (Map<String, Object>) dashboardStatsCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        LocalDateTime start = periodStart(period);
        Map<String, Object> stats = new LinkedHashMap<>();
        // 学生数：教师 = 自己创建的学生；超管 = 全部学生（用户裁决：替代无业务意义的会话数）
        stats.put(
                "studentCount",
                sysUserMapper.selectCount(
                        isAdmin
                                ? Wrappers.<SysUser>lambdaQuery().eq(SysUser::getRole, "STUDENT")
                                : Wrappers.<SysUser>lambdaQuery()
                                        .eq(SysUser::getRole, "STUDENT")
                                        .eq(SysUser::getCreatedBy, operatorId)));
        // 反馈数 + 点赞数：单条 SQL（XML），教师按学生归属子查询隔离
        Map<String, Object> feedbackRow =
                feedbackMapper.selectFeedbackStatsByPeriod(start, isAdmin ? null : operatorId);
        long total = feedbackRow != null && feedbackRow.get("total_count") != null
                ? ((Number) feedbackRow.get("total_count")).longValue()
                : 0L;
        long liked = feedbackRow != null && feedbackRow.get("liked_count") != null
                ? ((Number) feedbackRow.get("liked_count")).longValue()
                : 0L;
        stats.put("feedbackCount", total);
        // 总数 0 时点赞率 0（防除零）
        stats.put("likeRate", total > 0 ? (double) liked / total : 0.0);
        // 缓存写：Caffeine 禁止 null 值，统计结果不可能为 null，守卫仅防御未来改动
        if (stats != null) {
            dashboardStatsCache.put(key, stats);
        }
        return stats;
    }

    /**
     * 近 N 天每日反馈数（0 补位，日期升序；教师视角 = 自己学生的反馈，超管不限制）
     *
     * <p>统计结果缓存 60 秒（键含 days/operatorId/isAdmin），反馈提交/删除时由写方失效。
     *
     * @param days       天数（1~90 钳位）
     * @param operatorId 当前操作者 ID（教师过滤用）
     * @param isAdmin    是否为超管（true=统计全部数据）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> feedbackTrend(int days, Long operatorId, boolean isAdmin) {
        // 缓存读：键 = 天数 + 操作者 + 视角
        String key = "feedbackTrend:" + days + ":" + operatorId + ":" + isAdmin;
        List<Map<String, Object>> cached = (List<Map<String, Object>>) dashboardStatsCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        int clamped = Math.max(1, Math.min(days, 90));
        LocalDate startDate = LocalDate.now().minusDays(clamped - 1L);
        // 分组聚合 SQL 走 mapper XML 映射（宪法：禁止业务层拼接 SQL 字符串；createdBy 教师隔离）
        List<Map<String, Object>> rows =
                feedbackMapper.selectDailyFeedbackCount(startDate.atStartOfDay(), isAdmin ? null : operatorId);

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
        // 缓存写：Caffeine 禁止 null 值，统计结果不可能为 null，守卫仅防御未来改动
        if (trend != null) {
            dashboardStatsCache.put(key, trend);
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
