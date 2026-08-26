package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.convert.UserFeedbackConverter;
import com.commerce.rag.convert.UserFeedbackConverterImpl;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.mapper.UserFeedbackMapper;
import com.commerce.rag.service.impl.UserFeedbackServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.UserFeedbackVO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
    private DashboardCacheEvictor dashboardCacheEvictor;

    /** findPage 入参分页对象捕获器（验证 size 回退默认页大小） */
    @Captor
    private ArgumentCaptor<Page<UserFeedback>> pageCaptor;

    /** findPage 入参查询条件捕获器（验证意图过滤条件是否进入 wrapper） */
    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<UserFeedback>> wrapperCaptor;

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

        InOrder inOrder = inOrder(feedbackMapper, dashboardCacheEvictor);
        inOrder.verify(feedbackMapper).upsertFeedback(any());
        inOrder.verify(dashboardCacheEvictor).evictAll();
    }

    @Test
    @DisplayName("findPage — 实体分页转 VO 分页：records 逐条转换、total/current/size 语义保持")
    void findPage_mapsEntityPageToVoPage() {
        // 模拟 DB 返回的分页实体（同意图两条反馈，按创建时间降序由 SQL 保证）
        UserFeedback row1 = new UserFeedback();
        row1.setId(1L);
        row1.setUserId(200L);
        row1.setIsLiked(true);
        row1.setIntentType("TECHNICAL_QA");
        UserFeedback row2 = new UserFeedback();
        row2.setId(2L);
        row2.setUserId(201L);
        row2.setIsLiked(false);
        row2.setIntentType("TECHNICAL_QA");
        Page<UserFeedback> entityPage = new Page<>(1, 10, 2);
        entityPage.setRecords(List.of(row1, row2));
        when(feedbackMapper.selectPage(any(), any())).thenReturn(entityPage);

        IPage<UserFeedbackVO> voPage = feedbackService.findPage(1, 10, "TECHNICAL_QA");

        // records 已转换为 VO（实体不出 service 边界），字段随实体行转换
        assertEquals(2, voPage.getRecords().size());
        assertEquals(1L, voPage.getRecords().get(0).id());
        assertTrue(voPage.getRecords().get(0).isLiked());
        assertEquals(2L, voPage.getRecords().get(1).id());
        assertEquals(false, voPage.getRecords().get(1).isLiked());
        // total/current/size 分页语义保持
        assertEquals(2, voPage.getTotal());
        assertEquals(1, voPage.getCurrent());
        assertEquals(10, voPage.getSize());
        // 意图非空 → 过滤条件进入 wrapper（B 端按意图筛选生效）；
        // MP 条件参数惰性渲染，须先触发 getSqlSegment 再查参数对
        verify(feedbackMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("intent_type"), "intentType 过滤列应进入 SQL 片段");
        assertTrue(
                wrapperCaptor.getValue().getParamNameValuePairs().containsValue("TECHNICAL_QA"),
                "intentType 条件值应进入查询参数");
    }

    @Test
    @DisplayName("findPage — size 非正回退默认每页 20 条，空白意图不加过滤条件")
    void findPage_invalidSizeAndBlankIntent_fallsBackToDefaults() {
        // 空结果页：size=0 → 默认 20；intentType 空白 → 不拼接意图条件
        Page<UserFeedback> emptyPage = new Page<>(1, 20, 0);
        emptyPage.setRecords(List.of());
        when(feedbackMapper.selectPage(any(), any())).thenReturn(emptyPage);

        IPage<UserFeedbackVO> voPage = feedbackService.findPage(1, 0, "   ");

        assertTrue(voPage.getRecords().isEmpty());
        assertEquals(0, voPage.getTotal());
        // size 非正时以默认页大小 20 落库查询
        verify(feedbackMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertEquals(20, pageCaptor.getValue().getSize(), "size=0 应回退默认每页 20 条");
        // 空白意图不产生过滤条件（SQL 片段仅剩排序，不含 intent_type）
        assertFalse(wrapperCaptor.getValue().getSqlSegment().contains("intent_type"), "空白意图不应产生过滤条件");
    }

    @Test
    @DisplayName("findPage — intentType 为 null 不加过滤条件（B 端全量分页浏览）")
    void findPage_nullIntent_noFilter() {
        // 未指定意图筛选：分页浏览全量反馈，wrapper 不携带意图条件
        Page<UserFeedback> emptyPage = new Page<>(1, 20, 0);
        emptyPage.setRecords(List.of());
        when(feedbackMapper.selectPage(any(), any())).thenReturn(emptyPage);

        IPage<UserFeedbackVO> voPage = feedbackService.findPage(1, 20, null);

        assertTrue(voPage.getRecords().isEmpty());
        verify(feedbackMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertFalse(wrapperCaptor.getValue().getSqlSegment().contains("intent_type"), "null 意图不应产生过滤条件");
    }

    @Test
    @DisplayName("findStats — 聚合计数为 NULL 的意图（无赞无踩）兜底为 0")
    void findStats_nullAggregateCounts_fallbackToZero() {
        // PG 条件聚合：某意图既无赞也无踩时 count 返回 NULL，对外需统一为 0
        Map<String, Object> nullRow = new HashMap<>();
        nullRow.put("intent_type", "chat");
        nullRow.put("liked_count", null);
        nullRow.put("disliked_count", null);
        when(feedbackMapper.selectIntentStats()).thenReturn(List.of(nullRow));

        List<Map<String, Object>> stats = feedbackService.findStats();

        assertEquals(1, stats.size());
        assertEquals("chat", stats.get(0).get("intentType"));
        assertEquals(0L, stats.get(0).get("likedCount"), "NULL 赞数应兜底 0");
        assertEquals(0L, stats.get(0).get("dislikedCount"), "NULL 踩数应兜底 0");
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
