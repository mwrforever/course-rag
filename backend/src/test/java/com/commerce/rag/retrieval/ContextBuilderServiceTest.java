package com.commerce.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.properties.ContextBuilderProperties;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ContextBuilderService 单元测试 —— <document> 块组装（spec §3.2）
 *
 * @author commerce-rag
 */
@DisplayName("ContextBuilderService document 组装测试")
class ContextBuilderServiceTest {

    /** topK=5（与 application.yml 显式值一致）经属性类注入 */
    private final ContextBuilderService service = new ContextBuilderService(new ContextBuilderProperties(5));

    private static KnowledgeChunk chunk(String id, String content, String docTitle, String headingPath) {
        return new KnowledgeChunk(
                id, content, "", docTitle, headingPath, 0.9, IntentType.KNOWLEDGE_QUESTION, "h".repeat(64));
    }

    @Test
    @DisplayName("buildDocument — 组装检索说明 + system-document，序号按 rerank 顺序")
    void buildDocument_assemblesDocumentBlock() {
        String doc = service.buildDocument(
                "高等数学怎么学",
                List.of("高等数学 学习方法"),
                List.of(chunk("c1", "高等数学第一章内容", "高等数学讲义", "第一章 > 1.1节"), chunk("c2", "极限定义", "高等数学讲义", "第一章")));

        assertTrue(doc.startsWith("<document>"), "document 块以 <document> 开头");
        assertTrue(doc.contains("用户原问题:\"高等数学怎么学\""), "检索说明含用户原问题");
        assertTrue(doc.contains("检索查询(基于原问题重写):\"高等数学 学习方法\""), "检索说明含重写查询");
        assertTrue(doc.contains("<system-document>"), "含 system-document 子块");
        assertTrue(doc.contains("[1] 高等数学第一章内容"), "高相关 chunk 序号靠前");
        assertTrue(doc.contains("[2] 极限定义"), "第二条按 rerank 顺序编号");
        assertTrue(doc.contains("(来源: 高等数学讲义 / 章节: 第一章 > 1.1节)"), "含来源/章节元数据");
        assertFalse(doc.contains("<user-document>"), "首版无附件不组装 user-document 子块");
    }

    @Test
    @DisplayName("buildDocument — 候选超过 topK 只取前 N 条（未消费的候选不进 document）")
    void buildDocument_exceedsTopK_truncates() {
        // topK=2 边界：验证超过注入上限的候选被截断
        ContextBuilderService small = new ContextBuilderService(new ContextBuilderProperties(2));
        List<KnowledgeChunk> chunks =
                List.of(chunk("c1", "一", "doc", "h1"), chunk("c2", "二", "doc", "h2"), chunk("c3", "三", "doc", "h3"));

        String doc = small.buildDocument("q", List.of("重写"), chunks);

        assertTrue(doc.contains("[1] 一") && doc.contains("[2] 二"));
        assertFalse(doc.contains("[3] 三"), "topK=2 时第三条不进 document");
        assertFalse(doc.contains("三"));
    }

    @Test
    @DisplayName("buildDocument — 空候选返回 null（RetrieveNode 不注入 document）")
    void buildDocument_emptyChunks_returnsNull() {
        assertNull(service.buildDocument("q", List.of("重写"), List.of()));
        assertNull(service.buildDocument("q", List.of("重写"), null));
    }

    @Test
    @DisplayName("buildDocument — 重写查询多条时列出，空重写列表跳过该行")
    void buildDocument_multipleRewrites_listed() {
        String doc = service.buildDocument("q", List.of("重写一", "重写二"), List.of(chunk("c1", "内容", "doc", "h")));

        assertTrue(doc.contains("检索查询(基于原问题重写):\"重写一\", \"重写二\""));

        String noRewrite = service.buildDocument("q", null, List.of(chunk("c1", "内容", "doc", "h")));
        assertFalse(noRewrite.contains("检索查询(基于原问题重写)"), "无重写列表时不输出检索查询行");
    }

    @Test
    @DisplayName("buildUserDocument — 图片 caption 与文件命中合并为 user-document 块")
    void buildUserDocument_mergesCaptionsAndHits() {
        String doc = service.buildUserDocument(
                List.of(new ImageCaptionResult("图片1:红色图表", "a.png")), Map.of("0/doc.pdf", List.of("段落一", "段落二")));

        assertTrue(doc.contains("<user-document>"), "以 user-document 块包裹附件局部上下文");
        assertTrue(doc.contains("[图片1:红色图表]"), "图片 caption 以 [图片N:描述] 形式注入");
        assertTrue(doc.contains("[文件1]"), "文档命中以 [文件N] 序号列出");
        assertTrue(doc.contains("0/doc.pdf"), "文件行携带附件 objectKey");
        assertTrue(doc.contains("    - 段落一"), "命中段落以缩进列表行输出");
        assertTrue(doc.endsWith("</user-document>"), "以闭合标签结束");
    }

    @Test
    @DisplayName("buildUserDocument — 无附件内容（caption 与命中均空）返回 null")
    void buildUserDocument_empty() {
        assertNull(service.buildUserDocument(null, Map.of()), "captions null + docHits 空 → null");
        assertNull(service.buildUserDocument(List.of(), null), "captions 空 + docHits null → null");
        assertNull(service.buildUserDocument(null, null), "双 null → null");
    }

    @Test
    @DisplayName("appendUserDocument — user-document 在 </document> 前插入，保持装配顺序")
    void appendUserDocument_insertsBeforeClosingTag() {
        String system = "<document>\n检索说明:...\n</system-document>\n</document>";
        String user = "<user-document>\n  [图片1:红色图表]\n</user-document>";

        String merged = service.appendUserDocument(system, user);

        // 顺序：system-document 在 user-document 之前，闭合标签仍位于末尾
        int systemIdx = merged.indexOf("</system-document>");
        int userIdx = merged.indexOf("<user-document>");
        int closeIdx = merged.indexOf("</document>");
        assertTrue(systemIdx >= 0 && userIdx >= 0 && closeIdx >= 0, "三部分均存在");
        assertTrue(systemIdx < userIdx, "system-document 位于 user-document 之前");
        assertTrue(userIdx < closeIdx, "user-document 在 </document> 闭合之前");
        assertTrue(merged.endsWith("</document>"), "闭合标签仍在末尾");
    }

    @Test
    @DisplayName("appendUserDocument — userDocument 为 null/空白时原样返回 systemDocument")
    void appendUserDocument_emptyUser_returnsSystemUnchanged() {
        String system = "<document>\n</system-document>\n</document>";

        assertEquals(system, service.appendUserDocument(system, null), "null userDocument 原样返回");
        assertEquals(system, service.appendUserDocument(system, "  "), "空白 userDocument 原样返回");
        assertEquals(
                null,
                service.appendUserDocument(null, "<user-document>x</user-document>"),
                "systemDocument null 返回 null");
    }
}
