package com.commerce.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 企业级 RAG 知识库系统主启动类
 *
 * 基于 Spring AI + Spring AI Alibaba + Milvus 构建，提供：
 * - 文档 ETL 管道（解析 → 分块 → 向量存储）
 * - 混合检索链路（Dense + Sparse + RRF + Rerank）
 * - AI 机器人（课程询问 / 启发式解答 / QA 问答）
 * - 用户反馈采集与分析
 */
@SpringBootApplication
@EnableAsync
public class CommerceRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommerceRagApplication.class, args);
    }
}
