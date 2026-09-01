package com.commerce.rag.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Milvus 向量数据库配置属性（PERF-04 rpcDeadlineMs 配置化 + BUG-12 @Value 收敛）。
 * 绑定 application.yml 中 {@code milvus.*} 配置项。
 *
 * <p>宪法 D.5.10：SDK 显式配置 rpcDeadlineMs——Milvus SDK 默认 rpcDeadlineMs=0 表示
 * 无截止时间，gRPC 调用挂起时调用线程（检索/ETL）会被永久占用；显式设置后挂起从
 * 「线程永久占用」收窄为有界超时。
 *
 * <p>BUG-12：原 {@code config/MilvusConfig}（host/port）、{@code config/MilvusCollectionInitializer}
 * （collection-name/embedding-dim/hnsw-m/hnsw-ef-construction/auto-create-collection）、
 * {@code bot/tool/SearchKnowledgeTool}（sparse-bm25-k）、{@code service/impl/EpisodicMemoryServiceImpl}
 * （hnsw-ef）各自经 {@code @Value} 散落注入，现并入本属性类统一强类型绑定（宪法 A.2.2）。
 * 各默认值与原 {@code @Value} 兜底值逐一相同，行为零变化。
 *
 * <pre>
 * milvus:
 *   host: localhost
 *   port: 19530
 *   rpc-deadline-ms: 30000
 *   collection-name: knowledge_chunks
 *   embedding-dim: 1024
 *   hnsw-m: 16
 *   hnsw-ef-construction: 200
 *   hnsw-ef: 64
 *   sparse-bm25-k: 60
 *   auto-create-collection: true
 * </pre>
 *
 * @param rpcDeadlineMs      单次 RPC 调用截止时间（毫秒，默认 30000=30s，从 30s 起步防误杀
 *                          大 batch 插入/检索；允许为空——未配置时走 @DefaultValue 默认值，
 *                          配置来源为 application.yml，运维按部署环境调整）
 * @param host               Milvus 服务地址（默认 localhost，来自 MILVUS_HOST 环境变量）
 * @param port               Milvus 服务端口（默认 19530，来自 MILVUS_PORT 环境变量）
 * @param collectionName     知识 chunk collection 名称（默认 knowledge_chunks）
 * @param embeddingDim       向量维度（必须与 embedding 模型输出一致，text-embedding-v4=1024；
 *                          维度变更触发 collection 重建流程，宪法 D.5.1）
 * @param hnswM              HNSW 索引 M 参数（每层图出边数）
 * @param hnswEfConstruction HNSW 建索引 efConstruction 参数（索引期搜索宽度）
 * @param hnswEf             HNSW 检索 ef 参数（查询期搜索宽度，经历记忆召回与索引参数同源配置化）
 * @param sparseBm25K        sparse+dense 混合检索 RRF 融合常数 k（宪法 D.5.3 推荐区间实测调优）
 * @param autoCreateCollection 启动时是否自动创建 collection（幂等，存在则跳过）
 */
@Validated
@ConfigurationProperties(prefix = "milvus")
public record MilvusProperties(
        @Min(1000) @DefaultValue("30000") long rpcDeadlineMs,
        @DefaultValue("localhost") @NotBlank String host,
        @DefaultValue("19530") @Min(1) @Max(65535) int port,
        @DefaultValue("knowledge_chunks") @NotBlank String collectionName,
        @DefaultValue("1024") @Min(1) int embeddingDim,
        @DefaultValue("16") @Min(1) int hnswM,
        @DefaultValue("200") @Min(1) int hnswEfConstruction,
        @DefaultValue("64") @Min(1) int hnswEf,
        @DefaultValue("60") @Min(1) int sparseBm25K,
        @DefaultValue("true") boolean autoCreateCollection) {}
