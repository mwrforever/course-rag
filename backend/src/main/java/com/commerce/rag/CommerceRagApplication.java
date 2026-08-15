package com.commerce.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 企业级 RAG 知识库系统启动类
 * 基于 Spring Boot 3.5.8 + Spring AI Alibaba 1.1.2.0 + Milvus
 *
 * @author commerce-rag
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.commerce.rag.**.mapper")
public class CommerceRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommerceRagApplication.class, args);
    }
}
