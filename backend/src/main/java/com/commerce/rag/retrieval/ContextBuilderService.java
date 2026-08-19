package com.commerce.rag.retrieval;

import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.List;
import java.util.Map;
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
 * &lt;user-document&gt; [图片1] caption / [文件1] key：局部检索段落... &lt;/user-document&gt;
 * &lt;/document&gt;
 * </pre>
 *
 * <p>只取前 {@code rag.context-builder.top-k}（默认 5）条组装（spec §3.2：Top-K 仅限系统检索）。
 * <p>user-document 子块（spec §3.2/§5.3）承载用户附件局部上下文：图片 caption 注入 +
 * 文档附件局部检索命中段落，由 {@link #buildUserDocument} 组装，经
 * {@link #appendUserDocument} 在 `</document>` 前合并进 document。
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

    /**
     * 组装 {@code <user-document>} 子块（spec §3.2/§5.3：用户附件局部上下文）
     *
     * <p>内容包括两部分的任一并集（均非空时才被组装）：
     * <ul>
     *   <li>图片 caption 行：`  [图片N:描述]`（适配器已拼好 "图片N:" 前缀）</li>
     *   <li>文档局部检索命中：`  [文件N] objectKey：` + 逐条 `    - 段落`（N 按 map 迭代序）</li>
     * </ul>
     *
     * <p>输出整体不带尾随换行（由 {@link #appendUserDocument} 在 `</document>` 前插入时补）。
     *
     * @param captions 图片 caption 结果（"图片N:描述"，按上传顺序；可为 null/空）
     * @param docHits  文档局部检索命中（key=附件 objectKey 短键，value=命中的段落文本列表；
     *                 可为 null/空；空分片键在 RetrieveNode 侧已剔除）
     * @return user-document 文本；captions 与 docHits 均无内容返回 null（调用方不合并该子块）
     */
    public String buildUserDocument(List<ImageCaptionResult> captions, Map<String, List<String>> docHits) {
        boolean empty = (captions == null || captions.isEmpty()) && (docHits == null || docHits.isEmpty());
        if (empty) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<user-document>\n");
        // 图片 caption 行（“图片N:描述”前缀由适配器标注）
        if (captions != null) {
            for (ImageCaptionResult c : captions) {
                sb.append("  [").append(c.caption()).append("]\n");
            }
        }
        // 文档局部检索命中段落（fileNo 按迭代序递增）
        if (docHits != null) {
            int fileNo = 1;
            for (Map.Entry<String, List<String>> entry : docHits.entrySet()) {
                sb.append("  [文件").append(fileNo++).append("] ");
                sb.append(entry.getKey()).append("：\n");
                for (String hit : entry.getValue()) {
                    sb.append("    - ").append(hit).append("\n");
                }
            }
        }
        sb.append("</user-document>");
        return sb.toString();
    }

    /**
     * 把 user-document 子块合并进 systemDocument（`</document>` 之前插入，spec §3.2 装配顺序）
     *
     * <p>顺序：document 头+检索说明 → system-document（既有）→ user-document（新增，若有）→
     * {@code </document>} 闭合。
     *
     * @param systemDocument 既有 systemDocument 文本（{@link #buildDocument} 输出，非 null）
     * @param userDocument   user-document 子块（可为 null/空：无附件上下文时原样返回 systemDocument）
     * @return 合并后的 document 文本；userDocument 为空时原样返回 systemDocument
     */
    public String appendUserDocument(String systemDocument, String userDocument) {
        if (systemDocument == null) {
            return null;
        }
        // 无 user-document 子块（无附件上下文/附件全部无命中）→ 原样返回既有 document
        if (userDocument == null || userDocument.isBlank()) {
            return systemDocument;
        }
        // 在 </document> 闭合标签前插入 user-document（document 仅一个闭合标签）
        return systemDocument.replace("</document>", userDocument + "\n</document>");
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
