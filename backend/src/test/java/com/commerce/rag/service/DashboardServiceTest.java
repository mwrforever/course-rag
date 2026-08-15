package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.UserFeedbackMapper;
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
    private ChatRunMapper chatRunMapper;

    @Mock
    private UserFeedbackMapper feedbackMapper;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor 生成全参构造器，直接构造注入 mock
        service = new DashboardService(documentMapper, chunkMapper, chatRunMapper, feedbackMapper);
    }

    @Test
    @DisplayName("dashboardStats — 文档总数与待修正分片数")
    void dashboardStats_counts() {
        when(documentMapper.selectCount(any())).thenReturn(10L);
        when(chunkMapper.selectCount(any())).thenReturn(3L);

        Map<String, Object> stats = service.dashboardStats();

        assertEquals(10L, stats.get("documentCount"));
        assertEquals(3L, stats.get("pendingChunkCount"));
    }

    @Test
    @DisplayName("feedbackStats — 今日会话数 + 点赞率")
    void feedbackStats_likeRate() {
        when(chatRunMapper.selectCount(any())).thenReturn(5L);
        // 第一次调用=总反馈数，第二次调用=点赞数（Mockito 序列返回值）
        when(feedbackMapper.selectCount(any())).thenReturn(4L, 3L);

        Map<String, Object> stats = service.feedbackStats("today");

        assertEquals(5L, stats.get("sessionCount"));
        assertEquals(0.75, stats.get("likeRate"));
    }

    @Test
    @DisplayName("feedbackStats — 0 反馈时点赞率为 0（无除零）")
    void feedbackStats_zeroFeedback_likeRateZero() {
        when(chatRunMapper.selectCount(any())).thenReturn(0L);
        when(feedbackMapper.selectCount(any())).thenReturn(0L, 0L);

        Map<String, Object> stats = service.feedbackStats("today");

        assertEquals(0L, stats.get("sessionCount"));
        assertEquals(0.0, stats.get("likeRate"));
    }

    @Test
    @DisplayName("feedbackTrend — 近 N 天每日反馈数，0 补位，升序")
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
}
