package com.commerce.rag.service;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘统计服务接口 —— 聚合查询型（无单一主表实体，不继承 IService）
 *
 * @author commerce-rag
 */
public interface IDashboardService {

    /**
     * 全局统计（课程/知识库/文档/用户等计数，带缓存）
     */
    Map<String, Object> dashboardStats();

    /**
     * 反馈统计（按周期聚合，带缓存）
     *
     * @param period 周期维度（如 day/week）
     */
    Map<String, Object> feedbackStats(String period);

    /**
     * 反馈趋势（最近 N 天，带缓存）
     *
     * @param days 天数
     */
    List<Map<String, Object>> feedbackTrend(int days);
}
