package com.commerce.rag.bot.rewrite;

import java.util.List;

/**
 * 查询计划的元数据过滤条件 —— 首版仅 course_names（spec §2.2）
 *
 * @param courseNames 用户问题/上下文提到的课程名称语义标签（LLM 输出课程中文名，非 ID；
 *                    服务端 CourseNameMapper 确定性映射，见计划 Task 5）
 *
 * @author commerce-rag
 */
public record QueryPlanFilters(List<String> courseNames) {}
