package com.commerce.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量数据库连接配置
 *
 * 负责创建 MilvusServiceClient 单例，供向量存储和检索模块使用。
 * 连接参数通过 application.yml 注入，支持环境变量覆盖。
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private int port;

    /**
     * 创建 Milvus 服务客户端单例
     *
     * @return 已连接的 MilvusServiceClient
     */
    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient() {
        log.info("初始化 Milvus 连接: {}:{}", host, port);
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();
        return new MilvusServiceClient(connectParam);
    }
}
