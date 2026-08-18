package com.commerce.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ContextBuilderService 单元测试 —— <document> 块组装（spec §3.2）
 *
 * @author commerce-rag
 */
@DisplayName("ContextBuilderService document 组装测试")
class ContextBuilderServiceTest {

    private final ContextBuilderService service = new ContextBuilderService(5);

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
        ContextBuilderService small = new ContextBuilderService(2);
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
}
