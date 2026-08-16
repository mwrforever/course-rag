package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.commerce.rag.convert.UserFeedbackConverter;
import com.commerce.rag.convert.UserFeedbackConverterImpl;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.mapper.UserFeedbackMapper;
import com.commerce.rag.service.impl.UserFeedbackServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.UserFeedbackVO;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IUserFeedbackService 单元测试 —— Mock UserFeedbackMapper
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class UserFeedbackServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private UserFeedbackMapper feedbackMapper;

    /** 转换器用真实实现（MapStruct 生成类），转换行为由 UserFeedbackConverterTest 单独覆盖 */
    @Spy
    private UserFeedbackConverter feedbackConverter = new UserFeedbackConverterImpl();

    /** Dashboard 统计缓存（mock 即可，create/delete 的 invalidateAll 失效钩子验证用） */
    @Mock
    private Cache<String, Object> dashboardStatsCache;

    @InjectMocks
    private UserFeedbackServiceImpl feedbackService;

    @Test
    @DisplayName("create 新建反馈 — 不存在时插入并返回 VO")
    void create_newFeedback_inserts() {
        // 查询不存在
        when(feedbackMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(feedbackMapper.insert((UserFeedback) any())).thenReturn(1);

        UserFeedbackVO result = feedbackService.create(200L, 1L, 100L, true, "TECHNICAL_QA");

        assertNotNull(result);
        assertEquals(1L, result.sessionId());
        assertEquals(100L, result.messageId());
        assertTrue(result.isLiked());
        assertEquals("TECHNICAL_QA", result.intentType());
        verify(feedbackMapper).insert((UserFeedback) any());
    }

    @Test
    @DisplayName("create 更新已有反馈 — 存在时更新并返回 VO")
    void create_existingFeedback_updates() {
        UserFeedback existing = new UserFeedback();
        existing.setId(1L);
        existing.setSessionId(1L);
        existing.setMessageId(100L);
        existing.setIsLiked(false);

        when(feedbackMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(feedbackMapper.update(any(), any())).thenReturn(1);

        UserFeedbackVO result = feedbackService.create(200L, 1L, 100L, true, "COURSE_INFO");

        assertTrue(result.isLiked());
        assertEquals("COURSE_INFO", result.intentType());
        verify(feedbackMapper).update(any(), any());
        verify(feedbackMapper, never()).insert(any(UserFeedback.class));
    }

    @Test
    @DisplayName("delete 软删除反馈（带操作者 ID）")
    void delete_callsSoftDelete() {
        when(feedbackMapper.update(eq(null), any())).thenReturn(1);
        feedbackService.delete(1L, 100L);
        verify(feedbackMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("findStats 按意图分组统计")
    void findStats_groupsByIntentType() {
        // Mock 查询不重复的 intent_type
        UserFeedback type1 = new UserFeedback();
        // perf P3-1: 单条 GROUP BY 聚合 SQL（mapper XML）返回全部意图统计
        Map<String, Object> row1 = new java.util.HashMap<>();
        row1.put("intent_type", "TECHNICAL_QA");
        row1.put("liked_count", 10L);
        row1.put("disliked_count", 2L);
        Map<String, Object> row2 = new java.util.HashMap<>();
        row2.put("intent_type", "COURSE_INFO");
        row2.put("liked_count", 5L);
        row2.put("disliked_count", 1L);
        when(feedbackMapper.selectIntentStats()).thenReturn(List.of(row1, row2));

        var stats = feedbackService.findStats();

        assertEquals(2, stats.size());
        verify(feedbackMapper, times(1)).selectIntentStats();
        // 断言返回结构（intentType/likedCount/dislikedCount）
        Map<String, Object> tech = stats.stream()
                .filter(m -> "TECHNICAL_QA".equals(m.get("intentType")))
                .findFirst()
                .orElseThrow();
        assertEquals(10L, tech.get("likedCount"));
        assertEquals(2L, tech.get("dislikedCount"));
    }

    @Test
    @DisplayName("create → 携带 userId 落库（归属字段）")
    void create_withUserId_persistsOwnership() {
        // 无已有反馈时新建
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        UserFeedbackVO result = feedbackService.create(200L, 1L, 10L, true, "knowledge_question");

        ArgumentCaptor<UserFeedback> captor = ArgumentCaptor.forClass(UserFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        assertEquals(200L, captor.getValue().getUserId());
        assertEquals(10L, captor.getValue().getMessageId());
        // VO 出参与实体同字段（MapStruct 真实转换）
        assertEquals(200L, result.userId());
        assertEquals(10L, result.messageId());
    }

    @Test
    @DisplayName("create → 更新已有反馈时查询条件含 user_id（防止跨用户改他人反馈）")
    void create_updateExisting_queriesWithUserId() {
        UserFeedback existing = new UserFeedback();
        existing.setId(5L);
        existing.setUserId(200L);
        when(feedbackMapper.selectOne(any())).thenReturn(existing);

        feedbackService.create(200L, 1L, 10L, false, null);

        // 查询 wrapper 条件应含 user_id=200 与 message_id=10（跨用户反馈不会命中）
        ArgumentCaptor<LambdaQueryWrapper<UserFeedback>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(feedbackMapper).selectOne(captor.capture());
        String sqlSegment = captor.getValue().getCustomSqlSegment();
        assertTrue(sqlSegment.contains("user_id"));
        assertTrue(sqlSegment.contains("message_id"));
        // 更新走 update 而非 insert
        verify(feedbackMapper).update(any(), any());
        verify(feedbackMapper, never()).insert(any(UserFeedback.class));
    }
}
