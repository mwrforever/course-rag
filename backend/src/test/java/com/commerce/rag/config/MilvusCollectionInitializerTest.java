package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import java.util.List;
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

    private MilvusCollectionInitializer initializer() {
        return new MilvusCollectionInitializer(milvusClientV2, "knowledge_chunks", 1024, 16, 200, true);
    }

    @Test
    @DisplayName("schema 不匹配（缺 sha256）— drop 后重建")
    void schemaMismatch_dropsAndRecreates() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        DescribeCollectionResp desc = DescribeCollectionResp.builder()
                .fieldNames(List.of("chunk_id", "doc_id", "content", "dense_vector")) // 旧 schema
                .build();
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(desc);

        initializer().run(null);

        verify(milvusClientV2).dropCollection(any(DropCollectionReq.class));
    }

    @Test
    @DisplayName("schema 匹配 — 不 drop 不重建")
    void schemaMatches_skipsRebuild() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        DescribeCollectionResp desc = DescribeCollectionResp.builder()
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
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(desc);

        initializer().run(null);

        verify(milvusClientV2, never()).dropCollection(any(DropCollectionReq.class));
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
        verify(milvusClientV2).createCollection(captor.capture());
        // SDK 2.6.11 无 CollectionSchema.getFieldNames()，取 getFieldSchemaList() 映射字段名
        List<String> fields = captor.getValue().getCollectionSchema().getFieldSchemaList().stream()
                .map(FieldSchema::getName)
                .toList();
        assertTrue(fields.contains("content_type") && fields.contains("image_url") && fields.contains("sha256"));
        assertTrue(!fields.contains("collection_type"), "新 schema 不应含 collection_type");
        assertEquals(14, fields.size());
    }

    @Test
    @DisplayName("auto-create-collection=false — 跳过初始化，不调用 hasCollection")
    void autoCreateDisabled_skipsEntirely() {
        // Given: 自动创建开关关闭（run() 入口分支本次改动未涉及，回归保护）
        MilvusCollectionInitializer disabledInitializer =
                new MilvusCollectionInitializer(milvusClientV2, "knowledge_chunks", 1024, 16, 200, false);

        // When: 执行初始化
        disabledInitializer.run(null);

        // Then: 不应调用任何 Milvus 操作
        verify(milvusClientV2, never()).hasCollection(any(HasCollectionReq.class));
        verify(milvusClientV2, never()).createCollection(any(CreateCollectionReq.class));
    }
}
