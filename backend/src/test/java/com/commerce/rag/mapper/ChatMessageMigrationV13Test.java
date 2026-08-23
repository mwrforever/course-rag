package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.test.IntegrationTestBase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;

/**
 * Flyway V13 迁移文件测试 —— chat_message (run_id, seq) 唯一索引兜底（B2-4 数据层防线）
 *
 * <p>完成时刻 DB 故障的残余路径曾使同一 run 的消息批次重复落库（应用层已加
 * persisted 原子标志防护），V13 唯一索引为最终防线。测试分两层：
 * <ul>
 *   <li>文件层：迁移文件存在性、命名规范（V&lt;n&gt;__描述.sql，跟随 V6-V12 序列）与索引定义内容</li>
 *   <li>Testcontainers 真实 PG 层：模拟 B2-4 漏洞存续期已积累重复 (run_id, seq) 活跃行的
 *       生产库，验证迁移先软删重复行（每组保留 max(id) 最新一条）再建唯一索引，
 *       不会因历史脏数据建索引失败阻断部署</li>
 * </ul>
 *
 * @author commerce-rag
 */
@DisplayName("V13 chat_message (run_id,seq) 唯一索引迁移文件测试")
class ChatMessageMigrationV13Test extends IntegrationTestBase {

    /** 迁移文件路径（surefire 工作目录 = backend 模块根） */
    private static final Path MIGRATION_FILE =
            Path.of("src/main/resources/db/migration/V13__chat_message_run_seq_unique.sql");

    @Test
    @DisplayName("迁移文件存在且命名符合 V<n>__描述.sql 规范（V12 之后的下一序号）")
    void migrationFile_existsWithValidNaming() {
        assertTrue(Files.exists(MIGRATION_FILE), "迁移文件应存在: " + MIGRATION_FILE);
        // 命名规范：V13 为 V12（user_episodic_memory）之后的下一序号，下划线分隔描述
        assertEquals(
                "V13__chat_message_run_seq_unique.sql",
                MIGRATION_FILE.getFileName().toString());
    }

    @Test
    @DisplayName("迁移内容 → 建唯一索引 uniq_chat_message_run_seq(run_id, seq) 并移除同列普通索引")
    void migrationContent_createsUniqueIndexOnRunIdSeq() throws Exception {
        String sql = Files.readString(MIGRATION_FILE);

        // 唯一索引覆盖 (run_id, seq)，与 V6 逻辑删除风格一致（WHERE deleted = 0）
        assertTrue(sql.contains("CREATE UNIQUE INDEX uniq_chat_message_run_seq"), "应创建唯一索引 uniq_chat_message_run_seq");
        assertTrue(sql.contains("(run_id, seq)"), "唯一索引应覆盖 (run_id, seq) 列");
        assertTrue(sql.contains("WHERE deleted = 0"), "应沿用 deleted = 0 部分索引风格");
        // 被取代的同列普通索引（V6 的 idx_chat_message_run_seq）应清理，避免冗余索引
        assertTrue(sql.contains("DROP INDEX IF EXISTS idx_chat_message_run_seq"), "应移除被取代的普通索引");
    }

