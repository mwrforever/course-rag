package com.commerce.rag.etl.store;

import com.commerce.rag.etl.chunker.TextChunk;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.MutationResultWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Milvus 向量存储服务
 *
 * 负责：
 * 1. 初始化 Milvus collection（启动时自动创建）
 * 2. 批量写入 chunk 的 dense_vector
 * 3. 按 doc_id 删除旧向量（增量更新场景）
 * 4. 向量检索（供 retrieval 模块调用）
 *
 * Collection Schema:
 * - chunk_id (VARCHAR, primary key)
 * - doc_id (VARCHAR)
 * - kb_id (VARCHAR)
 * - content (VARCHAR)
 * - heading_path (VARCHAR)
 * - dense_vector (FLOAT_VECTOR, dim=1024)
 * - chunk_index (INT32)
 * - token_count (INT32)
 * - active_count (INT32)
 * - updated_at (INT64)
 */
@Slf4j
@Component
public class MilvusVectorStore {

    private final MilvusServiceClient milvusClient;

    @Value("${milvus.collection-name:knowledge_chunks}")
    private String collectionName;

    @Value("${milvus.embedding-dim:1024}")
    private int embeddingDim;

    public MilvusVectorStore(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    /**
     * 启动时检查并创建 Milvus collection
     */
    @PostConstruct
    public void initCollection() {
        R<Boolean> hasCollection = milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());

        if (hasCollection.getData() != null && hasCollection.getData()) {
            log.info("Milvus collection 已存在: {}", collectionName);
            return;
        }

        log.info("创建 Milvus collection: {}, dim={}", collectionName, embeddingDim);

        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .addFieldType(FieldType.newBuilder()
                        .withName("chunk_id").withDataType(DataType.VarChar)
                        .withMaxLength(64).withPrimaryKey(true).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("doc_id").withDataType(DataType.VarChar)
                        .withMaxLength(64).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("kb_id").withDataType(DataType.VarChar)
                        .withMaxLength(64).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("content").withDataType(DataType.VarChar)
                        .withMaxLength(65535).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("heading_path").withDataType(DataType.VarChar)
                        .withMaxLength(500).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("dense_vector").withDataType(DataType.FloatVector)
                        .withDimension(embeddingDim).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("chunk_index").withDataType(DataType.Int32).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("token_count").withDataType(DataType.Int32).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("active_count").withDataType(DataType.Int32).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("updated_at").withDataType(DataType.Int64).build())
                .build();

        milvusClient.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withSchema(schema)
                .build());

        // 创建向量索引：IVF_FLAT + COSINE
        milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("dense_vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\": 128}")
                .build());

        // 加载 collection 到内存
        milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());

        log.info("Milvus collection 创建完成并已加载: {}", collectionName);
    }

    /**
     * 批量写入 chunk 向量到 Milvus
     *
     * @param chunks   分块列表
     * @param vectors  对应的向量列表（与 chunks 一一对应）
     */
    public void batchInsert(List<TextChunk> chunks, List<List<Float>> vectors) {
        if (chunks.isEmpty() || vectors.isEmpty()) return;

        List<String> chunkIds = new ArrayList<>();
        List<String> docIds = new ArrayList<>();
        List<String> kbIds = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<String> headingPaths = new ArrayList<>();
        List<Integer> chunkIndexes = new ArrayList<>();
        List<Integer> tokenCounts = new ArrayList<>();
        List<Integer> activeCounts = new ArrayList<>();
        List<Long> updatedTimes = new ArrayList<>();

        long now = System.currentTimeMillis();
        for (TextChunk chunk : chunks) {
            String id = UUID.randomUUID().toString();
            chunkIds.add(id);
            docIds.add(chunk.metadata().docId());
            kbIds.add("");  // 由 ETL pipeline 填充
            contents.add(truncateContent(chunk.content()));
            headingPaths.add(chunk.metadata().headingPath() != null
                    ? chunk.metadata().headingPath() : "");
            chunkIndexes.add(chunk.metadata().chunkIndex());
            tokenCounts.add(chunk.metadata().tokenCount());
            activeCounts.add(0);
            updatedTimes.add(now);
        }

        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field("chunk_id", chunkIds),
                new InsertParam.Field("doc_id", docIds),
                new InsertParam.Field("kb_id", kbIds),
                new InsertParam.Field("content", contents),
                new InsertParam.Field("heading_path", headingPaths),
                new InsertParam.Field("dense_vector", vectors),
                new InsertParam.Field("chunk_index", chunkIndexes),
                new InsertParam.Field("token_count", tokenCounts),
                new InsertParam.Field("active_count", activeCounts),
                new InsertParam.Field("updated_at", updatedTimes)
        );

        R<MutationResult> result = milvusClient.insert(InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build());

        if (result.getStatus() != R.Status.Success.getCode()) {
            log.error("Milvus 批量写入失败: {}", result.getMessage());
            throw new RuntimeException("Milvus 写入失败: " + result.getMessage());
        }

        MutationResultWrapper wrapper = new MutationResultWrapper(result.getData());
        log.info("Milvus 写入成功: {} 条", wrapper.getInsertCnt());
    }

    /**
     * 按 doc_id 删除旧向量（增量更新时先删后写）
     *
     * @param docId 文档 ID
     */
    public void deleteByDocId(String docId) {
        R<MutationResult> result = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("doc_id == '" + docId + "'")
                .build());

        if (result.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus 删除 doc_id={} 失败: {}", docId, result.getMessage());
        }
    }

    /**
     * 截断内容以适应 Milvus VARCHAR 最大长度
     */
    private String truncateContent(String content) {
        // Milvus VARCHAR 最大 65535 字节
        if (content.length() > 16000) {
            return content.substring(0, 16000);
        }
        return content;
    }
}
