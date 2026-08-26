package com.commerce.rag.config;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq.CollectionSchema;
import io.milvus.v2.service.collection.request.CreateCollectionReq.Function;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Milvus Collection 自动初始化器（v2 API）—— 应用启动时比对重建 {@code knowledge_chunks} 与 {@code memory_chunks} 双集合
 *
 * <p>核心职责：
 * <ul>
 *   <li>实现 {@link ApplicationRunner}，Spring Boot 启动后自动执行</li>
 *   <li><b>比对重建</b>：Collection 存在时 describe 比对实际字段集与期望 14 字段集，
 *       完全匹配才跳过；不匹配（历史版本 schema）drop 重建</li>
 *   <li>14 字段 Schema + BM25 Function + 3 索引一步创建（v2 API：{@code indexParams} 随 {@code createCollection}）</li>
 *   <li>创建后调用 {@code loadCollection} 加载到内存</li>
 *   <li>通过配置开关 {@code milvus.auto-create-collection} 控制是否启用（默认 true）</li>
 *   <li>{@code memory_chunks} 第二集合（spec §8.5 召回索引，PG 为事实源、Milvus 仅索引，6 字段独立比对）</li>
 *   <li><b>异常降级</b>：Milvus 不可达或创建失败时 log warn 并跳过，不阻断应用启动</li>
 *   <li><b>schema 版本标记</b>（2026-08-26 sparse 整改引入）：knowledge_chunks 创建时把
 *       {@code schema-version:N} 写入 collection description，比对时额外校验版本标记——
 *       字段名比对无法感知字段属性变化（如 content 字段 analyzer 配置），版本递增即触发重建</li>
 * </ul>
 *
 * <p>Collection Schema（14 字段，必须与 SearchKnowledgeTool / EtlPipeline 精确匹配）：
 * <pre>
 * | 字段名           | 类型                 | 约束 / 备注                      |
 * | chunk_id         | VARCHAR(64)          | Primary Key, autoID=false       |
 * | doc_id           | VARCHAR(64)          | 文档 ID                          |
 * | kb_id            | VARCHAR(64)          | 知识库 ID                        |
 * | content          | VARCHAR(65535)       | enableAnalyzer=true + jieba（chinese）← BM25 输入 |
 * | heading_path     | VARCHAR(500)         | 标题导航路径                     |
 * | dense_vector     | FLOAT_VECTOR(1024)   | text-embedding-v4 dense 向量     |
 * | sparse_vector    | SPARSE_FLOAT_VECTOR  | 服务端 BM25 Function 自动生成    |
 * | chunk_index      | INT32                | 分片序号                         |
 * | token_count      | INT32                | token 数量                       |
 * | course_id        | VARCHAR(64)          | DEFAULT / 具体课程 ID            |
 * | content_type     | VARCHAR(20)          | 分片内容类型（text / image / table）|
 * | image_url        | VARCHAR(1000)        | 图片分片的 MinIO objectKey        |
 * | sha256           | VARCHAR(64)          | 归一化内容哈希（检索侧防御去重用） |
 * | updated_at       | INT64                | Unix epoch 秒                    |
 * </pre>
 *
 * <p>Function（1 个）：BM25 — input=[content], output=[sparse_vector]
 * <p>索引（3 个，随 Collection 一起创建）：
 * <ul>
 *   <li>dense_vector: HNSW, COSINE, M=16, efConstruction=200</li>
 *   <li>sparse_vector: SPARSE_INVERTED_INDEX, BM25</li>
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

    /**
     * schema 版本标记（2026-08-26 sparse 整改引入）：写入 knowledge_chunks 的 collection description，
     * 启动比对时校验。递增时机 = 字段属性级变更（analyzer 配置、索引参数等字段名比对感知不到的变化）：
     * 1 → 2 = content 字段启用 jieba 中文分词器（chinese analyzer，sparse 整改）。
     * 包级可见（去 private）：测试引用同一事实源，避免字面量漂移。
     */
    static final int SCHEMA_VERSION = 2;

    static final String SCHEMA_VERSION_MARKER = "schema-version:" + SCHEMA_VERSION;

    /** content 字段 analyzer 配置：chinese = jieba 分词器 + cnalphanumonly 过滤（Milvus 2.6 内置，官方 v2.6 文档） */
    private static final Map<String, Object> ANALYZER_PARAMS_CHINESE = Map.of("type", "chinese");

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
    public static final String FIELD_COURSE_ID = "course_id";
    public static final String FIELD_CONTENT_TYPE = "content_type";
    public static final String FIELD_IMAGE_URL = "image_url";
    public static final String FIELD_SHA256 = "sha256";
    public static final String FIELD_UPDATED_AT = "updated_at";

    // ── memory_chunks Collection（spec §8.5 召回索引，PG 为事实源）──
    public static final String COLLECTION_MEMORY = "memory_chunks";

    // ── memory_chunks 字段名常量（供 Task 6 EpisodicMemoryService 引用）──
    public static final String FIELD_MEMORY_ID = "memory_id";
    public static final String FIELD_MEMORY_USER_ID = "user_id";
    public static final String FIELD_MEMORY_TYPE = "type";
    public static final String FIELD_MEMORY_VALIDITY = "validity";
    public static final String FIELD_MEMORY_EMBEDDING = "embedding";
    public static final String FIELD_MEMORY_UPDATED_AT = "updated_at";

    /** 期望 schema 字段全集（14 个）——启动时 describe 比对，不匹配则 drop 重建 */
    private static final List<String> EXPECTED_FIELD_NAMES = List.of(
            FIELD_CHUNK_ID,
            FIELD_DOC_ID,
            FIELD_KB_ID,
            FIELD_CONTENT,
            FIELD_HEADING_PATH,
            FIELD_DENSE_VECTOR,
            FIELD_SPARSE_VECTOR,
            FIELD_CHUNK_INDEX,
            FIELD_TOKEN_COUNT,
            FIELD_COURSE_ID,
            FIELD_CONTENT_TYPE,
            FIELD_IMAGE_URL,
            FIELD_SHA256,
            FIELD_UPDATED_AT);

    /** 期望 memory_chunks schema 字段全集（6 个）——启动时 describe 比对，不匹配则 drop 重建（spec §8.5） */
    private static final List<String> EXPECTED_MEMORY_FIELD_NAMES = List.of(
            FIELD_MEMORY_ID,
            FIELD_MEMORY_USER_ID,
            FIELD_MEMORY_TYPE,
            FIELD_MEMORY_VALIDITY,
            FIELD_MEMORY_EMBEDDING,
            FIELD_MEMORY_UPDATED_AT);

    // ── 字段长度常量 ──
    private static final int MAX_LEN_CHUNK_ID = 64;
    private static final int MAX_LEN_DOC_ID = 64;
    private static final int MAX_LEN_KB_ID = 64;
    private static final int MAX_LEN_CONTENT = 65535;
    private static final int MAX_LEN_HEADING_PATH = 500;
    private static final int MAX_LEN_COURSE_ID = 64;
    private static final int MAX_LEN_CONTENT_TYPE = 20;
    private static final int MAX_LEN_IMAGE_URL = 1000;
    private static final int MAX_LEN_SHA256 = 64;
    private static final int MAX_LEN_MEMORY_ID = 64;
    private static final int MAX_LEN_MEMORY_USER_ID = 64;
    private static final int MAX_LEN_MEMORY_TYPE = 50;
    private static final int MAX_LEN_MEMORY_VALIDITY = 20;

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

        log.info("开始检查 Milvus Collections: knowledge={}, memory={}", collectionName, COLLECTION_MEMORY);
        try {
            // 1. 既有 knowledge_chunks（schema 版本校验，spec §12 重建口径不变）
            ensureCollection(collectionName, buildKnowledgeCollectionSchema(), buildKnowledgeIndexParams());
            // 2. memory_chunks（spec §8.5 召回索引，独立集合）
            ensureCollection(COLLECTION_MEMORY, buildMemoryCollectionSchema(), buildMemoryIndexParams());
        } catch (Exception e) {
            // Milvus 不可达或创建失败时降级，不阻断应用启动
            log.warn("Milvus Collection 初始化失败（应用继续启动）: error={}", e.getMessage());
        }
    }

    /**
     * 通用 Collection 确保逻辑（存在则比对 schema，不匹配 drop 重建；不存在直接创建）
     *
     * <p>流程：
     * <ol>
     *   <li>检查 Collection 是否存在</li>
     *   <li>已存在时 describe 比对 schema（按 name 选择期望字段集），匹配则跳过；不匹配（历史版本）drop 重建</li>
     *   <li>创建 Collection（传入 Schema + 索引一步创建）</li>
     *   <li>加载 Collection 到内存</li>
     * </ol>
     *
     * @param name    Collection 名称（如 knowledge_chunks / memory_chunks）
     * @param schema  期望的 Collection Schema
     * @param indexes 随 Collection 一起创建的索引参数列表
     * @throws RuntimeException Milvus 操作失败时抛出（由 {@link #run} 捕获降级）
     */
    private void ensureCollection(String name, CollectionSchema schema, List<IndexParam> indexes) {
        // 1. 检查 Collection 是否存在
        Boolean exists = milvusClientV2.hasCollection(
                HasCollectionReq.builder().collectionName(name).build());

        if (Boolean.TRUE.equals(exists)) {
            if (schemaMatches(name)) {
                log.info("Milvus Collection 已存在且 schema 匹配，跳过创建: name={}", name);
                return;
            }
            // S1 §12：schema 不匹配（历史版本）→ drop 重建（开发库无业务数据，用户已拍板）
            log.warn("Milvus Collection schema 不匹配，drop 重建: name={}", name);
            milvusClientV2.dropCollection(
                    DropCollectionReq.builder().collectionName(name).build());
        }

        // 2. 创建 Collection（Schema + 索引一步创建；仅 knowledge_chunks 写入 schema 版本标记——
        //    memory_chunks 不参与版本比对，写 knowledge 的标记会造成版本号语义串用，故传 null）
        log.info("开始创建 Milvus Collection: name={}", name);
        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(name)
                .description(COLLECTION_NAME.equals(name) ? SCHEMA_VERSION_MARKER : null)
                .collectionSchema(schema)
                .indexParams(indexes)
                .build();

        milvusClientV2.createCollection(createReq);
        log.info("Milvus Collection 创建成功（含 Schema + Function + 索引）: name={}", name);

        // 3. 加载 Collection 到内存
        milvusClientV2.loadCollection(
                LoadCollectionReq.builder().collectionName(name).build());
        log.info("Milvus Collection 加载完成: name={}", name);
    }

    /**
     * describe 比对实际 schema 与期望 schema（字段集双向包含 + knowledge_chunks 额外校验版本标记）
     *
     * <p>字段名比对感知不到字段属性变化（如 content 的 analyzer 配置），故 knowledge_chunks
     * 额外要求 collection description 含 {@link #SCHEMA_VERSION_MARKER}——旧版本（无标记）视为不匹配 drop 重建。
     * memory_chunks 不参与版本比对（schema 无属性级变更，字段名比对足够，避免误重建生产数据）。
     *
     * <p>describe 异常或返回空字段信息时保守返回 true（视为匹配）——不因 Milvus 瞬时故障误删有数据 Collection。
     *
     * @param name Collection 名称（决定期望字段集：knowledge_chunks 14 字段 / memory_chunks 6 字段）
     */
    private boolean schemaMatches(String name) {
        try {
            DescribeCollectionResp resp = milvusClientV2.describeCollection(
                    DescribeCollectionReq.builder().collectionName(name).build());
            // describe 返回 null/空字段信息（未抛异常）同属异常态——保守视为匹配，不做破坏性 drop。
            // SDK 的 DescribeCollectionResp 构造对 fieldNames 空值默认填充空列表，故空列表也须覆盖
            if (resp == null
                    || resp.getFieldNames() == null
                    || resp.getFieldNames().isEmpty()) {
                return true;
            }
            Set<String> actual = new HashSet<>(resp.getFieldNames());
            // 按集合选择期望字段集：knowledge 14 字段；memory 6 字段（spec §8.5）
            List<String> expectedFieldNames =
                    COLLECTION_MEMORY.equals(name) ? EXPECTED_MEMORY_FIELD_NAMES : EXPECTED_FIELD_NAMES;
            boolean fieldMatch = actual.containsAll(expectedFieldNames) && expectedFieldNames.containsAll(actual);
            if (!fieldMatch) {
                return false;
            }
            // knowledge_chunks 版本标记校验：字段属性级变更（analyzer 配置等）字段名感知不到，
            // 版本标记不符（旧 collection description 为空或版本落后）→ 不匹配触发重建。
            // 用注入的 collectionName 而非静态常量——配置覆盖为非默认值时校验键保持一致；
            // equals 精确匹配（防 contains 前缀误判：版本 2 不会误匹配未来的 schema-version:2x）
            if (collectionName.equals(name)) {
                String desc = resp.getDescription();
                return SCHEMA_VERSION_MARKER.equals(desc);
            }
            return true;
        } catch (Exception e) {
            log.warn("Milvus describe 失败，保守视为 schema 匹配（跳过重建）: collection={}, error={}", name, e.getMessage());
            return true;
        }
    }

    /**
     * 构建 knowledge_chunks Schema —— 14 个字段 + BM25 Function
     *
     * <p>字段定义必须与 {@code SearchKnowledgeTool.OUTPUT_FIELDS} 和
     * {@code EtlPipeline.insertToMilvus} 的字段完全一致。
     *
     * @return CollectionSchema
     */
    private CollectionSchema buildKnowledgeCollectionSchema() {
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

        // 4. content — 分片文本内容（enableAnalyzer=true + jieba 中文分词，BM25 Function 输入；
        //    sparse 整改 2026-08-26：不加 jieba 中文会致服务端分词崩溃，issue #1402 实证）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CONTENT)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_CONTENT)
                .enableAnalyzer(true)
                .analyzerParams(ANALYZER_PARAMS_CHINESE)
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

        // 10. course_id — 课程 ID（DEFAULT 表示无特定课程）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_COURSE_ID)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_COURSE_ID)
                .build());

        // 11. content_type — 分片内容类型（text / image / table）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CONTENT_TYPE)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_CONTENT_TYPE)
                .build());

        // 12. image_url — 图片分片的 MinIO objectKey（仅 image 分片有值）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_IMAGE_URL)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_IMAGE_URL)
                .build());

        // 13. sha256 — 归一化内容哈希（检索侧防御去重用，计划 2/5 消费）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_SHA256)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_SHA256)
                .build());

        // 14. updated_at — 更新时间戳（Unix epoch 秒）
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
     * 构建 knowledge_chunks 的 3 个索引参数（随 Collection 一起创建）
     *
     * <ul>
     *   <li>dense_vector: HNSW, COSINE, M=16, efConstruction=200</li>
     *   <li>sparse_vector: SPARSE_INVERTED_INDEX, BM25</li>
     *   <li>course_id: INVERTED（标量索引）</li>
     * </ul>
     *
     * @return 索引参数列表
     */
    private List<IndexParam> buildKnowledgeIndexParams() {
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

        // 3. course_id: INVERTED（标量索引，加速过滤表达式）
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_COURSE_ID)
                .indexType(IndexParam.IndexType.INVERTED)
                .build());

        return indexParams;
    }

    /**
     * 构建 memory_chunks Schema —— 6 字段（spec §8.5：仅索引，完整事实回 PG 取数）
     *
     * @return CollectionSchema
     */
    private CollectionSchema buildMemoryCollectionSchema() {
        CollectionSchema schema = CollectionSchema.builder().build();

        // 1. memory_id — 主键（对应 PG 雪花 id 的字符串，VARCHAR(64)）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_MEMORY_ID)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_MEMORY_ID)
                .isPrimaryKey(true)
                .autoID(false)
                .build());

        // 2. user_id — 硬隔离过滤键
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_MEMORY_USER_ID)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_MEMORY_USER_ID)
                .build());

        // 3. type — 记忆分类（白名单枚举序列化，用于按 type 卡召回）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_MEMORY_TYPE)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_MEMORY_TYPE)
                .build());

        // 4. validity — 状态机（recall_history 动态过滤键）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_MEMORY_VALIDITY)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_MEMORY_VALIDITY)
                .build());

        // 5. embedding — dense 向量（text-embedding-v4，1024 维，summary+content 合并向量）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_MEMORY_EMBEDDING)
                .dataType(DataType.FloatVector)
                .dimension(embeddingDim)
                .build());

        // 6. updated_at — 更新时间戳（Unix epoch 秒）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_MEMORY_UPDATED_AT)
                .dataType(DataType.Int64)
                .build());

        return schema;
    }

    /**
     * 构建 memory_chunks 索引 —— embedding HNSW/COSINE + user_id/type/validity INVERTED
     *
     * @return 索引参数列表
     */
    private List<IndexParam> buildMemoryIndexParams() {
        List<IndexParam> indexParams = new ArrayList<>();

        // 1. embedding: HNSW + COSINE（dense 向量召回）
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_MEMORY_EMBEDDING)
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", hnswM, "efConstruction", hnswEfConstruction))
                .build());

        // 2. user_id: INVERTED（硬隔离过滤加速）
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_MEMORY_USER_ID)
                .indexType(IndexParam.IndexType.INVERTED)
                .build());

        // 3. type: INVERTED（按记忆类型卡召回）
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_MEMORY_TYPE)
                .indexType(IndexParam.IndexType.INVERTED)
                .build());

        // 4. validity: INVERTED（状态机动态过滤键）
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_MEMORY_VALIDITY)
                .indexType(IndexParam.IndexType.INVERTED)
                .build());

        return indexParams;
    }
}
