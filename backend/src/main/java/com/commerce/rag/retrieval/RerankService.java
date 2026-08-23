package com.commerce.rag.retrieval;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Rerank 精排服务 —— 使用 qwen3-rerank 对融合后的候选集重新评分
 *
 * <p>流程：候选 chunk 列表 → 构造 Document → Reranker → 按 rerank score 过滤（≥threshold）
 * → 返回重新排序后的 chunk 列表
 *
 * <p>降级策略：RerankModel 调用异常时返回原列表前 10 条，不中断主流程。
 *
 * @author commerce-rag
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    /** 降级时返回的最大结果数 */
    private static final int FALLBACK_LIMIT = 10;

    private final RerankModel rerankModel;
    private final double rerankThreshold;
    private final String rerankInstruct;

    public RerankService(
            RerankModel rerankModel,
            @Value("${retrieval.rerank-threshold:0.30}") double rerankThreshold,
            PromptLoader promptLoader) {
        this.rerankModel = rerankModel;
        this.rerankThreshold = rerankThreshold;
        // 加载 rerank-instruct.yml 中的 instruct 指令文本
        String instruct = promptLoader.load("rerank-instruct.yml");
        // 从加载结果中提取 instruct 值（格式为 "rerank: instruct: <content>"）
        this.rerankInstruct = extractInstructValue(instruct);
        log.info("RerankService 初始化: rerankInstruct 长度={}", rerankInstruct.length());
    }

    /**
     * 从 PromptLoader 加载的 YAML 文本中提取 instruct 的值。
     * PromptLoader 返回格式为 "rerank: \ninstruct: <content>"，
     * 本方法截取 instruct: 之后的内容。
     *
     * @param raw PromptLoader 加载的原始文本
     * @return instruct 指令文本
     */
    private String extractInstructValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String marker = "instruct:";
        int idx = raw.indexOf(marker);
        if (idx < 0) {
            return raw.trim();
        }
        return raw.substring(idx + marker.length()).trim();
    }

    /**
     * Rerank 重排序（使用 DashScopeRerankModel）
     *
     * <p>流程：
     * <ol>
     *   <li>将 KnowledgeChunk 列表转为 Document 列表（content + metadata 带 chunkId）</li>
     *   <li>调用 rerankModel.call(RerankRequest) 获取重排序结果</li>
     *   <li>过滤 score < threshold 的结果</li>
     *   <li>映射回 KnowledgeChunk（带 rerank score）</li>
     * </ol>
     *
     * <p>降级：异常时返回原列表前 {@value #FALLBACK_LIMIT} 条。
     *
     * @param query  查询文本
     * @param chunks 候选 chunk 列表
     * @return 按 rerank score 降序排列、过滤低于阈值的 chunk 列表
     */
    public List<KnowledgeChunk> rerank(String query, List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 1. 转换为 Document 列表（content 作为文本，chunkId 放 metadata）
            List<Document> documents = chunks.stream()
                    .map(c -> new Document(c.content(), Map.of("chunkId", c.chunkId())))
                    .collect(Collectors.toList());

            // 2. 调用 RerankModel（将 instruct 指令拼入 query 以实现指令注入）
            String effectiveQuery = rerankInstruct.isBlank() ? query : rerankInstruct + "\n" + query;
            RerankRequest request = new RerankRequest(effectiveQuery, documents);
            RerankResponse response = rerankModel.call(request);

            // 3. 构建 chunkId → 原 chunk 的映射表（用于回填原始字段）
            Map<String, KnowledgeChunk> chunkMap = chunks.stream()
                    .collect(Collectors.toMap(KnowledgeChunk::chunkId, Function.identity(), (a, b) -> a));

            // 4. 过滤 + 映射回 KnowledgeChunk
            List<KnowledgeChunk> result = response.getResults().stream()
                    .filter(dws -> dws.getScore() != null && dws.getScore() >= rerankThreshold)
                    .map(dws -> mapToChunk(dws, chunkMap))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.info("Rerank 完成: 输入={}, 输出={}, 阈值={}", chunks.size(), result.size(), rerankThreshold);
            return result;

        } catch (Exception e) {
            log.warn("Rerank 异常，降级返回原列表前{}: {}", FALLBACK_LIMIT, e.getMessage());
            return fallbackToOriginal(chunks);
        }
    }

    /**
     * 将 DocumentWithScore 映射回 KnowledgeChunk（用 rerank score 替换原 score）
     *
     * @param dws      rerank 结果
     * @param chunkMap chunkId → 原 chunk 映射
     * @return 带 rerank score 的 KnowledgeChunk，若找不到原 chunk 则返回 null
     */
    private KnowledgeChunk mapToChunk(DocumentWithScore dws, Map<String, KnowledgeChunk> chunkMap) {
        Document doc = dws.getOutput();
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        Object chunkIdObj = doc.getMetadata().get("chunkId");
        if (chunkIdObj == null) {
            return null;
        }
        String chunkId = String.valueOf(chunkIdObj);
        KnowledgeChunk original = chunkMap.get(chunkId);
        if (original == null) {
            return null;
        }
        // 用 rerank score 替换原始 score，保留其余字段（8 字段，对照设计 §2.4）
        // source 已废弃恒空串（docTitle 为替代字段，B1-5 弃用收尾）：透传空串字面量，
        // 与 SearchKnowledgeTool 构造口径一致，不再触发 @Deprecated accessor 调用
        return new KnowledgeChunk(
                original.chunkId(),
                original.content(),
                "",
                original.docTitle(),
                original.headingPath(),
                dws.getScore(),
                original.collectionType(),
                original.sha256());
    }

    /**
     * 降级策略：返回原列表前 FALLBACK_LIMIT 条
     */
    private List<KnowledgeChunk> fallbackToOriginal(List<KnowledgeChunk> chunks) {
        List<KnowledgeChunk> fallback = new ArrayList<>(chunks);
        if (fallback.size() > FALLBACK_LIMIT) {
            fallback = new ArrayList<>(fallback.subList(0, FALLBACK_LIMIT));
        }
        return fallback;
    }
}
