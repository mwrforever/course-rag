package com.commerce.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 企业级 RAG 知识库系统启动类
 * 基于 Spring Boot 3.5.8 + Spring AI Alibaba 1.1.2.0 + Milvus
 *
 * <p>{@code @ConfigurationPropertiesScan}（BUG-12）：properties/ 包全部属性类统一扫描注册，
 * 消费方（bot/retrieval/storage/service 等业务组件）直接注入属性类，杜绝 @Value 散落
 * （宪法 A.2.2）；既有各 config 类的 @EnableConfigurationProperties 局部注册与之幂等共存。
 *
 * @author commerce-rag
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.commerce.rag.**.mapper")
@ConfigurationPropertiesScan("com.commerce.rag.properties")
public class CommerceRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommerceRagApplication.class, args);
    }
}
