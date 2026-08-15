package com.commerce.rag.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * @author commerce-rag
 */
@Configuration
public class MilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConfig.class);

    /**
     * 创建 Milvus v2 客户端 Bean
     *
     * @param host Milvus 服务地址（默认 localhost）
     * @param port Milvus 服务端口（默认 19530）
     * @return MilvusClientV2 实例
     */
    @Bean
    public MilvusClientV2 milvusClientV2(
            @Value("${milvus.host:localhost}") String host, @Value("${milvus.port:19530}") int port) {
        String uri = "http://" + host + ":" + port;
        ConnectConfig config = ConnectConfig.builder().uri(uri).build();
        log.info("创建 Milvus v2 客户端: uri={}", uri);
        return new MilvusClientV2(config);
    }
}
