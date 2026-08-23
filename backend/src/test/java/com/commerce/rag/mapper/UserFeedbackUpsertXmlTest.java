package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * UserFeedbackMapper.xml upsert 语句规范性测试（P1-5，文件级断言）
 *
 * <p>背景：反馈创建原为 selectOne + insert/update 两往返，并发双击撞 partial 唯一索引
 * uniq_feedback_message(user_id, message_id) WHERE deleted = 0 抛异常。
 * ON CONFLICT 单条 SQL 化后，冲突目标必须带 WHERE 谓词才能推断 partial 唯一索引——
 * 本类守住该谓词与 EXCLUDED 引用不被误删（缺谓词时 PG 报"no unique or exclusion
 * constraint matching the ON CONFLICT specification"）。
 *
 * <p>本类为文件级断言（无 Docker 环境）；upsert 真实执行（插入/冲突更新/软删新行三分支）
 * 由 UserFeedbackMapperXmlTest（Testcontainers）覆盖。
 *
 * @author commerce-rag
 */
class UserFeedbackUpsertXmlTest {

    /** 读取 UserFeedbackMapper.xml 全文 */
    private String readMapperXml() throws IOException {
        Resource resource = new ClassPathResource("mapper/UserFeedbackMapper.xml");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("P1-5 upsert 冲突目标带 partial 索引谓词 — (user_id, message_id) WHERE deleted = 0")
    void upsert_conflictTarget_carriesPartialIndexPredicate() throws IOException {
        String xml = readMapperXml();
        int idx = xml.indexOf("id=\"upsertFeedback\"");
        assertTrue(idx >= 0, "UserFeedbackMapper.xml 应包含 upsertFeedback 语句");
        String stmt = xml.substring(idx);

        // 冲突目标列 = 唯一索引列（user_id + message_id，防跨用户命中他人反馈）
        assertTrue(
                stmt.contains("ON CONFLICT (user_id, message_id)"),
                "冲突目标应为 (user_id, message_id)——与 uniq_feedback_message 唯一索引列一致");
        // 冲突目标必须带 WHERE deleted = 0 谓词才能推断 partial 唯一索引（不带则 SQL 直接报错）
        assertTrue(stmt.contains("WHERE deleted = 0"), "冲突目标必须带 WHERE deleted = 0 谓词（partial 索引推断要求）");
    }

    @Test
    @DisplayName("P1-5 upsert 冲突分支仅更新赞踩与意图（EXCLUDED 引用），RETURNING 取回落库行")
    void upsert_doUpdate_usesExcludedAndReturnsRow() throws IOException {
        String xml = readMapperXml();
        String stmt = xml.substring(xml.indexOf("id=\"upsertFeedback\""));

        // 冲突更新用 EXCLUDED（本次入参值），且只更新赞踩与意图——既有行 id/session_id/created_at 不被覆盖
        assertTrue(stmt.contains("DO UPDATE SET"), "冲突分支应为 DO UPDATE（幂等更新）");
        assertTrue(stmt.contains("is_liked = EXCLUDED.is_liked"), "赞踩应取本次值 EXCLUDED.is_liked");
        assertTrue(stmt.contains("intent_type = EXCLUDED.intent_type"), "意图应取本次值 EXCLUDED.intent_type");
        // RETURNING 取回落库后行（service 以返回行组装 VO，插入/冲突两分支语义统一）
        assertTrue(stmt.contains("RETURNING id,"), "应 RETURNING id 等列取回落库行");
    }
}
