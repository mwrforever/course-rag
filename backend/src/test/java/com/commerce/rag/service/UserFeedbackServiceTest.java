package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
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
import org.mockito.InOrder;
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

    /** 雪花 ID 生成器（MP 自动装配 bean；自定义 upsert SQL 不走 ASSIGN_ID 自动填充，service 显式取号） */
    @Mock
    private IdentifierGenerator identifierGenerator;

    /** 转换器用真实实现（MapStruct 生成类），转换行为由 UserFeedbackConverterTest 单独覆盖 */
    @Spy
    private UserFeedbackConverter feedbackConverter = new UserFeedbackConverterImpl();

    /** Dashboard 统计缓存（mock 即可，create/delete 的 invalidateAll 失效钩子验证用） */
    @Mock
    private Cache<String, Object> dashboardStatsCache;

    @InjectMocks
    private UserFeedbackServiceImpl feedbackService;

    // ==================== P1-5：ON CONFLICT 单条 upsert ====================

    @Test
    @DisplayName("P1-5 create — 单条 upsert 落库（不再 selectOne 预查 + insert/update 两往返）")
    void create_upsert_singleRoundTrip() {
        // RETURNING 行：落库后最终状态（id 由 DB 返回）
        UserFeedback saved = new UserFeedback();
        saved.setId(9L);
        saved.setUserId(200L);
        saved.setSessionId(1L);
        saved.setMessageId(100L);
        saved.setIsLiked(true);
        saved.setIntentType("TECHNICAL_QA");
        when(identifierGenerator.nextId(any())).thenReturn(123L);
        when(feedbackMapper.upsertFeedback(any())).thenReturn(saved);

        UserFeedbackVO result = feedbackService.create(200L, 1L, 100L, true, "TECHNICAL_QA");

        // VO 直接由 RETURNING 行转换（本次落库状态的语义与原实现一致）
        assertNotNull(result);
        assertEquals(9L, result.id());
        assertEquals(200L, result.userId());
        assertEquals(1L, result.sessionId());
        assertEquals(100L, result.messageId());
        assertTrue(result.isLiked());
        assertEquals("TECHNICAL_QA", result.intentType());
        // 单条 SQL：无 selectOne 预查、无 insert/update 分支
        verify(feedbackMapper).upsertFeedback(any());
        verify(feedbackMapper, never()).selectOne(any());
        verify(feedbackMapper, never()).insert(any(UserFeedback.class));
        verify(feedbackMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("P1-5 create — 插入实体携带雪花 ID 与登录用户归属（防跨用户伪造）")
    void create_upsert_assignsSnowflakeIdAndOwnership() {
        when(identifierGenerator.nextId(any())).thenReturn(123L);
        when(feedbackMapper.upsertFeedback(any())).thenReturn(new UserFeedback());

        feedbackService.create(200L, 1L, 10L, false, "knowledge_question");

        // upsert 入参实体：id = 生成器取号（ASSIGN_ID 仅在 BaseMapper.insert 生效，自定义 SQL 需显式生成）
        ArgumentCaptor<UserFeedback> captor = ArgumentCaptor.forClass(UserFeedback.class);
        verify(feedbackMapper).upsertFeedback(captor.capture());
        assertEquals(123L, captor.getValue().getId());
        // user_id 取当前登录用户（P0-2h 防跨用户伪造；ON CONFLICT (user_id, message_id) 保证只命中本人反馈）
        assertEquals(200L, captor.getValue().getUserId());
        assertEquals(10L, captor.getValue().getMessageId());
        assertEquals(1L, captor.getValue().getSessionId());
        assertEquals(false, captor.getValue().getIsLiked());
        assertEquals("knowledge_question", captor.getValue().getIntentType());
    }

    @Test
    @DisplayName("P1-5 create — 冲突路径幂等更新语义：返回既有行 id/sessionId + 新赞踩状态")
    void create_upsert_conflictKeepsExistingRowIdentity() {
        // 场景：同用户同消息已有反馈（id=77、sessionId=55、踩）→ upsert 更新赞踩与意图，
        // RETURNING 返回既有行 id/session_id（DO UPDATE 不触碰）+ 本次写入的 is_liked/intent_type
        UserFeedback returned = new UserFeedback();
        returned.setId(77L);
        returned.setUserId(200L);
        returned.setSessionId(55L);
        returned.setMessageId(100L);
        returned.setIsLiked(true);
        returned.setIntentType("COURSE_INFO");
        when(identifierGenerator.nextId(any())).thenReturn(123L);
        when(feedbackMapper.upsertFeedback(any())).thenReturn(returned);

        UserFeedbackVO result = feedbackService.create(200L, 99L, 100L, true, "COURSE_INFO");

        // 既有行身份保留（id/sessionId），赞踩与意图为本次值
        assertEquals(77L, result.id());
        assertEquals(55L, result.sessionId());
        assertTrue(result.isLiked());
        assertEquals("COURSE_INFO", result.intentType());
    }

    @Test
    @DisplayName("P1-5 create — 先写 DB 后失效统计缓存（顺序保持一致性铁律）")
    void create_upsert_invalidatesStatsCacheAfterDbWrite() {
        when(identifierGenerator.nextId(any())).thenReturn(123L);
        when(feedbackMapper.upsertFeedback(any())).thenReturn(new UserFeedback());

        feedbackService.create(200L, 1L, 100L, true, "TECHNICAL_QA");

        InOrder inOrder = inOrder(feedbackMapper, dashboardStatsCache);
        inOrder.verify(feedbackMapper).upsertFeedback(any());
        inOrder.verify(dashboardStatsCache).invalidateAll();
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
}