    @Test
    @DisplayName("V13 重复数据兼容 → 预置重复 (run_id,seq) 行：软删旧重复行保留 max(id) 一条且唯一索引建成")
    void migration_withDuplicateRows_softDeletesDuplicatesThenCreatesUniqueIndex() throws Exception {
        // 模拟 B2-4 漏洞存续期的生产库：V13 执行前已积累重复活跃行（同一 run 内 seq 重复）
        jdbcTemplate.execute("DROP INDEX IF EXISTS uniq_chat_message_run_seq");
        // run 990001 / seq 0 三条重复（id 最大者 = 最后落库结果，应保留）
        insertMessage(600001L, 990001L, 0, 0L);
        insertMessage(600002L, 990001L, 0, 0L);
        insertMessage(600003L, 990001L, 0, 0L);
        // run 990002 / seq 1 单行组（无重复，应保持活跃不动）
        insertMessage(600004L, 990002L, 1, 0L);
        // run 990003 / seq 2 历史已软删行（迁移不得触碰其 deleted 时间戳）
        insertMessage(600005L, 990003L, 2, 1700000000000L);

        try {
            // 在真实 PG 上重放 V13 迁移原文（含重复行软删 + 建唯一索引）
            executeV13Migration();

            // 重复组仅保留 max(id)（最新落库）一条活跃行
            assertEquals(1, activeCount(990001L, 0), "重复组应仅剩一条活跃行（max(id)）");
            assertEquals(
                    600003L,
                    jdbcTemplate.queryForObject(
                            "SELECT max(id) FROM chat_message WHERE run_id = ? AND seq = 0 AND deleted = 0",
                            Long.class,
                            990001L),
                    "保留的应为 id 最大（最后一次落库）的一条");
            // 其余重复行被软删（deleted = 迁移时刻毫秒时间戳，非 0 即出部分索引）
            assertEquals(
                    2,
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM chat_message WHERE run_id = ? AND deleted <> 0",
                            Integer.class,
                            990001L),
                    "旧重复行应被软删（deleted 置非 0 时间戳）");
            // 单行组保持活跃；已软删行不被触碰
            assertEquals(1, activeCount(990002L, 1));
            assertEquals(
                    1700000000000L,
                    jdbcTemplate.queryForObject("SELECT deleted FROM chat_message WHERE id = 600005", Long.class),
                    "历史已软删行的 deleted 不得被改写");

            // 唯一索引建成且生效：再插同 (run_id, seq) 活跃行被数据库直接拒绝
            assertTrue(indexExists(), "唯一索引 uniq_chat_message_run_seq 应建成");
            assertThrows(
                    DuplicateKeyException.class,
                    () -> insertMessage(600006L, 990001L, 0, 0L),
                    "唯一索引生效后重复插入应被拒绝（最终防线语义）");
        } finally {
            // 还原共享测试库状态：清测试数据后幂等重建唯一索引
            // （try 内可能已建索引——先 DROP IF EXISTS 再重放，保证任意失败点退出后索引唯一存在）
            jdbcTemplate.update("DELETE FROM chat_message WHERE id BETWEEN 600001 AND 600006");
            jdbcTemplate.execute("DROP INDEX IF EXISTS uniq_chat_message_run_seq");
            executeV13Migration();
        }
    }

    // ==================== 辅助方法 ====================

    /** 插入一条 chat_message 测试行（run_id/seq/deleted 由用例指定，其余列取最小合法值） */
    private void insertMessage(Long id, Long runId, int seq, long deleted) {
        jdbcTemplate.update(
                "INSERT INTO chat_message (id, session_id, role, content, run_id, seq, deleted)"
                        + " VALUES (?, 600, 'USER', '迁移测试消息', ?, ?, ?)",
                id,
                runId,
                seq,
                deleted);
    }

    /** 统计指定 (run_id, seq) 的活跃（deleted = 0）行数 */
    private int activeCount(Long runId, int seq) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_message WHERE run_id = ? AND seq = ? AND deleted = 0",
                Integer.class,
                runId,
                seq);
        return count == null ? 0 : count;
    }

    /** 判断唯一索引 uniq_chat_message_run_seq 是否存在于 chat_message 表 */
    private boolean indexExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'chat_message'"
                        + " AND indexname = 'uniq_chat_message_run_seq'",
                Integer.class);
        return count != null && count > 0;
    }

    /**
     * 在真实 PG 上重放 V13 迁移原文：去掉 `--` 行注释后按分号拆分逐条执行
     * （Flyway 迁移即按此语义执行该文件；测试直接复用文件内容保证断言对象与部署产物一致）
     */
    private void executeV13Migration() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V13__chat_message_run_seq_unique.sql");
        String sql;
        try (InputStream in = resource.getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String statements = Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        for (String statement : statements.split(";")) {
            if (!statement.isBlank()) {
                jdbcTemplate.execute(statement.trim());
            }
        }
    }
}
