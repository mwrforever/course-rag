package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.commerce.rag.record.PreferenceCandidate;
import com.commerce.rag.record.PreferenceDeletion;
import com.commerce.rag.record.PreferenceExtractionResult;
import com.commerce.rag.service.IPreferenceService;
import com.commerce.rag.test.IntegrationTestBase;
import java.util.List;
import java.util.Map;
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

    @Test
    @DisplayName("null userId / null result → 直接返回 0，不写库")
    void applyExtraction_nullInputsReturnZero() {
        Long userId = registerUser("pref_test_null", "STUDENT");
        var result = new PreferenceExtractionResult(
                List.of(new PreferenceCandidate("response_language", "中文", 1.0, 0.9)), List.of());
        assertEquals(0, preferenceService.applyExtraction(null, result), "userId 为空返回 0");
        assertEquals(0, preferenceService.applyExtraction(userId, null), "result 为空返回 0");
        Long count =
                jdbcTemplate.queryForObject("SELECT count(*) FROM user_preference WHERE user_id=?", Long.class, userId);
        assertEquals(0L, count, "空入参不应产生任何偏好行");
    }

    @Test
    @DisplayName("含糊新偏好 → CREATE_OBSERVING 进观察池（ws∈[observeLow,writeHigh)）")
    void applyExtraction_createsObserving() {
        Long userId = registerUser("pref_test_obs", "STUDENT");
        // e=0.6、c=0.9：ws=0.4*0.6+0.4*0.25+0.2*0.9=0.52，∈[0.50,0.75) 且 e<0.8 → 观察池
        preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("response_language", "中文", 0.6, 0.9)), List.of()));

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM user_preference WHERE user_id=? AND key='response_language'", String.class, userId);
        assertEquals("observing", status);
    }

    @Test
    @DisplayName("既有 active 同 value 再提 → REINFORCE 计数 +1（分数重算）")
    void applyExtraction_reinforcesActive() {
        Long userId = registerUser("pref_test_reinf", "STUDENT");
        var candidate = new PreferenceCandidate("response_language", "中文", 0.9, 0.9);
        assertEquals(
                1,
                preferenceService.applyExtraction(
                        userId, new PreferenceExtractionResult(List.of(candidate), List.of())));
        // 同值再提：命中 activeSame → REINFORCE（spec §7.5-①）
        assertEquals(
                1,
                preferenceService.applyExtraction(
                        userId, new PreferenceExtractionResult(List.of(candidate), List.of())));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT observation_count FROM user_preference WHERE user_id=? AND key='response_language'",
                Integer.class,
                userId);
        assertEquals(2, count, "REINFORCE 应使 observation_count +1 → 2");
    }

    @Test
    @DisplayName("既有 observing 同 value 未达线再提 → OBSERVE_REINFORCE 计数 +1")
    void applyExtraction_observingReinforces() {
        Long userId = registerUser("pref_test_obsreinf", "STUDENT");
        var candidate = new PreferenceCandidate("response_language", "中文", 0.6, 0.9);
        // 首次：ws=0.52 进观察池（count=1）；再次：count=2、stability=0.40、ws=0.58<0.75 → 未达线
        preferenceService.applyExtraction(userId, new PreferenceExtractionResult(List.of(candidate), List.of()));
        assertEquals(
                1,
                preferenceService.applyExtraction(
                        userId, new PreferenceExtractionResult(List.of(candidate), List.of())));

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM user_preference WHERE user_id=? AND key='response_language'", String.class, userId);
        assertEquals("observing", status, "未达晋升线保持观察池");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT observation_count FROM user_preference WHERE user_id=? AND key='response_language'",
                Integer.class,
                userId);
        assertEquals(2, count);
    }

    @Test
    @DisplayName("含糊冲突且观察池已有行 → OBSERVE_RESET 覆盖 value + count 重置 1")
    void applyExtraction_conflictResetsObserving() {
        Long userId = registerUser("pref_test_reset", "STUDENT");
        // 1. 明确建 active 英文（单值 key 冲突基底）
        preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("response_language", "英文", 0.9, 0.9)), List.of()));
        // 2. 含糊新 value → 无观察行 → CREATE_OBSERVING（观察池）
        preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("response_language", "中文", 0.6, 0.9)), List.of()));
        // 3. 含糊第三 value → 观察池已有行 → OBSERVE_RESET（覆盖 value、count 重置 1）
        int written = preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("response_language", "繁琐", 0.5, 0.6)), List.of()));
        assertEquals(1, written);

        Map<String, Object> obs = jdbcTemplate.queryForMap(
                "SELECT value, status, observation_count FROM user_preference"
                        + " WHERE user_id=? AND key='response_language' AND status='observing' AND deleted=0",
                userId);
        assertEquals("繁琐", obs.get("value"), "OBSERVE_RESET 应覆盖观察池 value");
        assertEquals("observing", obs.get("status"));
        assertEquals(1, ((Number) obs.get("observation_count")).intValue(), "count 重置 1");
    }

    @Test
    @DisplayName("观察池达线（count≥5 且 ws≥0.75）→ PROMOTE 晋升 active（撞车旧 active 软删审计）")
    void applyExtraction_promotesObserving() {
        Long userId = registerUser("pref_test_promote", "STUDENT");
        // 预置：单值 key 旧 active 英文（晋升时软删审计）+ observing 中文 count=4（接近晋升线）
        jdbcTemplate.update(
                "INSERT INTO user_preference"
                        + " (id, user_id, key, value, status, observation_count, version, source, write_score, stability, deleted)"
                        + " VALUES (?, ?, 'response_language', '英文', 'active', 3, 2, 'explicit', 0.85, 0.55, 0)",
                900101L,
                userId);
        jdbcTemplate.update(
                "INSERT INTO user_preference"
                        + " (id, user_id, key, value, status, observation_count, version, source, write_score, stability, deleted)"
                        + " VALUES (?, ?, 'response_language', '中文', 'observing', 4, 1, 'implicit', 0.55, 0.70, 0)",
                900102L,
                userId);
        // count 4→5、stability=0.85、ws=0.4*0.9+0.4*0.85+0.2*0.9=0.88 ≥0.75 → PROMOTE（含 superseded 审计）
        int written = preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("response_language", "中文", 0.9, 0.9)), List.of()));
        assertEquals(1, written);

        Map<String, Object> newRow = jdbcTemplate.queryForMap(
                "SELECT status, observation_count, source FROM user_preference"
                        + " WHERE user_id=? AND key='response_language' AND value='中文' AND deleted=0",
                userId);
        assertEquals("active", newRow.get("status"), "晋升后转 active");
        assertEquals(5, ((Number) newRow.get("observation_count")).intValue());
        assertEquals("implicit", newRow.get("source"), "晋升来源 implicit");
        Long deletedCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_preference"
                        + " WHERE user_id=? AND key='response_language' AND value='英文' AND deleted=1",
                Long.class,
                userId);
        assertEquals(1L, deletedCnt, "撞车旧 active 应软删审计（物理行保留）");
    }

    @Test
    @DisplayName("明确冲突变更 → UPDATE 软删旧 active + 新建 active（version+1）")
    void applyExtraction_conflictUpdatesActive() {
        Long userId = registerUser("pref_test_update", "STUDENT");
        preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("response_language", "英文", 0.9, 0.9)), List.of()));
        // 明确改为中文：e=0.9 ≥ 0.8 → UPDATE（spec §7.5 明确改变立即生效）
        int written = preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("response_language", "中文", 0.9, 0.9)), List.of()));
        assertEquals(1, written);

        Map<String, Object> newRow = jdbcTemplate.queryForMap(
                "SELECT value, status, version FROM user_preference"
                        + " WHERE user_id=? AND key='response_language' AND deleted=0",
                userId);
        assertEquals("中文", newRow.get("value"));
        assertEquals("active", newRow.get("status"));
        assertEquals(2, ((Number) newRow.get("version")).intValue(), "冲突 UPDATE 新行 version+1");
        Long deletedCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_preference"
                        + " WHERE user_id=? AND key='response_language' AND value='英文' AND deleted=1",
                Long.class,
                userId);
        assertEquals(1L, deletedCnt, "旧 value 软删审计");
    }

    @Test
    @DisplayName("低分新候选（ws<observeLow）→ IGNORE 不写库")
    void applyExtraction_ignoresLowScore() {
        Long userId = registerUser("pref_test_ignore", "STUDENT");
        // e=0.1、c=0.1：ws=0.4*0.1+0.4*0.25+0.2*0.1=0.16 < 0.50 → IGNORE
        int written = preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("response_language", "随便", 0.1, 0.1)), List.of()));
        assertEquals(0, written);
        Long count =
                jdbcTemplate.queryForObject("SELECT count(*) FROM user_preference WHERE user_id=?", Long.class, userId);
        assertEquals(0L, count, "IGNORE 不产生任何行");
    }

    @Test
    @DisplayName("DELETE 未命中（无该偏好）→ 返回 0 无副作用")
    void applyExtraction_softDeleteNoMatch() {
        Long userId = registerUser("pref_test_delnomatch", "STUDENT");
        int n = preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(), List.of(new PreferenceDeletion("response_language", "不存在的值"))));
        assertEquals(0, n, "未命中删除返回 0");
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM user_preference", Long.class);
        assertEquals(0L, count, "未命中删除不应产生任何行");
    }

    @Test
    @DisplayName("同批单值 key 双候选（明确冲突）→ 第二条转 UPDATE，不撞唯一索引、整批不回滚（BUG-03）")
    void applyExtraction_sameBatchConflict_updatesInsteadOfViolatingIndex() {
        Long userId = registerUser("pref_test_batch1", "STUDENT");
        // 同批两条同 key 候选（LLM 一批输出矛盾值）：第二条必须看到第一条的写入 → UPDATE 而非再次 CREATE
        int written = preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(
                                new PreferenceCandidate("response_language", "英文", 0.9, 0.9),
                                new PreferenceCandidate("response_language", "中文", 0.9, 0.9)),
                        List.of()));
        assertEquals(2, written, "CREATE_ACTIVE + UPDATE 各计 1（整批不回滚）");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT value, status, version FROM user_preference"
                        + " WHERE user_id=? AND key='response_language' AND deleted=0",
                userId);
        assertEquals("中文", row.get("value"), "后序候选作为最新明确表达生效");
        assertEquals("active", row.get("status"));
        assertEquals(2, ((Number) row.get("version")).intValue(), "冲突 UPDATE 新行 version+1");
        Long deletedCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_preference" + " WHERE user_id=? AND key='response_language' AND deleted=1",
                Long.class,
                userId);
        assertEquals(1L, deletedCnt, "被替换的英文软删审计");
    }

    @Test
    @DisplayName("同批同 key 同 value 双候选 → 第二条 REINFORCE 计数累加，无重复行（BUG-03 批内强引用）")
    void applyExtraction_sameBatchSameValue_reinforces() {
        Long userId = registerUser("pref_test_batch2", "STUDENT");
        var same = new PreferenceCandidate("response_language", "中文", 0.9, 0.9);
        int written = preferenceService.applyExtraction(
                userId, new PreferenceExtractionResult(List.of(same, same), List.of()));
        assertEquals(2, written, "CREATE_ACTIVE + REINFORCE 各计 1");

        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_preference WHERE user_id=? AND deleted=0", Long.class, userId);
        assertEquals(1L, count, "同批同 value 只保留一行（唯一索引未被撞）");
        Integer obs = jdbcTemplate.queryForObject(
                "SELECT observation_count FROM user_preference WHERE user_id=?", Integer.class, userId);
        assertEquals(2, obs, "第二条 REINFORCE 使计数 +1 → 2");
    }

    @Test
    @DisplayName("findExistingValuesText — active 偏好转「标签:值」注入文本")
    void findExistingValuesText_returnsLabels() {
        Long userId = registerUser("pref_test_label", "STUDENT");
        preferenceService.applyExtraction(
                userId,
                new PreferenceExtractionResult(
                        List.of(new PreferenceCandidate("response_language", "中文", 0.9, 0.9)), List.of()));
        assertEquals("回答语言:中文", preferenceService.findExistingValuesText(userId));
    }
}
