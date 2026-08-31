package com.commerce.rag.config;

import com.commerce.rag.properties.MilvusProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量数据库客户端配置（v2 API）
 *
 * <p>创建 {@link MilvusClientV2} Bean，供 {@code SearchKnowledgeTool} / {@code EtlPipeline}
 * / {@code MilvusCollectionInitializer} 注入使用。
 * 连接参数从 application.yml 的 {@code milvus.host} 和 {@code milvus.port} 读取。
 *
 * <p>SDK 2.6.11 v2 API 使用 {@link ConnectConfig} 替代 v1 的 {@code ConnectParam}，
 * 连接 URI 格式为 {@code http://{host}:{port}}。
 *
 * <p>PERF-04（宪法 D.5.10）：显式配置 rpcDeadlineMs——SDK 默认 0 表示无截止时间，
 * gRPC 调用挂起时调用线程（检索/ETL）会被永久占用；经 {@link MilvusProperties}
 * 配置化注入，挂起收窄为有界超时。
 *
 * @author commerce-rag
 */
@Configuration
@EnableConfigurationProperties(MilvusProperties.class)
public class MilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConfig.class);

    /**
     * 创建 Milvus v2 客户端 Bean
     *
     * @param host Milvus 服务地址（默认 localhost）
     * @param port Milvus 服务端口（默认 19530）
     * @param milvusProperties Milvus 连接调优配置（rpcDeadlineMs 等，非空，Spring 注入）
     * @return MilvusClientV2 实例
     */
    @Bean
    public MilvusClientV2 milvusClientV2(
            @Value("${milvus.host:localhost}") String host,
            @Value("${milvus.port:19530}") int port,
            MilvusProperties milvusProperties) {
        String uri = "http://" + host + ":" + port;
        // PERF-04：显式 rpcDeadlineMs 防挂死（默认 0=无截止，gRPC 挂起将永久占用调用线程）
        ConnectConfig config = ConnectConfig.builder()
                .uri(uri)
                .rpcDeadlineMs(milvusProperties.rpcDeadlineMs())
                .build();
        log.info("创建 Milvus v2 客户端: uri={}, rpcDeadlineMs={}", uri, milvusProperties.rpcDeadlineMs());
        return new MilvusClientV2(config);
    }
}
