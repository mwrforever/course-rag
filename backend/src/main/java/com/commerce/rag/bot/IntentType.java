package com.commerce.rag.bot;

/**
 * RAG Agent intent types.
 *
 * <p>Two supported intents. Ambiguous queries are handled internally by the
 * {@code queryRewriteNode} generating multiple coverage queries — there is no
 * {@code AWAITING_CLARIFICATION} state.
 *
 * <p>意图由上游 {@code intentClassifier} 判定后随 {@link com.commerce.rag.bot.tool.TypedQuery}
 * 下发检索侧，仅作为日志/结果标注的元数据，不再参与 Milvus 过滤（S1 意图-检索解耦，
 * {@code knowledge_chunks} 已移除 {@code collection_type} 字段，检索仅按 course_id 收窄）。
 *
 * @see com.commerce.rag.bot.tool.SearchKnowledgeTool
 */
public enum IntentType {

    /** Technical Q&A — troubleshoot, explain concepts, error diagnosis. */
    TECHNICAL_QA,

    /** Course information — schedules, pricing, enrollment, syllabus. */
    COURSE_INFO
}
