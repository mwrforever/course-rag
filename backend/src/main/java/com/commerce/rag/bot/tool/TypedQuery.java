package com.commerce.rag.bot.tool;

import com.commerce.rag.bot.IntentType;
import java.util.List;

/**
 * A typed query carrying the intent and optional course scope.
 *
 * <p>Used as input to {@code SearchKnowledgeTool.searchKnowledge(List<TypedQuery>)}.
 * The {@code collectionType} drives the Milvus scalar filter expression, and
 * {@code courseIds} narrows the search scope when the user has enrolled in
 * specific courses.
 *
 * <p>When {@code courseIds} is {@code null} or empty, the filter expression is
 * {@code collection_type == "X"} only (no course_id filter).
 * When non-null and non-empty, the expression appends:
 * {@code and (course_id == "DEFAULT" or course_id in ["C1","C2"])}.
 *
 * @param collectionType intent that determines which Milvus partition to search
 * @param queryText      the actual search text (one of the rewritten queries)
 * @param courseIds      optional list of enrolled course IDs for scoped search;
 *                       {@code null} means global (no course_id filter)
 */
public record TypedQuery(IntentType collectionType, String queryText, List<String> courseIds) {}
