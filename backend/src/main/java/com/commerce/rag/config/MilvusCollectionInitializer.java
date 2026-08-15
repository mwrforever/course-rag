package com.commerce.rag.config;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq.CollectionSchema;
import io.milvus.v2.service.collection.request.CreateCollectionReq.Function;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Milvus Collection 自动初始化器（v2 API）—— 应用启动时幂等创建 {@code knowledge_chunks}
 *
 * <p>核心职责：
 * <ul>
 *   <li>实现 {@link ApplicationRunner}，Spring Boot 启动后自动执行</li>
 *   <li><b>幂等</b>：先检查 Collection 是否存在，存在则跳过，不存在才创建</li>
 *   <li>12 字段 Schema + BM25 Function + 4 索引一步创建（v2 API：{@code indexParams} 随 {@code createCollection}）</li>
 *   <li>创建后调用 {@code loadCollection} 加载到内存</li>
 *   <li>通过配置开关 {@code milvus.auto-create-collection} 控制是否启用（默认 true）</li>
 *   <li><b>异常降级</b>：Milvus 不可达时 log warn 并跳过，不阻断应用启动</li>
 * </ul>
 *
 * <p>Collection Schema（12 字段，必须与 SearchKnowledgeTool / EtlPipeline 精确匹配）：
 * <pre>
 * | 字段名           | 类型                 | 约束 / 备注                      |
 * | chunk_id         | VARCHAR(64)          | Primary Key, autoID=false       |
 * | doc_id           | VARCHAR(64)          | 文档 ID                          |
 * | kb_id            | VARCHAR(64)          | 知识库 ID                        |
 * | content          | VARCHAR(65535)       | enableAnalyzer=true ← BM25 输入  |
 * | heading_path     | VARCHAR(500)         | 标题导航路径                     |
 * | dense_vector     | FLOAT_VECTOR(1024)   | text-embedding-v4 dense 向量     |
 * | sparse_vector    | SPARSE_FLOAT_VECTOR  | 服务端 BM25 Function 自动生成    |
 * | chunk_index      | INT32                | 分片序号                         |
 * | token_count      | INT32                | token 数量                       |
 * | collection_type  | VARCHAR(20)          | TECHNICAL_QA / COURSE_INFO       |
 * | course_id        | VARCHAR(64)          | DEFAULT / 具体课程 ID            |
 * | updated_at       | INT64                | Unix epoch 秒                    |
 * </pre>
 *
 * <p>Function（1 个）：BM25 — input=[content], output=[sparse_vector]
 * <p>索引（4 个，随 Collection 一起创建）：
 * <ul>
 *   <li>dense_vector: HNSW, COSINE, M=16, efConstruction=200</li>
 *   <li>sparse_vector: SPARSE_INVERTED_INDEX, BM25</li>
 *   <li>collection_type: INVERTED（标量索引）</li>
 *   <li>course_id: INVERTED（标量索引）</li>
 * </ul>
 *
 * @author commerce-rag
 * @see MilvusConfig
 */
