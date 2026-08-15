package com.commerce.rag.bot;

/**
 * RAG Agent intent types.
 *
 * <p>Two supported intents. Ambiguous queries are handled internally by the
 * {@code queryRewriteNode} generating multiple coverage queries — there is no
 * {@code AWAITING_CLARIFICATION} state.
 *
 * <p>Used as Milvus scalar filter: {@code collection_type == "TECHNICAL_QA"}
 * or {@code collection_type == "COURSE_INFO"} on the single {@code knowledge_chunks}
 * collection.
 *
 * @see com.commerce.rag.bot.tool.SearchKnowledgeTool
 */
public enum IntentType {

    /** Technical Q&A — troubleshoot, explain concepts, error diagnosis. */
    TECHNICAL_QA,

    /** Course information — schedules, pricing, enrollment, syllabus. */
    COURSE_INFO
}
