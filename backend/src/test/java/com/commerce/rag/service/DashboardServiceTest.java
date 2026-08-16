package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.mapper.SysUserMapper;
import com.commerce.rag.mapper.UserFeedbackMapper;
import com.commerce.rag.service.impl.DashboardServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IDashboardService 单元测试 —— 统计口径（P2-2；2026-08-15 用户裁决：全局统计，不区分教师/超管视角）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IDashboardService 统计测试")
class DashboardServiceTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMapper chunkMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private UserFeedbackMapper feedbackMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    private IDashboardService service;

    /** Dashboard 统计缓存（真实 Caffeine 实例；JUnit 每测试方法新实例，缓存互不串扰） */
    private final Cache<String, Object> dashboardStatsCache =
            Caffeine.newBuilder().build();

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor 生成全参构造器，直接构造注入 mock + 真实缓存
        service = new DashboardServiceImpl(
                documentMapper, chunkMapper, knowledgeBaseMapper, feedbackMapper, sysUserMapper, dashboardStatsCache);
    }

    @Test
    @DisplayName("dashboardStats — 全局统计 documentCount/pendingChunkCount/knowledgeBaseCount（无 created_by 过滤）")
    void dashboardStats_globalCounts() {
        when(documentMapper.selectCount(any())).thenReturn(10L);
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(2L);

        Map<String, Object> stats = service.dashboardStats();

        assertEquals(10L, stats.get("documentCount"));
        assertEquals(3L, stats.get("pendingChunkCount"));
        assertEquals(2L, stats.get("knowledgeBaseCount"));
        // 全局口径：待修正分片数走 lambda 全局 count，不再有教师子查询计数
        verify(chunkMapper).selectCount(any());
    }

    @Test
    @DisplayName("feedbackStats — 全局学生数 + 反馈数 + 点赞率（无 created_by 过滤）")
    void feedbackStats_globalCounts() {
        when(sysUserMapper.selectCount(any())).thenReturn(30L);
        when(feedbackMapper.selectFeedbackStatsByPeriod(any()))
                .thenReturn(Map.of("total_count", 4L, "liked_count", 3L));

        Map<String, Object> stats = service.feedbackStats("today");

        assertEquals(30L, stats.get("studentCount"));
        assertEquals(4L, stats.get("feedbackCount"));
        assertEquals(0.75, stats.get("likeRate"));
        // 全局口径：统计 SQL 不传 createdBy
        verify(feedbackMapper).selectFeedbackStatsByPeriod(any());
    }

    @Test
    @DisplayName("feedbackStats — 0 反馈时点赞率为 0（无除零）")
    void feedbackStats_zeroFeedback_likeRateZero() {
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(feedbackMapper.selectFeedbackStatsByPeriod(any()))
                .thenReturn(Map.of("total_count", 0L, "liked_count", 0L));

        Map<String, Object> stats = service.feedbackStats("today");

        assertEquals(0L, stats.get("studentCount"));
        assertEquals(0.0, stats.get("likeRate"));
    }

    @Test
    @DisplayName("feedbackTrend — 近 N 天每日反馈数，0 补位，升序（全局口径）")
    void feedbackTrend_zeroFillAscending() {
        // mapper 分组统计返回 2 条记录（间隔日期），其余天补 0（SQL 在 UserFeedbackMapper.xml）
        when(feedbackMapper.selectDailyFeedbackCount(any()))
                .thenReturn(List.of(
                        Map.of("d", LocalDate.now().minusDays(4).toString(), "c", 2L),
                        Map.of("d", LocalDate.now().toString(), "c", 3L)));

        List<Map<String, Object>> trend = service.feedbackTrend(7);

        assertEquals(7, trend.size());
        assertEquals(LocalDate.now().minusDays(6).toString(), trend.get(0).get("date"));
        assertEquals(0L, trend.get(0).get("count"));
        assertEquals(2L, trend.get(2).get("count"));
        assertEquals(3L, trend.get(6).get("count"));
    }

    @Test
    @DisplayName("feedbackTrend — days 钳位（1~90），负数按 1")
    void feedbackTrend_clampDays() {
        when(feedbackMapper.selectDailyFeedbackCount(any())).thenReturn(List.of());

        assertEquals(1, service.feedbackTrend(0).size());
        assertEquals(90, service.feedbackTrend(999).size());
    }

    // ==================== perf P2-3 统计缓存 ====================

    @Test
    @DisplayName("dashboardStats — 同参数二次调用命中缓存（mapper 只查一次）")
    void dashboardStats_secondCall_hitsCache() {
        when(documentMapper.selectCount(any())).thenReturn(10L);
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(2L);

        Map<String, Object> first = service.dashboardStats();
        Map<String, Object> second = service.dashboardStats();

        assertEquals(first, second, "同参数两次调用返回一致");
        // 二次调用同参数命中缓存：三个统计各自只查一次 DB
        verify(documentMapper, times(1)).selectCount(any());
        verify(chunkMapper, times(1)).selectCount(any());
        verify(knowledgeBaseMapper, times(1)).selectCount(any());
    }

    @Test
    @DisplayName("统计缓存 — 三个端点键互不串扰（dashboardStats / feedbackStats / feedbackTrend 各自独立）")
    void statsCache_keysIsolatedAcrossEndpoints() {
        when(documentMapper.selectCount(any())).thenReturn(10L);
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(2L);
        when(sysUserMapper.selectCount(any())).thenReturn(1L);
        when(feedbackMapper.selectFeedbackStatsByPeriod(any()))
                .thenReturn(Map.of("total_count", 1L, "liked_count", 1L));
        when(feedbackMapper.selectDailyFeedbackCount(any())).thenReturn(List.of());

        // 三端点各调一次 + dashboardStats 再调一次（命中自身缓存，不触发其他端点失效）
        service.dashboardStats();
        service.feedbackStats("today");
        service.feedbackTrend(7);
        service.dashboardStats();

        // dashboardStats 二次调用命中缓存：三个 count 各只查一次
        verify(documentMapper, times(1)).selectCount(any());
        verify(chunkMapper, times(1)).selectCount(any());
        verify(knowledgeBaseMapper, times(1)).selectCount(any());
        // 其他端点各自只查一次（键隔离，互不命中）
        verify(sysUserMapper, times(1)).selectCount(any());
        verify(feedbackMapper, times(1)).selectFeedbackStatsByPeriod(any());
        verify(feedbackMapper, times(1)).selectDailyFeedbackCount(any());
    }
}
