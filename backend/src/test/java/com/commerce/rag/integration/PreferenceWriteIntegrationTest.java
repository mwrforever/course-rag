package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.commerce.rag.record.PreferenceCandidate;
import com.commerce.rag.record.PreferenceDeletion;
import com.commerce.rag.record.PreferenceExtractionResult;
import com.commerce.rag.service.IPreferenceService;
import com.commerce.rag.test.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 偏好落库集成测试 —— applyExtraction 真实 PG 写（决策链路 + 软删 + 唯一索引），spec §7.5
 *
 * <p>数据隔离：IntegrationTestBase.setUpBase() 由 JUnit 自动先执行（清理业务表/Redis/模型 stub，
 * 见 ChatFlowIntegrationTest 同款机制）；本类 @BeforeEach 额外清 user_preference（基类
 * cleanupBusinessTables 不含该表），防跨用例残留导致断言不稳（Task 1 审查 minor (a)）。
 */
class PreferenceWriteIntegrationTest extends IntegrationTestBase {

    @Autowired
    private IPreferenceService preferenceService;

    @BeforeEach
    void setUpPreference() {
        jdbcTemplate.update("DELETE FROM user_preference");
    }

    @Test
    @DisplayName("高显式候选 → CREATE_ACTIVE 写入，status=active/write_score>0.75")
    void applyExtraction_createsActive() {
        Long userId = registerUser("pref_test_1", "STUDENT");
        var result = new PreferenceExtractionResult(
                List.of(new PreferenceCandidate("response_language", "中文", 1.0, 0.9)), List.of());
        preferenceService.applyExtraction(userId, result);

        Long count =
                jdbcTemplate.queryForObject("SELECT count(*) FROM user_preference WHERE user_id=?", Long.class, userId);
        assertEquals(1L, count);
        String status =
                jdbcTemplate.queryForObject("SELECT status FROM user_preference WHERE user_id=?", String.class, userId);
        assertEquals("active", status);
    }

    @Test
    @DisplayName("DELETE 目标命中 → 软删 deleted=1（物理行保留审计）")
    void applyExtraction_softDeletes() {
        Long userId = registerUser("pref_test_2", "STUDENT");
        preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("course_direction", "Python", 0.9, 0.9)), List.of()));
        preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(), List.of(new PreferenceDeletion("course_direction", "Python"))));

        String deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM user_preference WHERE user_id=?", String.class, userId);
        assertEquals("1", deleted, "删除走软删 deleted=1（MP 逻辑删除）");
    }
}
