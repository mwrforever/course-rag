package com.commerce.rag.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地缓存配置 —— 提供课程查询与 Dashboard 统计两个 Caffeine 缓存实例
 *
 * <p>课程查询缓存（perf P2-2）：ICourseQueryService 查询结果，TTL 5 分钟，容量 512；
 * 失效钩子挂在课程/排期写方法（先写 DB 后失效，一致性铁律）。
 *
 * <p>Dashboard 统计缓存（perf P2-3）：三端点统计结果，TTL 60 秒兜底，
 * 文档上传/ETL 终态/反馈提交时由写方主动 invalidateAll。
 *
 * @author commerce-rag
 */
@Configuration
public class CacheConfig {

    /** 课程查询缓存：键格式 search:{keyword}:{page} / course:{id} / contents:{id} / schedule:{id} */
    @Bean
    public Cache<String, Object> courseQueryCache() {
        return Caffeine.newBuilder()
                .maximumSize(512)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    /** Dashboard 统计缓存：键格式 dashboardStats / feedbackStats:{period} / feedbackTrend:{days}（单键全局视图，BUG-10 注释修正） */
    @Bean
    public Cache<String, Object> dashboardStatsCache() {
        return Caffeine.newBuilder()
                .maximumSize(32)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
    }
}
