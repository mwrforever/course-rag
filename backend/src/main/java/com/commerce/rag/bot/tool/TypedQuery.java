package com.commerce.rag.bot.tool;

import com.commerce.rag.bot.IntentType;
import java.util.List;

/**
 * 一次类型化检索请求 —— 携带意图元数据 + 查询文本 + 可选课程范围
 *
 * <p>作为 {@code SearchKnowledgeTool.searchKnowledge(List<TypedQuery>)} 的输入。
 * S1 意图-检索解耦后，{@code collectionType} 仅作为日志/结果标注的元数据，
 * 不再参与 Milvus 过滤表达式；{@code courseIds} 在用户已选课时收窄检索范围。
 *
 * <p>过滤表达式由 {@code SearchKnowledgeTool.buildFilterExpression} 生成：
 * <ul>
 *   <li>{@code courseIds} 为 null 或空：返回 null（不设过滤，全局检索）</li>
 *   <li>非 null 非空：{@code (course_id == "DEFAULT" or course_id in ["C1","C2"])}</li>
 * </ul>
 *
 * @param collectionType 意图元数据（仅用于日志/结果标注，不驱动 Milvus 过滤）
 * @param queryText      实际检索文本（查询改写后的某条覆盖查询）
 * @param courseIds      用户已选课程 ID 列表，用于收窄检索范围；
 *                       {@code null} 表示全局检索（不设 course_id 过滤）
 */
public record TypedQuery(IntentType collectionType, String queryText, List<String> courseIds) {}
