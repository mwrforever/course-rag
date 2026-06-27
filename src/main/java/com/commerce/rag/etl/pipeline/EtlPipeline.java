package com.commerce.rag.etl.pipeline;

import com.commerce.rag.common.exception.RagBusinessException;
import com.commerce.rag.etl.chunker.SemanticChunker;
import com.commerce.rag.etl.chunker.TextChunk;
import com.commerce.rag.etl.parser.DocumentParser;
import com.commerce.rag.etl.parser.DocumentParserRouter;
import com.commerce.rag.etl.store.KeywordIndexStore;
import com.commerce.rag.etl.store.MilvusVectorStore;
import com.commerce.rag.knowledge.entity.Document;
import com.commerce.rag.knowledge.entity.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * ETL 全链路编排服务
 *
 * 串联文档处理的完整流程：
 * 上传文档 → 解析(Extract) → 分块(Transform) → Embedding → 双写(Load) → 更新状态
 *
 * 设计要点：
 * - 异步执行：长文档处理不阻塞主线程
 * - 状态追踪：每个阶段更新 Document 表的 parse_status
 * - 失败容错：单个 chunk embedding 失败不阻断整批
 * - 增量更新：同一文档重新上传时先清理旧数据
 */
@Slf4j
@Service
public class EtlPipeline {

    private final DocumentParserRouter parserRouter;
    private final SemanticChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final MilvusVectorStore milvusStore;
    private final KeywordIndexStore keywordStore;
    private final DocumentRepository documentRepository;

    public EtlPipeline(DocumentParserRouter parserRouter,
                       SemanticChunker chunker,
                       EmbeddingModel embeddingModel,
                       MilvusVectorStore milvusStore,
                       KeywordIndexStore keywordStore,
                       DocumentRepository documentRepository) {
        this.parserRouter = parserRouter;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.milvusStore = milvusStore;
        this.keywordStore = keywordStore;
        this.documentRepository = documentRepository;
    }

    /**
     * 异步执行 ETL 全链路
     *
     * 流程：解析 → 分块 → embedding → 双写（Milvus + PG） → 更新状态
     *
     * @param docId    文档 ID（已在数据库中创建记录）
     * @param fileData 文件二进制内容
     * @param fileName 文件名（含扩展名）
     * @return 处理完成后的文档记录
     */
    @Async
    public CompletableFuture<Document> processAsync(UUID docId, byte[] fileData, String fileName) {
        log.info("ETL 开始处理: docId={}, fileName={}", docId, fileName);
        long startTime = System.currentTimeMillis();

        try {
            // ── 阶段 1: 解析 (Extract) ──
            updateStatus(docId, EtlStatus.PARSING);
            DocumentParser.ParsedDocument parsedDoc;
            try (var inputStream = new ByteArrayInputStream(fileData)) {
                parsedDoc = parserRouter.parse(inputStream, fileName);
            }
            log.info("ETL 解析完成: docId={}, 章节数={}", docId, parsedDoc.sections().size());

            // ── 阶段 2: 分块 (Transform) ──
            updateStatus(docId, EtlStatus.CHUNKING);
            List<TextChunk> chunks = chunker.chunk(docId.toString(), parsedDoc);
            log.info("ETL 分块完成: docId={}, chunk 数={}", docId, chunks.size());

            if (chunks.isEmpty()) {
                updateStatus(docId, EtlStatus.COMPLETED);
                return CompletableFuture.completedFuture(documentRepository.findById(docId).orElse(null));
            }

            // ── 阶段 3: Embedding ──
            updateStatus(docId, EtlStatus.EMBEDDING);
            List<List<Float>> vectors = embedChunks(chunks);
            log.info("ETL Embedding 完成: docId={}, 向量数={}", docId, vectors.size());

            // ── 阶段 4: 双写 (Load) ──
            updateStatus(docId, EtlStatus.INDEXING);
            Document doc = documentRepository.findById(docId)
                    .orElseThrow(() -> new RagBusinessException("文档不存在: " + docId));

            // 清理旧数据（增量更新场景）
            milvusStore.deleteByDocId(docId.toString());
            keywordStore.deleteByDocId(docId);

            // 写入 Milvus（向量）
            milvusStore.batchInsert(chunks, vectors);

            // 写入 PostgreSQL（关键字索引 + 元数据）
            keywordStore.batchSave(chunks, docId, doc.getKbId());

            // ── 阶段 5: 更新状态 ──
            doc.setParseStatus(EtlStatus.COMPLETED.name());
            doc.setChunkCount(chunks.size());
            documentRepository.save(doc);

            log.info("ETL 全流程完成: docId={}, chunk 数={}, 总耗时={}ms",
                    docId, chunks.size(), System.currentTimeMillis() - startTime);
            return CompletableFuture.completedFuture(doc);

        } catch (Exception e) {
            log.error("ETL 处理失败: docId={}, fileName={}", docId, fileName, e);
            updateStatusWithError(docId, e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 批量 embedding 分块内容
     *
     * 调用 Spring AI EmbeddingModel（DashScope text-embedding-v3），
     * 分批处理避免单次请求过大。
     */
    private List<List<Float>> embedChunks(List<TextChunk> chunks) {
        List<List<Float>> allVectors = new ArrayList<>();
        int batchSize = 20;  // 每批最多 20 条

        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<String> batchTexts = chunks.subList(i, end).stream()
                    .map(TextChunk::content)
                    .toList();

            try {
                // Spring AI 1.0.0 使用 call() + EmbeddingRequest 替代已移除的 embed(String[])
                EmbeddingResponse response = embeddingModel.call(
                        new EmbeddingRequest(batchTexts, null));
                for (Embedding embedding : response.getResults()) {
                    float[] vector = embedding.getOutput();
                    List<Float> floatList = new ArrayList<>(vector.length);
                    for (float v : vector) {
                        floatList.add(v);
                    }
                    allVectors.add(floatList);
                }
            } catch (Exception e) {
                log.error("Embedding 批次失败: batch=[{}, {}), 跳过此批次", i, end, e);
                // 失败的 chunk 用零向量填充，避免阻断后续
                for (int j = i; j < end; j++) {
                    allVectors.add(new ArrayList<>());
                }
            }
        }
        return allVectors;
    }

    /**
     * 更新文档 ETL 状态
     */
    @Transactional
    protected void updateStatus(UUID docId, EtlStatus status) {
        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setParseStatus(status.name());
            documentRepository.save(doc);
        });
    }

    /**
     * 更新文档为失败状态并记录错误信息
     */
    @Transactional
    protected void updateStatusWithError(UUID docId, String errorMessage) {
        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setParseStatus(EtlStatus.FAILED.name());
            doc.setErrorMessage(errorMessage);
            documentRepository.save(doc);
        });
    }
}
