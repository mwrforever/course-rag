package com.commerce.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索参数配置
 *
 * 绑定 application.yml 中 retrieval.* 前缀的配置项，
 * 包含分数传播、热度加权、RRF 融合等核心检索旋钮。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "retrieval")
public class RetrievalConfigProperties {

    /** 子节点分数传播权重，1.0=纯子分，<1 则父分参与 */
    private double scorePropagationAlpha = 0.85;

    /** 热度加权系数，0.0=关闭，0.15=15%热度 */
    private double hotnessAlpha = 0.15;

    /** 热度衰减半衰期（天） */
    private double halfLifeDays = 7.0;

    /** 默认返回结果数 */
    private int defaultTopK = 5;

    /** 预取倍数（prefetch = topK * multiplier） */
    private int prefetchMultiplier = 4;

    /** RRF 融合常数 k，默认 60 */
    private int rrfK = 60;

    /** rerank 分数阈值，低于此值的结果被过滤 */
    private double rerankThreshold = 0.30;
}
