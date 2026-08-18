package com.commerce.rag.retrieval;

import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Context Builder —— 组装 `<document>` 临时上下文块（spec §3.2）
 *
 * <p>输入为 RetrieveNode 输出的检索候选（已 SHA256 去重 + Rerank 精排，按分数降序），
 * 输出 spec §3.2 定稿格式：
 * <pre>
 * &lt;document&gt;
 * 检索说明:（用户原问题 / 重写查询 / 重写规则 / 回答以原问题为准）
 * &lt;system-document&gt; [1] chunk（来源: 文档标题 / 章节）... &lt;/system-document&gt;
 * &lt;/document&gt;
 * </pre>
 *
 * <p>只取前 {@code rag.context-builder.top-k}（默认 5）条组装（spec §3.2：Top-K 仅限系统检索）；
 * user-document 子块属于计划 3/5（附件链路），本版本不组装。
 *
 * <p>document 是临时上下文：文本由 RetrieveNode 写入 config.metadata()，
 * DocumentAssemblerInterceptor 瞬时注入 UserMessage，不落 state/checkpoint。
 *
 * @author commerce-rag
 */
@Service
public class ContextBuilderService {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilderService.class);

    /** 系统资料注入条数上限（spec §3.2 "rag.context-builder.top-k" 默认 5） */
    private final int topK;

    public ContextBuilderService(@Value("${rag.context-builder.top-k:5}") int topK) {
        this.topK = topK;
    }

    /**
     * 组装 <document> 文本
     *
     * @param originalQuery    用户原问题（检索说明展示，回答以原问题为准）
     * @param rewrittenQueries 重写后的检索查询列表（可为 null/空，空则省略检索查询行）
     * @param chunks           已精排的检索候选（按 rerank 分数降序；空/空列表返回 null）
     * @return <document> 文本；chunks 为空返回 null（调用方不注入 document）
     */
    public String buildDocument(String originalQuery, List<String> rewrittenQueries, List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            log.debug("无检索候选，不组装 document（ReactAgent 直接回答）");
            return null;
        }

        List<KnowledgeChunk> top = chunks.size() > topK ? chunks.subList(0, topK) : chunks;

        StringBuilder sb = new StringBuilder("<document>\n");
        sb.append("检索说明:\n");
        sb.append("- 用户原问题:\"")
                .append(originalQuery == null ? "" : originalQuery)
                .append("\"\n");
        if (rewrittenQueries != null && !rewrittenQueries.isEmpty()) {
            String queries = rewrittenQueries.stream().map(q -> "\"" + q + "\"").collect(Collectors.joining(", "));
            sb.append("- 检索查询(基于原问题重写):").append(queries).append("\n");
        }
        sb.append("- 重写规则:理解用户实际需求,提炼关键实体与意图,去除口语噪声,以便精确检索\n");
        sb.append("- 回答时以用户原问题为准,检索查询仅用于资料获取\n");
        sb.append("<system-document>\n");
        int index = 1;
        for (KnowledgeChunk c : top) {
            sb.append("  [").append(index++).append("] ").append(c.content()).append("\n");
            sb.append("  (来源: ")
                    .append(blankTo(c.docTitle(), "未知"))
                    .append(" / 章节: ")
                    .append(blankTo(c.headingPath(), "未知"))
                    .append(")\n");
        }
        sb.append("</system-document>\n</document>");

        log.info("document 组装完成: 原问题={}, 重写={}条, 注入={}条", truncate(originalQuery, 30), top.size(), index - 1);
        return sb.toString();
    }

    /** null/空白 → 兜底值 */
    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }
}
