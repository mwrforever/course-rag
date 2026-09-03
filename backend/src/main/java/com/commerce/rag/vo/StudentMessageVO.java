package com.commerce.rag.vo;

import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.RetrievalSource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学生历史消息视图对象 —— C 端会话历史回显（R1 补口 A，接口契约独立演化，不复用 ChatMessageVO）
 *
 * <p>与实时 SSE 事件同构设计：sources/attachments 由服务端把 chat_message 的
 * sources_json/attachments_json（JSONB 字符串）解析为对象数组后下发，
 * 前端「引用来源」卡片与附件渲染无需区分实时/历史链路；非法 JSON 兜底空列表，
 * 不阻断历史回显。
 *
 * <p>Long 字段（id/runId）经 R0 全局 Jackson Long→String 序列化输出为字符串，
 * 防止雪花 ID 超出 JS Number.MAX_SAFE_INTEGER 精度丢失。
 *
 * @param id          消息雪花 ID（反馈目标）
 * @param role        角色：USER / ASSISTANT
 * @param content     正文（TOOL_CALL/TOOL_RESULT 行为 JSON 串，与实时事件格式一致；
 *                    query_plan 行为原始 JSON 字符串，由前端 parse，后端不重组）
 * @param messageType 消息类型：null（正文）/ thinking / TOOL_CALL / TOOL_RESULT / query_plan
 * @param thinkingStage thinking 行的思考阶段键（understanding/attachments/generating）；
 *                    2026-08-28 时间线改版新增，历史存量 thinking 行无该列值时为 null
 *                    （null 语义 = 前端降级按 generating 渲染，接口不报错）
 * @param intentType  意图类型（knowledge_question / chat / unknown；R2 落库修复前的存量行为 null，前端按可空处理）
 * @param runId       所属 run
 * @param seq         run 内序号
 * @param createdAt   创建时间（ISO-8601 字符串输出）
 * @param sources     引用来源列表（服务端解析，非法 JSON 兜底 []）
 * @param attachments 用户消息附件列表（assistant 行恒 []）
 * @param runStatus   所属 run 终态（M4：COMPLETED/CANCELLED/ERROR，仅终态 run 的非 USER 行下发；
 *                    USER 行与进行中 run 的行恒 null——接口演进只加字段，A.3.6）
 * @param errorMessage run 错误信息（M4：仅 runStatus=ERROR 行下发，前端「生成失败」徽标
 *                    tooltip 数据源，取自 chat_run.error_message；其余行恒 null）
 * @author commerce-rag
 */
public record StudentMessageVO(
        Long id,
        String role,
        String content,
        String messageType,
        String thinkingStage,
        String intentType,
        Long runId,
        Integer seq,
        LocalDateTime createdAt,
        List<RetrievalSource> sources,
        List<AttachmentRecord> attachments,
        String runStatus,
        String errorMessage) {}
