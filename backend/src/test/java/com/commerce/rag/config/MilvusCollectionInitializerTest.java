package com.commerce.rag.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

/**
 * MilvusCollectionInitializer 单元测试 —— Mock MilvusClientV2
 *
 * <p>测试四条路径：
 * <ol>
 *   <li>Collection 不存在 → 创建 + 加载</li>
 *   <li>Collection 已存在 → 跳过</li>
 *   <li>Milvus 不可达 → warn + 不抛异常</li>
 *   <li>开关关闭 → 不执行</li>
 * </ol>
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class MilvusCollectionInitializerTest {

    @Mock
    private MilvusClientV2 milvusClientV2;

    @Mock
    private ApplicationArguments applicationArguments;

    /** 被测对象：自动创建开关开启，使用与 application.yml 一致的默认参数 */
    private MilvusCollectionInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new MilvusCollectionInitializer(milvusClientV2, "knowledge_chunks", 1024, 16, 200, true);
    }

    @Test
    @DisplayName("Collection 不存在 → 创建 Collection（含索引） + 加载")
    void run_collectionNotExists_createsAndLoads() {
        // Given: hasCollection 返回 false（不存在）
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(Boolean.FALSE);

        // When: 执行初始化
        initializer.run(applicationArguments);

        // Then: 验证创建 Collection（含 indexParams 一步创建）
        verify(milvusClientV2).createCollection(any(CreateCollectionReq.class));
        // 验证加载 Collection
        verify(milvusClientV2).loadCollection(any(LoadCollectionReq.class));
    }

    @Test
    @DisplayName("Collection 已存在 → 跳过创建，不调用 createCollection")
    void run_collectionExists_skipsCreation() {
        // Given: hasCollection 返回 true（已存在）
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(Boolean.TRUE);

        // When: 执行初始化
        initializer.run(applicationArguments);

        // Then: 不应创建 Collection
        verify(milvusClientV2, never()).createCollection(any(CreateCollectionReq.class));
        // 不应加载 Collection
        verify(milvusClientV2, never()).loadCollection(any(LoadCollectionReq.class));
    }

    @Test
    @DisplayName("Milvus 不可达 → hasCollection 抛异常，降级跳过，不阻断启动")
    void run_milvusUnreachable_degradesGracefully() {
        // Given: hasCollection 抛出异常（模拟 Milvus 不可达）
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class)))
                .thenThrow(new RuntimeException("连接超时: Milvus 不可达"));

        // When: 执行初始化 —— 不应抛出异常
        initializer.run(applicationArguments);

        // Then: 不应创建 Collection（异常已降级）
        verify(milvusClientV2, never()).createCollection(any(CreateCollectionReq.class));
        verify(milvusClientV2, never()).loadCollection(any(LoadCollectionReq.class));
    }

    @Test
    @DisplayName("auto-create-collection=false → 跳过初始化，不调用 hasCollection")
    void run_autoCreateDisabled_skipsEntirely() {
        // Given: 自动创建开关关闭
        MilvusCollectionInitializer disabledInitializer =
                new MilvusCollectionInitializer(milvusClientV2, "knowledge_chunks", 1024, 16, 200, false);

        // When: 执行初始化
        disabledInitializer.run(applicationArguments);

        // Then: 不应调用任何 Milvus 操作
        verify(milvusClientV2, never()).hasCollection(any(HasCollectionReq.class));
        verify(milvusClientV2, never()).createCollection(any(CreateCollectionReq.class));
    }
}
