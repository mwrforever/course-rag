package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.properties.MilvusProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MilvusCollectionInitializer 单元测试 —— schema 比对重建逻辑
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class MilvusCollectionInitializerTest {

    @Mock
    private MilvusClientV2 milvusClientV2;

    /** 构造被测初始化器（collection/embedding 维度/HNSW 参数与 application.yml 显式值一致，经属性类注入） */
    private MilvusCollectionInitializer initializer() {
        return new MilvusCollectionInitializer(milvusClientV2, milvusProperties(true));
    }

    /** 被测属性快照：collection=knowledge_chunks、dim=1024、hnswM=16、efConstruction=200，自动创建开关随用例 */
    private MilvusProperties milvusProperties(boolean autoCreateCollection) {
        return new MilvusProperties(
                30000L, "localhost", 19530, "knowledge_chunks", 1024, 16, 200, 64, 60, autoCreateCollection);
    }

    @Test
    @DisplayName("schema 不匹配（缺 sha256）— knowledge/memory 均 drop 重建")
    void schemaMismatch_dropsAndRecreates() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        DescribeCollectionResp desc = DescribeCollectionResp.builder()
                .fieldNames(List.of("chunk_id", "doc_id", "content", "dense_vector")) // 旧 schema
                .build();
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(desc);

        initializer().run(null);

        // run() 依次 ensure knowledge + memory，两集合均 schema 不匹配，各 drop 一次
        verify(milvusClientV2, times(2)).dropCollection(any(DropCollectionReq.class));
    }

    @Test
    @DisplayName("schema 匹配（字段集 + knowledge 版本标记）— 不 drop 不重建")
    void schemaMatches_skipsRebuild() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        // 按集合返回各自完整的期望字段集 → 两集合 schema 均匹配，均不 drop 不重建
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenAnswer(inv -> {
                    DescribeCollectionReq req = inv.getArgument(0);
                    if (MilvusCollectionInitializer.COLLECTION_MEMORY.equals(req.getCollectionName())) {
                        return DescribeCollectionResp.builder()
                                .fieldNames(List.of(
                                        MilvusCollectionInitializer.FIELD_MEMORY_ID,
                                        MilvusCollectionInitializer.FIELD_MEMORY_USER_ID,
                                        MilvusCollectionInitializer.FIELD_MEMORY_TYPE,
                                        MilvusCollectionInitializer.FIELD_MEMORY_VALIDITY,
                                        MilvusCollectionInitializer.FIELD_MEMORY_EMBEDDING,
                                        MilvusCollectionInitializer.FIELD_MEMORY_UPDATED_AT))
                                .build();
                    }
                    return DescribeCollectionResp.builder()
                            // 版本标记引用实现常量（单一事实源，避免字面量漂移）
                            .description(MilvusCollectionInitializer.SCHEMA_VERSION_MARKER)
                            .fieldNames(List.of(
                                    "chunk_id",
                                    "doc_id",
                                    "kb_id",
                                    "content",
                                    "heading_path",
                                    "dense_vector",
                                    "sparse_vector",
                                    "chunk_index",
                                    "token_count",
                                    "course_id",
                                    "content_type",
                                    "image_url",
                                    "sha256",
                                    "updated_at"))
                            .build();
                });

        initializer().run(null);

        verify(milvusClientV2, never()).dropCollection(any(DropCollectionReq.class));
    }

    @Test
    @DisplayName("字段集匹配但版本标记缺失（旧 collection）— knowledge drop 重建，memory 跳过")
    void schemaVersionMismatch_dropsKnowledgeOnly() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        // 两集合字段集均完整，但 knowledge 的 description 为旧版本标记（版本落后）→ 版本比对失败
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenAnswer(inv -> {
                    DescribeCollectionReq req = inv.getArgument(0);
                    if (MilvusCollectionInitializer.COLLECTION_MEMORY.equals(req.getCollectionName())) {
                        return DescribeCollectionResp.builder()
                                .fieldNames(List.of(
                                        MilvusCollectionInitializer.FIELD_MEMORY_ID,
                                        MilvusCollectionInitializer.FIELD_MEMORY_USER_ID,
                                        MilvusCollectionInitializer.FIELD_MEMORY_TYPE,
                                        MilvusCollectionInitializer.FIELD_MEMORY_VALIDITY,
                                        MilvusCollectionInitializer.FIELD_MEMORY_EMBEDDING,
                                        MilvusCollectionInitializer.FIELD_MEMORY_UPDATED_AT))
                                .build();
                    }
                    return DescribeCollectionResp.builder()
                            // 旧版本标记（schema-version:1）→ equals 精确比对失败触发重建
                            .description("schema-version:1")
                            .fieldNames(List.of(
                                    "chunk_id",
                                    "doc_id",
                                    "kb_id",
                                    "content",
                                    "heading_path",
                                    "dense_vector",
                                    "sparse_vector",
                                    "chunk_index",
                                    "token_count",
                                    "course_id",
                                    "content_type",
                                    "image_url",
                                    "sha256",
                                    "updated_at"))
                            .build();
                });

        initializer().run(null);

        // 仅 knowledge_chunks 因版本标记缺失重建；memory 字段匹配跳过
        // ArgumentCaptor 锚定重建对象为 knowledge_chunks（防名字分支写反的假绿）
        ArgumentCaptor<DropCollectionReq> dropCaptor = ArgumentCaptor.forClass(DropCollectionReq.class);
        verify(milvusClientV2, times(1)).dropCollection(dropCaptor.capture());
        assertEquals(
                MilvusCollectionInitializer.COLLECTION_NAME,
                dropCaptor.getValue().getCollectionName());
        ArgumentCaptor<CreateCollectionReq> createCaptor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(milvusClientV2, times(1)).createCollection(createCaptor.capture());
        assertEquals(
                MilvusCollectionInitializer.COLLECTION_NAME,
                createCaptor.getValue().getCollectionName());
    }

    @Test
    @DisplayName("describe 异常 — 保守视为匹配，不误删")
    void describeFailure_treatedAsMatch() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenThrow(new RuntimeException("milvus busy"));

        initializer().run(null);

        verify(milvusClientV2, never()).dropCollection(any(DropCollectionReq.class));
    }

    @Test
    @DisplayName("describe 返回空字段信息（未抛异常）— 保守视为匹配，不 drop 不重建")
    void describeReturnsNullFieldNames_treatedAsMatch() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        // describe 返回 fieldNames 为 null 的响应（SDK 异常态，未抛异常）
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(DescribeCollectionResp.builder().build());

        initializer().run(null);

        verify(milvusClientV2, never()).dropCollection(any(DropCollectionReq.class));
        verify(milvusClientV2, never()).createCollection(any(CreateCollectionReq.class));
    }

    @Test
    @DisplayName("Milvus 不可达 — hasCollection 抛异常，run() 顶层降级不抛、不创建不加载")
    void hasCollectionThrows_degradesGracefully() {
        // Given: hasCollection 抛出异常（模拟 Milvus 不可达）
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class)))
                .thenThrow(new RuntimeException("连接超时: Milvus 不可达"));

        // When: 执行初始化 —— run() 顶层 catch 降级，不应抛出异常阻断应用启动
        initializer().run(null);

        // Then: 不应创建、不应加载（异常已降级）
        verify(milvusClientV2, never()).createCollection(any(CreateCollectionReq.class));
        verify(milvusClientV2, never()).loadCollection(any(LoadCollectionReq.class));
    }

    @Test
    @DisplayName("Collection 不存在 — 直接创建（schema 含新三字段，无 collection_type）")
    void collectionMissing_createsNewSchema() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(false);

        initializer().run(null);

        ArgumentCaptor<CreateCollectionReq> captor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(milvusClientV2, times(2)).createCollection(captor.capture());
        // 第一个请求为 knowledge_chunks（run() 先初始化 knowledge 再 memory）
        CreateCollectionReq knowledgeReq = captor.getAllValues().get(0);
        assertEquals("knowledge_chunks", knowledgeReq.getCollectionName());
        // SDK 2.6.11 无 CollectionSchema.getFieldNames()，取 getFieldSchemaList() 映射字段名
        List<String> fields = knowledgeReq.getCollectionSchema().getFieldSchemaList().stream()
                .map(FieldSchema::getName)
                .toList();
        assertTrue(fields.contains("content_type") && fields.contains("image_url") && fields.contains("sha256"));
        assertTrue(!fields.contains("collection_type"), "新 schema 不应含 collection_type");
        assertEquals(14, fields.size());
        // sparse 整改：knowledge 创建须携带 schema 版本标记（版本不符时启动比对触发重建），
        // memory 创建不写标记（不参与版本比对，避免版本号语义串用）
        assertEquals(MilvusCollectionInitializer.SCHEMA_VERSION_MARKER, knowledgeReq.getDescription());
        CreateCollectionReq memoryReq = captor.getAllValues().get(1);
        assertEquals(MilvusCollectionInitializer.COLLECTION_MEMORY, memoryReq.getCollectionName());
        assertNull(memoryReq.getDescription(), "memory_chunks 不应携带 knowledge 的版本标记");
        // sparse 整改：content 字段启用 jieba 中文分词（chinese analyzer，issue #1402 中文崩溃根因）
        FieldSchema contentField = knowledgeReq.getCollectionSchema().getFieldSchemaList().stream()
                .filter(f -> "content".equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("knowledge schema 缺少 content 字段"));
        assertEquals(Boolean.TRUE, contentField.getEnableAnalyzer());
        assertEquals(Map.of("type", "chinese"), contentField.getAnalyzerParams());
    }

    @Test
    @DisplayName("auto-create-collection=false — 跳过初始化，不调用 hasCollection")
    void autoCreateDisabled_skipsEntirely() {
        // Given: 自动创建开关关闭（run() 入口分支本次改动未涉及，回归保护）
        MilvusCollectionInitializer disabledInitializer =
                new MilvusCollectionInitializer(milvusClientV2, milvusProperties(false));

        // When: 执行初始化
        disabledInitializer.run(null);

        // Then: 不应调用任何 Milvus 操作
        verify(milvusClientV2, never()).hasCollection(any(HasCollectionReq.class));
        verify(milvusClientV2, never()).createCollection(any(CreateCollectionReq.class));
    }

    @Test
    @DisplayName("Collection 均不存在 — 创建两个集合（knowledge + memory），create/load 各 2 次")
    void run_createsBothCollections() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(false);

        initializer().run(null);

        ArgumentCaptor<CreateCollectionReq> captor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(milvusClientV2, times(2)).createCollection(captor.capture());
        verify(milvusClientV2, times(2)).loadCollection(any(LoadCollectionReq.class));
        // 第二个请求为 memory_chunks（run() 先 knowledge 后 memory）
        CreateCollectionReq memoryReq = captor.getAllValues().get(1);
        assertEquals(MilvusCollectionInitializer.COLLECTION_MEMORY, memoryReq.getCollectionName());
    }

    @Test
    @DisplayName("memory collection schema 含 6 个期望字段，embedding 为 FloatVector(1024) 维")
    void memoryCollectionSchemaContainsExpectedFields() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(false);

        initializer().run(null);

        ArgumentCaptor<CreateCollectionReq> captor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(milvusClientV2, times(2)).createCollection(captor.capture());
        // 第二个请求即 memory_chunks
        CreateCollectionReq memoryReq = captor.getAllValues().get(1);
        assertEquals(MilvusCollectionInitializer.COLLECTION_MEMORY, memoryReq.getCollectionName());

        List<FieldSchema> memoryFields = memoryReq.getCollectionSchema().getFieldSchemaList();
        assertEquals(6, memoryFields.size());
        // 期望 6 字段全集（与 FIELD_MEMORY_* 公开常量一一对应）
        List<String> names = memoryFields.stream().map(FieldSchema::getName).toList();
        assertTrue(names.containsAll(List.of(
                MilvusCollectionInitializer.FIELD_MEMORY_ID,
                MilvusCollectionInitializer.FIELD_MEMORY_USER_ID,
                MilvusCollectionInitializer.FIELD_MEMORY_TYPE,
                MilvusCollectionInitializer.FIELD_MEMORY_VALIDITY,
                MilvusCollectionInitializer.FIELD_MEMORY_EMBEDDING,
                MilvusCollectionInitializer.FIELD_MEMORY_UPDATED_AT)));

        // embedding 字段为 FLOAT_VECTOR(1024)（text-embedding-v4）
        FieldSchema embedding = memoryFields.stream()
                .filter(f -> MilvusCollectionInitializer.FIELD_MEMORY_EMBEDDING.equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("memory schema 缺少 embedding 字段"));
        assertEquals(DataType.FloatVector, embedding.getDataType());
        assertEquals(1024, embedding.getDimension());
    }
}
