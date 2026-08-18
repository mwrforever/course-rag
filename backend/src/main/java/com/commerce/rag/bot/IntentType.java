package com.commerce.rag.bot;

/**
 * RAG Agent 意图枚举（S1 定稿）
 *
 * <p>值域 knowledge_question / chat / unknown（spec §1）：
 * <ul>
 *   <li>knowledge_question：课程信息或技术知识咨询（含课程咨询）——唯一触发检索的意图，由
 *       queryUnderstandingNode 判定，RetrieveNode 分支执行系统检索；课程结构化信息由
 *       ReactAgent 按需调用 CourseApiTool 获取</li>
 *   <li>chat：纯闲聊/寒暄，与课程/技术无关——不检索，直接对话</li>
 *   <li>unknown：意图识别失败（LLM 失败/JSON 解析失败降级产物）——不拒答，走正常对话</li>
 * </ul>
 *
 * <p>意图与检索解耦（S1）：本枚举不再参与 Milvus 过滤（knowledge_chunks 已移除
 * collection_type 字段），仅作为日志/结果标注与图条件边路由 key（code() 的小写规范名与
 * LeadAgentGraph 条件边映射键一致）。
 *
 * @see com.commerce.rag.bot.graph.LeadAgentGraph
 */
public enum IntentType {

    /** 知识/课程问题 —— 触发检索链路（spec §1：课程咨询并入本意图） */
    KNOWLEDGE_QUESTION,

    /** 闲聊/寒暄 —— 不检索，直接对话 */
    CHAT,

    /** 意图识别失败 —— 不拒答，走正常对话 */
    UNKNOWN;

    /**
     * 字符串 → 意图（宽松映射）
     *
     * <p>QueryUnderstandingService 解析 LLM 输出 JSON 时调用；未知字符串一律返回 UNKNOWN
     * （意图识别失败降级路径，不因字段缺失/拼写偏差打断对话）。
     *
     * @param value 意图字符串（如 "knowledge_question"），可为 null/空白
     * @return 对应意图枚举，未知一律 UNKNOWN
     */
    public static IntentType fromString(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return switch (value.trim().toLowerCase()) {
            case "knowledge_question" -> KNOWLEDGE_QUESTION;
            case "chat" -> CHAT;
            default -> UNKNOWN;
        };
    }

    /**
     * 小写规范名（spec §2.4）——与 LLM 输出 JSON 的 intent 字段值一致（如 "knowledge_question"）
     *
     * <p>条件边路由 key（spec §1 意图名小写）：LeadAgentGraph 的 INTENT_ROUTES 映射键必须使用
     * code() 而非 name()（name() 返回大写常量标识符，与路由键语义不符）。
     *
     * <p>本方法分支与 {@link #fromString(String)} 的 switch case 一一对称：code() 返回当前枚举
     * 的小写规范名，fromString 按同一规范名反解回对应枚举。
     *
     * @return 当前意图的小写规范名
     */
    public String code() {
        return switch (this) {
            case KNOWLEDGE_QUESTION -> "knowledge_question";
            case CHAT -> "chat";
            case UNKNOWN -> "unknown";
        };
    }
}
