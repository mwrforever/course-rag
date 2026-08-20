package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.test.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * user_episodic_memory 表结构集成测试（Testcontainers 真实 PG，计划 5/5 Task 1）
 *
 * <p>验证：V12 迁移落地（表/列/索引）、@TableLogic 软删语义、
 * JSONB 列可写入/读回原始 JSON 文本。
 *
 * @author commerce-rag
 */
class UserEpisodicMemorySchemaTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void tableExistsWithExpectedColumnsAndIndexes() {
        // 表存在且关键列齐全（原始 SQL 直查，绕开 MP @TableLogic 过滤）
        List<Map<String, Object>> cols = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns " + "WHERE table_name = 'user_episodic_memory'");
        List<String> names =
                cols.stream().map(c -> String.valueOf(c.get("column_name"))).toList();
        for (String expect : List.of(
                "id",
                "user_id",
                "type",
                "content",
                "summary",
                "structured_facts",
                "importance",
                "confidence",
                "validity",
                "version",
                "source_session_id",
                "deleted",
                "created_at",
                "updated_at")) {
            assertTrue(names.contains(expect), "缺少列: " + expect);
        }
        // 索引存在（user+type+validity+deleted 查询路径）
        List<Map<String, Object>> idx =
                jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'user_episodic_memory'");
        assertTrue(idx.stream().anyMatch(r -> String.valueOf(r.get("indexname")).contains("idx_episodic_user_type")));
    }

    @Test
    void insertRawJsonBAndReadBack() {
        Long id = 9000000000000000001L;
        // 原始 SQL 插入 JSONB（含中文），验证 structured_facts 可落可读
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, structured_facts,"
                        + " importance, confidence, validity, version, deleted) VALUES "
                        + "(?, ?, ?, ?, ?, ?::jsonb, 0.900, 0.850, 'active', 1, 0)",
                id,
                42L,
                "learning_progress",
                "Python 基础已学完，正在学 Django",
                "Python 基础完成，在学 Django",
                "{\"skill\": \"Python/Django\", \"stage\": \"Django学习\"}");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT structured_facts, importance FROM user_episodic_memory WHERE id = ?", id);
        // PG JDBC 对 jsonb 返回 PGobject，转换后断言原始 JSON 文本含关键字段值
        assertEquals(
                "Python/Django",
                String.valueOf(row.get("structured_facts")).contains("Python/Django") ? "Python/Django" : "");
        // importance 以 NUMERIC(4,3) 落 0.900，读回按数值比对（驱动返回类型/toString 形式不稳定，数值比对不受影响）
        assertEquals(0, new BigDecimal("0.900").compareTo(new BigDecimal(String.valueOf(row.get("importance")))));
    }

    @Test
    void softDeleteKeepsPhysicalRow() {
        Long id = 9000000000000000002L;
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, validity, version, deleted) "
                        + "VALUES (?, 42, 'resolved_question', 'JVM 堆溢出已调大 -Xmx 解决', 'active', 1, 0)",
                id);
        // 模拟 MP removeById 的软删语义（deleted 置 1）
        jdbcTemplate.update("UPDATE user_episodic_memory SET deleted = 1 WHERE id = ?", id);
        Integer remain = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_episodic_memory WHERE id = ? AND deleted = 0", Integer.class, id);
        assertEquals(0, remain);
        // 物理行仍在（审计可追溯）
        Integer physical = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_episodic_memory WHERE id = ?", Integer.class, id);
        assertEquals(1, physical);
    }
}