@Component
public class MilvusCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MilvusCollectionInitializer.class);

    // ── Collection 名称（公开常量，供 SearchKnowledgeTool / EtlPipeline 引用）──
    public static final String COLLECTION_NAME = "knowledge_chunks";

    // ── 字段名常量（公开，供 SearchKnowledgeTool / EtlPipeline 引用，确保三方一致）──
    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_DOC_ID = "doc_id";
    public static final String FIELD_KB_ID = "kb_id";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_HEADING_PATH = "heading_path";
    public static final String FIELD_DENSE_VECTOR = "dense_vector";
    public static final String FIELD_SPARSE_VECTOR = "sparse_vector";
    public static final String FIELD_CHUNK_INDEX = "chunk_index";
    public static final String FIELD_TOKEN_COUNT = "token_count";
    public static final String FIELD_COLLECTION_TYPE = "collection_type";
    public static final String FIELD_COURSE_ID = "course_id";
    public static final String FIELD_UPDATED_AT = "updated_at";

    // ── 字段长度常量 ──
    private static final int MAX_LEN_CHUNK_ID = 64;
    private static final int MAX_LEN_DOC_ID = 64;
    private static final int MAX_LEN_KB_ID = 64;
    private static final int MAX_LEN_CONTENT = 65535;
    private static final int MAX_LEN_HEADING_PATH = 500;
    private static final int MAX_LEN_COLLECTION_TYPE = 20;
    private static final int MAX_LEN_COURSE_ID = 64;

    private final MilvusClientV2 milvusClientV2;
    private final String collectionName;
    private final int embeddingDim;
    private final int hnswM;
    private final int hnswEfConstruction;
    private final boolean autoCreateCollection;

    /**
     * 构造函数 —— 通过 {@code @Value} 注入所有配置参数
     *
     * @param milvusClientV2       Milvus v2 客户端（由 {@link MilvusConfig} 创建）
     * @param collectionName       Collection 名称（默认 knowledge_chunks）
     * @param embeddingDim         向量维度（text-embedding-v4 输出 1024）
     * @param hnswM                HNSW 索引 M 参数
     * @param hnswEfConstruction   HNSW 索引 efConstruction 参数
     * @param autoCreateCollection 是否启用自动创建（默认 true）
     */
    public MilvusCollectionInitializer(
            MilvusClientV2 milvusClientV2,
            @Value("${milvus.collection-name:knowledge_chunks}") String collectionName,
            @Value("${milvus.embedding-dim:1024}") int embeddingDim,
            @Value("${milvus.hnsw-m:16}") int hnswM,
            @Value("${milvus.hnsw-ef-construction:200}") int hnswEfConstruction,
            @Value("${milvus.auto-create-collection:true}") boolean autoCreateCollection) {
        this.milvusClientV2 = milvusClientV2;
        this.collectionName = collectionName;
        this.embeddingDim = embeddingDim;
        this.hnswM = hnswM;
        this.hnswEfConstruction = hnswEfConstruction;
        this.autoCreateCollection = autoCreateCollection;
    }

    /**
     * Spring Boot 启动后自动执行入口
     *
     * <p>异常降级策略：任何异常（含 Milvus 不可达）均 log warn 并跳过，
     * 不抛出异常，不阻断应用启动。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!autoCreateCollection) {
            log.info("Milvus 自动创建 Collection 已禁用 (milvus.auto-create-collection=false)，跳过初始化");
            return;
        }

        log.info("开始检查 Milvus Collection: name={}", collectionName);
        try {
            initCollection();
        } catch (Exception e) {
            // Milvus 不可达或创建失败时降级，不阻断应用启动
            log.warn("Milvus Collection 初始化失败（应用继续启动）: collection={}, error={}", collectionName, e.getMessage());
        }
    }

    /**
     * 执行 Collection 初始化的完整流程
     *
     * <p>流程：
     * <ol>
     *   <li>检查 Collection 是否存在（幂等保证）</li>
     *   <li>不存在时创建 Collection（12 字段 Schema + BM25 Function + 4 索引一步创建）</li>
     *   <li>加载 Collection 到内存</li>
     * </ol>
     *
     * @throws RuntimeException Milvus 操作失败时抛出（由 {@link #run} 捕获降级）
     */
    private void initCollection() {
        // 1. 检查 Collection 是否存在（幂等）
        Boolean exists = milvusClientV2.hasCollection(
                HasCollectionReq.builder().collectionName(collectionName).build());

        if (Boolean.TRUE.equals(exists)) {
            log.info("Milvus Collection 已存在，跳过创建: name={}", collectionName);
            return;
        }

        // 2. 构建 Schema + 索引，一步创建 Collection
        log.info("Milvus Collection 不存在，开始创建: name={}", collectionName);
        CollectionSchema schema = buildCollectionSchema();
        List<IndexParam> indexParams = buildIndexParams();

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build();

        milvusClientV2.createCollection(createReq);
        log.info("Milvus Collection 创建成功（含 Schema + Function + 索引）: name={}", collectionName);

        // 3. 加载 Collection 到内存（search 前必须 load）
        milvusClientV2.loadCollection(
                LoadCollectionReq.builder().collectionName(collectionName).build());
        log.info("Milvus Collection 加载完成: name={}", collectionName);
    }

    /**
     * 构建 Collection Schema —— 12 个字段 + BM25 Function
     *
     * <p>字段定义必须与 {@code SearchKnowledgeTool.OUTPUT_FIELDS} 和
     * {@code EtlPipeline.insertToMilvus} 的字段完全一致。
     *
     * @return CollectionSchema
     */
    private CollectionSchema buildCollectionSchema() {
        CollectionSchema schema = CollectionSchema.builder().build();

        // 1. chunk_id — 主键（VARCHAR(64)，手动指定，非自增）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_ID)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_CHUNK_ID)
                .isPrimaryKey(true)
                .autoID(false)
                .build());

        // 2. doc_id — 文档 ID
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_DOC_ID)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_DOC_ID)
                .build());

        // 3. kb_id — 知识库 ID
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_KB_ID)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_KB_ID)
                .build());

        // 4. content — 分片文本内容（enableAnalyzer=true，BM25 Function 输入）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CONTENT)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_CONTENT)
                .enableAnalyzer(true)
                .build());

        // 5. heading_path — 标题导航路径
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_HEADING_PATH)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_HEADING_PATH)
                .build());

        // 6. dense_vector — dense 向量（FLOAT_VECTOR, dim=1024）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_DENSE_VECTOR)
                .dataType(DataType.FloatVector)
                .dimension(embeddingDim)
                .build());

        // 7. sparse_vector — sparse 向量（SPARSE_FLOAT_VECTOR，服务端 BM25 Function 自动生成）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_SPARSE_VECTOR)
                .dataType(DataType.SparseFloatVector)
                .build());

        // 8. chunk_index — 分片序号
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_INDEX)
                .dataType(DataType.Int32)
                .build());

        // 9. token_count — token 数量
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_TOKEN_COUNT)
                .dataType(DataType.Int32)
                .build());

        // 10. collection_type — 标量路由字段（TECHNICAL_QA / COURSE_INFO）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_COLLECTION_TYPE)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_COLLECTION_TYPE)
                .build());

        // 11. course_id — 课程 ID（DEFAULT 表示无特定课程）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_COURSE_ID)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_COURSE_ID)
                .build());

        // 12. updated_at — 更新时间戳（Unix epoch 秒）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_UPDATED_AT)
                .dataType(DataType.Int64)
                .build());

        // BM25 Function: content → sparse_vector（服务端自动生成 sparse 向量）
        schema.addFunction(Function.builder()
                .functionType(FunctionType.BM25)
                .name("bm25_func")
                .inputFieldNames(List.of(FIELD_CONTENT))
                .outputFieldNames(List.of(FIELD_SPARSE_VECTOR))
                .build());

        return schema;
    }

    /**
     * 构建 4 个索引参数（随 Collection 一起创建）
     *
     * <ul>
     *   <li>dense_vector: HNSW, COSINE, M=16, efConstruction=200</li>
     *   <li>sparse_vector: SPARSE_INVERTED_INDEX, BM25</li>
     *   <li>collection_type: INVERTED（标量索引）</li>
     *   <li>course_id: INVERTED（标量索引）</li>
     * </ul>
     *
     * @return 索引参数列表
     */
    private List<IndexParam> buildIndexParams() {
        List<IndexParam> indexParams = new ArrayList<>();

        // 1. dense_vector: HNSW + COSINE
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_DENSE_VECTOR)
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", hnswM, "efConstruction", hnswEfConstruction))
                .build());

        // 2. sparse_vector: SPARSE_INVERTED_INDEX + BM25
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_SPARSE_VECTOR)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build());

        // 3. collection_type: INVERTED（标量索引，加速过滤表达式）
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_COLLECTION_TYPE)
                .indexType(IndexParam.IndexType.INVERTED)
                .build());

        // 4. course_id: INVERTED（标量索引，加速过滤表达式）
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_COURSE_ID)
                .indexType(IndexParam.IndexType.INVERTED)
                .build());

        return indexParams;
    }
}
