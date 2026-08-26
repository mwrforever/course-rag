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

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor 生成全参构造器，直接构造注入 mock（缓存走 @Cacheable 注解，单测不生效）
        service = new DashboardServiceImpl(
                documentMapper, chunkMapper, knowledgeBaseMapper, feedbackMapper, sysUserMapper);
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
    // 缓存命中/键隔离语义已随 @Cacheable 注解化迁移至集成测试
    // （CacheIntegrationTest.dashboardStats_annotationCacheAndEvict：真实 Redis 验证注解写入/失效与 TTL），
    // 单测（无 Spring 代理）不再覆盖缓存行为——平铺调用每次都直查 DB。
}
