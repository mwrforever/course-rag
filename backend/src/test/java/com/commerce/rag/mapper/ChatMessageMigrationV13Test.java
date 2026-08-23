package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Flyway V13 迁移文件测试 —— chat_message (run_id, seq) 唯一索引兜底（B2-4 数据层防线）
 *
 * <p>完成时刻 DB 故障的残余路径曾使同一 run 的消息批次重复落库（应用层已加
 * persisted 原子标志防护），V13 唯一索引为最终防线。本测试校验迁移文件的
 * 存在性、命名规范（V&lt;n&gt;__描述.sql，跟随 V6-V12 序列）与索引定义内容；
 * 索引在真实 PG 上的实际生效由集成测试基座（Testcontainers + Flyway 全量迁移）验证。
 *
 * @author commerce-rag
 */
@DisplayName("V13 chat_message (run_id,seq) 唯一索引迁移文件测试")
class ChatMessageMigrationV13Test {

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
}
