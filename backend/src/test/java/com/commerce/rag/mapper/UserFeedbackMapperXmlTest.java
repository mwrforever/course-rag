package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.commerce.rag.test.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

/**
 * UserFeedbackMapper.xml 执行级测试（真实 PG 执行反馈统计 SQL）
 *
 * <p>覆盖 Dashboard 反馈统计的三个聚合 SQL：
 * <ul>
 *   <li>selectDailyFeedbackCount：to_char 按天分组计数（日期格式 YYYY-MM-DD，升序）</li>
 *   <li>selectIntentStats：按意图分组 SUM CASE 统计赞/踩（null 意图与软删不参与分组）</li>
 *   <li>selectFeedbackStatsByPeriod：周期内 total_count / liked_count 单条 SQL</li>
 * </ul>
 *
 * <p>数据准备：本类 @BeforeEach 清空 user_feedback（基类清理不含该表）后 INSERT
 * 跨两天 7 条反馈：含不同 intent_type、null 意图、is_liked 三态与 1 条软删。
 *
 * @author commerce-rag
 */
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class UserFeedbackMapperXmlTest extends IntegrationTestBase {

    /** 第一天日期（2026-08-10） */
    private static final String DAY1 = "2026-08-10";
    /** 第二天日期（2026-08-11） */
    private static final String DAY2 = "2026-08-11";

    private final UserFeedbackMapper feedbackMapper;

    @BeforeEach
    void setUpFeedback() {
        // 清空反馈表（基类清理不含 user_feedback，本类自行清理保证数据形态可控）
        jdbcTemplate.update("DELETE FROM user_feedback");
        // 第一天：TECHNICAL_QA 赞/踩各 1、COURSE_INFO 赞/踩各 1、null 意图赞 1、软删赞 1
        insertFeedback(101L, true, "TECHNICAL_QA", 0L, DAY1 + " 09:30:00");
        insertFeedback(102L, false, "TECHNICAL_QA", 0L, DAY1 + " 10:30:00");
        insertFeedback(103L, true, "COURSE_INFO", 0L, DAY1 + " 11:30:00");
        insertFeedback(104L, false, "COURSE_INFO", 0L, DAY1 + " 12:30:00");
        insertFeedback(105L, true, null, 0L, DAY1 + " 13:30:00");
        insertFeedback(106L, true, "TECHNICAL_QA", 1L, DAY1 + " 14:30:00");
        // 第二天：TECHNICAL_QA 赞 1（验证 to_char 跨天分组）
        insertFeedback(107L, true, "TECHNICAL_QA", 0L, DAY2 + " 09:00:00");
    }

    /**
     * 预置单条反馈记录。
     *
     * @param id          反馈主键
     * @param liked       是否点赞（true=赞，false=踩）
     * @param intentType  意图类型（null = 无意图反馈）
     * @param deleted     逻辑删除标记（0 = 未删除，1 = 已删除）
     * @param createdAt   创建时间（显式控制以验证按天分组，参数以 Timestamp 传入避免 varchar→timestamp 隐式转换失败）
     */
    private void insertFeedback(Long id, boolean liked, String intentType, Long deleted, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO user_feedback (id, user_id, session_id, message_id, is_liked, intent_type, deleted,"
                        + " created_at) VALUES (?, 3001, 5001, ?, ?, ?, ?, ?)",
                id,
                id,
                liked,
                intentType,
                deleted,
                Timestamp.valueOf(createdAt));
    }

    /**
     * selectDailyFeedbackCount：to_char 按天分组真实生效——第一天 5 条（软删排除）、
     * 第二天 1 条，结果按日期升序，分组日期格式为 YYYY-MM-DD。
     */
    @Test
    void selectDailyFeedbackCount按天分组统计正确() {
        List<Map<String, Object>> rows =
                feedbackMapper.selectDailyFeedbackCount(LocalDateTime.parse("2026-08-10T00:00:00"));
        assertEquals(2, rows.size(), "跨两天应分组为 2 行");
        assertEquals(DAY1, rows.get(0).get("d"), "首行应为第一天（日期升序）");
        assertEquals(5L, ((Number) rows.get(0).get("c")).longValue(), "第一天应有 5 条未删除反馈");
        assertEquals(DAY2, rows.get(1).get("d"), "次行应为第二天");
        assertEquals(1L, ((Number) rows.get(1).get("c")).longValue(), "第二天应有 1 条未删除反馈");
    }

    /**
     * selectIntentStats：赞/踩 SUM CASE 正确——TECHNICAL_QA 赞 2 踩 1、COURSE_INFO 赞 1 踩 1，
     * null 意图与软删反馈不参与分组，按意图名升序（COURSE_INFO 在前）。
     */
    @Test
    void selectIntentStats赞踩分组统计正确() {
        List<Map<String, Object>> rows = feedbackMapper.selectIntentStats();
        assertEquals(2, rows.size(), "只有两种非 null 意图参与分组");
        Map<String, Object> course = rows.get(0);
        assertEquals("COURSE_INFO", course.get("intent_type"), "意图按名称升序 COURSE_INFO 在前");
        assertEquals(1L, ((Number) course.get("liked_count")).longValue(), "COURSE_INFO 赞数应为 1");
        assertEquals(1L, ((Number) course.get("disliked_count")).longValue(), "COURSE_INFO 踩数应为 1");
        Map<String, Object> tech = rows.get(1);
        assertEquals("TECHNICAL_QA", tech.get("intent_type"), "意图按名称升序 TECHNICAL_QA 在后");
        assertEquals(2L, ((Number) tech.get("liked_count")).longValue(), "TECHNICAL_QA 赞数应为 2（含第二天 1 条）");
        assertEquals(1L, ((Number) tech.get("disliked_count")).longValue(), "TECHNICAL_QA 踩数应为 1");
    }

    /**
     * selectFeedbackStatsByPeriod：周期过滤 + total/liked 单条 SQL——
     * 周期取第二天仅 1 条（赞）；周期含两天则 6 条（软删排除，null 意图的赞计入 liked）。
     */
    @Test
    void selectFeedbackStatsByPeriod周期过滤统计正确() {
        // 周期仅含第二天：只统计反馈 107（赞）
        Map<String, Object> day2 =
                feedbackMapper.selectFeedbackStatsByPeriod(LocalDateTime.parse("2026-08-11T00:00:00"));
        assertEquals(1L, ((Number) day2.get("total_count")).longValue(), "第二天周期 total 应为 1");
        assertEquals(1L, ((Number) day2.get("liked_count")).longValue(), "第二天周期 liked 应为 1");

        // 周期含两天：6 条未删除反馈（101-105 + 107），赞 4 条（101/103/105/107）
        Map<String, Object> fullPeriod =
                feedbackMapper.selectFeedbackStatsByPeriod(LocalDateTime.parse("2026-08-10T00:00:00"));
        assertEquals(6L, ((Number) fullPeriod.get("total_count")).longValue(), "全周期 total 应为 6（软删排除）");
        assertEquals(4L, ((Number) fullPeriod.get("liked_count")).longValue(), "全周期 liked 应为 4（null 意图赞计入）");
    }
}
