package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.mapper.UserFeedbackMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UserFeedbackService 单元测试 —— Mock UserFeedbackMapper
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

    @InjectMocks
    private UserFeedbackService feedbackService;

    @Test
    @DisplayName("create 新建反馈 — 不存在时插入")
    void create_newFeedback_inserts() {
        // 查询不存在
        when(feedbackMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(feedbackMapper.insert((UserFeedback) any())).thenReturn(1);

        UserFeedback result = feedbackService.create(200L, 1L, 100L, true, "TECHNICAL_QA");

        assertNotNull(result);
        assertEquals(1L, result.getSessionId());
        assertEquals(100L, result.getMessageId());
        assertTrue(result.getIsLiked());
        assertEquals("TECHNICAL_QA", result.getIntentType());
        verify(feedbackMapper).insert((UserFeedback) any());
    }

    @Test
    @DisplayName("create 更新已有反馈 — 存在时更新")
    void create_existingFeedback_updates() {
        UserFeedback existing = new UserFeedback();
        existing.setId(1L);
        existing.setSessionId(1L);
        existing.setMessageId(100L);
        existing.setIsLiked(false);

        when(feedbackMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(feedbackMapper.update(any(), any())).thenReturn(1);

        UserFeedback result = feedbackService.create(200L, 1L, 100L, true, "COURSE_INFO");

        assertTrue(result.getIsLiked());
        assertEquals("COURSE_INFO", result.getIntentType());
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
        type1.setIntentType("TECHNICAL_QA");
        UserFeedback type2 = new UserFeedback();
        type2.setIntentType("COURSE_INFO");
        when(feedbackMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(type1, type2));

        // Mock 统计赞/踩数
        when(feedbackMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(10L) // TECHNICAL_QA 赞
                .thenReturn(2L) // TECHNICAL_QA 踩
                .thenReturn(5L) // COURSE_INFO 赞
                .thenReturn(1L); // COURSE_INFO 踩

        var stats = feedbackService.findStats();

        assertEquals(2, stats.size());
        verify(feedbackMapper, times(4)).selectCount(any());
    }

    @Test
    @DisplayName("create → 携带 userId 落库（归属字段）")
    void create_withUserId_persistsOwnership() {
        // 无已有反馈时新建
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        UserFeedback result = feedbackService.create(200L, 1L, 10L, true, "knowledge_question");

        ArgumentCaptor<UserFeedback> captor = ArgumentCaptor.forClass(UserFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        assertEquals(200L, captor.getValue().getUserId());
        assertEquals(10L, captor.getValue().getMessageId());
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
