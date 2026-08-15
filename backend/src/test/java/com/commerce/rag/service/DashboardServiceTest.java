package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.mapper.SysUserMapper;
import com.commerce.rag.mapper.UserFeedbackMapper;
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
 * DashboardService 单元测试 —— 统计口径（P2-2）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService 统计测试")
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

    private DashboardService service;

    /** Dashboard 统计缓存（真实 Caffeine 实例；JUnit 每测试方法新实例，缓存互不串扰） */
    private final Cache<String, Object> dashboardStatsCache =
            Caffeine.newBuilder().build();

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor 生成全参构造器，直接构造注入 mock + 真实缓存
        service = new DashboardService(
                documentMapper, chunkMapper, knowledgeBaseMapper, feedbackMapper, sysUserMapper, dashboardStatsCache);
    }

    @Test
    @DisplayName("dashboardStats — 超管统计全部（documentCount/pendingChunkCount/knowledgeBaseCount）")
    void dashboardStats_admin_seesAll() {
        when(documentMapper.selectCount(any())).thenReturn(10L);
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(2L);

        Map<String, Object> stats = service.dashboardStats(100L, true);

        assertEquals(10L, stats.get("documentCount"));
        assertEquals(3L, stats.get("pendingChunkCount"));
        assertEquals(2L, stats.get("knowledgeBaseCount"));
        // 超管不走教师子查询计数
        verify(chunkMapper, never()).selectPendingChunkCountByTeacher(any());
    }

    @Test
    @DisplayName("dashboardStats — 教师仅统计自己创建的数据（created_by 隔离，用户 2026-08-15 裁决）")
    void dashboardStats_teacher_seesOwnData() {
        when(documentMapper.selectCount(any())).thenReturn(4L);
        when(chunkMapper.selectPendingChunkCountByTeacher(100L)).thenReturn(1L);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(1L);

        Map<String, Object> stats = service.dashboardStats(100L, false);

        assertEquals(4L, stats.get("documentCount"));
        assertEquals(1L, stats.get("pendingChunkCount"));
        assertEquals(1L, stats.get("knowledgeBaseCount"));
    }

    @Test
    @DisplayName("feedbackStats — 超管统计全部学生数 + 反馈数 + 点赞率")
    void feedbackStats_admin_counts() {
        when(sysUserMapper.selectCount(any())).thenReturn(30L);
        when(feedbackMapper.selectFeedbackStatsByPeriod(any(), isNull()))
                .thenReturn(Map.of("total_count", 4L, "liked_count", 3L));

        Map<String, Object> stats = service.feedbackStats("today", 100L, true);

        assertEquals(30L, stats.get("studentCount"));
        assertEquals(4L, stats.get("feedbackCount"));
        assertEquals(0.75, stats.get("likeRate"));
    }

    @Test
    @DisplayName("feedbackStats — 教师统计自己学生（createdBy 传入子查询）")
    void feedbackStats_teacher_filtersByCreatedBy() {
        when(sysUserMapper.selectCount(any())).thenReturn(5L);
        when(feedbackMapper.selectFeedbackStatsByPeriod(any(), eq(100L)))
                .thenReturn(Map.of("total_count", 4L, "liked_count", 3L));

        Map<String, Object> stats = service.feedbackStats("today", 100L, false);

        assertEquals(5L, stats.get("studentCount"));
        assertEquals(4L, stats.get("feedbackCount"));
        assertEquals(0.75, stats.get("likeRate"));
        // 教师过滤必须传入 createdBy（而非 null）
        verify(feedbackMapper).selectFeedbackStatsByPeriod(any(), eq(100L));
    }

    @Test
    @DisplayName("feedbackStats — 0 反馈时点赞率为 0（无除零）")
    void feedbackStats_zeroFeedback_likeRateZero() {
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(feedbackMapper.selectFeedbackStatsByPeriod(any(), isNull()))
                .thenReturn(Map.of("total_count", 0L, "liked_count", 0L));

        Map<String, Object> stats = service.feedbackStats("today", 100L, true);

        assertEquals(0L, stats.get("studentCount"));
        assertEquals(0.0, stats.get("likeRate"));
    }

    @Test
    @DisplayName("feedbackTrend — 近 N 天每日反馈数，0 补位，升序（超管 createdBy=null）")
    void feedbackTrend_zeroFillAscending() {
        // mapper 分组统计返回 2 条记录（间隔日期），其余天补 0（SQL 在 UserFeedbackMapper.xml）
        when(feedbackMapper.selectDailyFeedbackCount(any(), isNull()))
                .thenReturn(List.of(
                        Map.of("d", LocalDate.now().minusDays(4).toString(), "c", 2L),
                        Map.of("d", LocalDate.now().toString(), "c", 3L)));

        List<Map<String, Object>> trend = service.feedbackTrend(7, 100L, true);

        assertEquals(7, trend.size());
        assertEquals(LocalDate.now().minusDays(6).toString(), trend.get(0).get("date"));
        assertEquals(0L, trend.get(0).get("count"));
        assertEquals(2L, trend.get(2).get("count"));
        assertEquals(3L, trend.get(6).get("count"));
    }

    @Test
    @DisplayName("feedbackTrend — 教师传入 createdBy 过滤（自己学生的反馈）")
    void feedbackTrend_teacher_filtersByCreatedBy() {
        when(feedbackMapper.selectDailyFeedbackCount(any(), eq(100L))).thenReturn(List.of());

        service.feedbackTrend(7, 100L, false);

        verify(feedbackMapper).selectDailyFeedbackCount(any(), eq(100L));
    }

    @Test
    @DisplayName("feedbackTrend — days 钳位（1~90），负数按 1")
    void feedbackTrend_clampDays() {
        when(feedbackMapper.selectDailyFeedbackCount(any(), isNull())).thenReturn(List.of());

        assertEquals(1, service.feedbackTrend(0, 100L, true).size());
        assertEquals(90, service.feedbackTrend(999, 100L, true).size());
    }

    // ==================== perf P2-3 统计缓存 ====================

    @Test
    @DisplayName("dashboardStats — 同参数二次调用命中缓存（mapper 只查一次）")
    void dashboardStats_secondCall_hitsCache() {
        when(documentMapper.selectCount(any())).thenReturn(10L);
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(2L);

        Map<String, Object> first = service.dashboardStats(100L, true);
        Map<String, Object> second = service.dashboardStats(100L, true);

        assertEquals(first, second, "同参数两次调用返回一致");
        // 二次调用同参数命中缓存：三个统计各自只查一次 DB
        verify(documentMapper, times(1)).selectCount(any());
        verify(chunkMapper, times(1)).selectCount(any());
        verify(knowledgeBaseMapper, times(1)).selectCount(any());
    }

    @Test
    @DisplayName("dashboardStats — 不同参数键隔离（operatorId/isAdmin 任一不同互不命中）")
    void dashboardStats_keyIsolatedPerView() {
        when(documentMapper.selectCount(any())).thenReturn(10L);
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectPendingChunkCountByTeacher(anyLong())).thenReturn(1L);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(2L);

        // 三个不同键：超管 100 / 教师 100 / 教师 200，任一参数不同即重新查询
        service.dashboardStats(100L, true);
        service.dashboardStats(100L, false);
        service.dashboardStats(200L, false);

        verify(documentMapper, times(3)).selectCount(any());
        verify(chunkMapper, times(1)).selectCount(any());
        verify(chunkMapper, times(2)).selectPendingChunkCountByTeacher(anyLong());
        verify(knowledgeBaseMapper, times(3)).selectCount(any());
    }
}
