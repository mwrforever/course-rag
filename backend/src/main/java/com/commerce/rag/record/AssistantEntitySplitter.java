package com.commerce.rag.record;

import com.commerce.rag.vo.ChatMessageVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * assistant 实体行拆行器（消息实体化，2026-08-29）——纯函数，集中一处。
 *
 * <p>正常完成路径每次 LLM 调用落一条 {@code message_type='assistant'} 实体行（content 为
 * spec §3.1 JSON：{schema, stage, reasoning[], toolCalls[], text}）。消费面（C 端历史
 * findByRunId / 学生历史 findStudentMessagesBySession / 降级回放 replayFromPg）经本类
 * 把实体行拆回「事件序行」——与实体化前落库的行形态完全一致，前端与 SSE 事件协议零改动：
 * <ul>
 *   <li>stage=understanding（QU 实体）→ thinking 行（stage=understanding）+ query_plan 行
 *       （content=text 原样，前端 parse 契约 intent/rewritten/filters.courseNames 不变）</li>
 *   <li>stage=attachments（caption 实体）→ thinking 行（stage=attachments）</li>
 *   <li>stage=generating（主 agent 实体）→ thinking 行（stage=generating）→ TOOL_CALL 行×N
 *       → 正文行</li>
 * </ul>
 * VO.seq 按数组序倒推：n 个 VO，seq = 实体seq-(n-1)…实体seq（仅作 (createdAt, seq) 排序键，
 * 同实体拆出 VO createdAt 相同、seq 递增，与跨实体/增量行混合排序正确）。
 *
 * <p>降级（spec §3.6-5）：实体行 JSON 解析失败（存量/损坏）按「正文行」输出（content 原文），
 * 记 warn，不回滚查询。
 *
 * <p>线程安全：全静态无状态，ObjectMapper 为线程安全单例。
 */
public final class AssistantEntitySplitter {

    private static final Logger log = LoggerFactory.getLogger(AssistantEntitySplitter.class);

    /** 实体行 JSON schema 版本（spec §3.1，随 schema 演进递增） */
    private static final String SCHEMA_VERSION = "assistant-v1";

    /** 静态 JSON 处理器（仅实体行 3.1 schema 序列化/解析，无项目级自定义模块需求，线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具类禁止实例化 */
    private AssistantEntitySplitter() {}

    /**
     * 捕获 → 实体行 content（spec §3.1 JSON）。
     *
     * <p>reasoning 按换行拆数组（拆行时按换行原样拼回，round-trip 无损）；toolCalls 与实时
     * TOOL_CALL 事件 schema 同构（{toolCallId, toolName, input}，arguments 为合法 JSON 时
     * 解析为对象嵌入、否则按字符串保留——与 ChatRequestWorker.buildToolCallContent 同语义）；
     * text 为 null 时显式输出 null（无正文调用的调用全貌仍完整可读）。
     *
     * @param capture 单次 LLM 调用捕获（stage 恒非空）
     * @return 3.1 JSON 字符串；序列化失败（理论不可达）返回 null
     */
    public static String toEntityJson(AssistantMessageCapture capture) {
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("schema", SCHEMA_VERSION);
            content.put("stage", capture.stage());
            content.put("reasoning", splitLines(capture.reasoning()));
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (AssistantMessageCapture.AssistantToolCall toolCall : capture.toolCalls()) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("toolCallId", toolCall.id() == null ? "" : toolCall.id());
                tool.put("toolName", toolCall.name() == null ? "" : toolCall.name());
                tool.put("input", parseArgument(toolCall.arguments()));
                toolCalls.add(tool);
            }
            content.put("toolCalls", toolCalls);
            content.put("text", capture.text());
            return MAPPER.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            // 理论不可达（纯 LinkedHashMap 序列化）；降级 null，调用方按无实体处理
            log.warn("assistant 实体行序列化失败: stage={}, err={}", capture.stage(), e.getMessage());
            return null;
        }
    }

    /**
     * 实体行拆行数（persistMessages 赋 seq 用）：与 {@link #splitEntity} 同源判定——
     * 拆出的 VO 数完全由 content 决定，保证「落库实体 seq = 拆行末位 VO seq」恒成立。
     *
     * @param contentJson 实体行 content（3.1 JSON；null 按 0 处理）
     * @return 拆行 VO 数（0 = 空实体不落行；解析失败按降级正文行计 1）
     */
    public static int voCount(String contentJson) {
        return parseParts(contentJson).size();
    }

    /**
     * 实体行 → 事件序 VO 列表（纯函数，消费面统一入口）。
     *
     * <p>非 assistant 实体行（messageType != "assistant"）原样单元素返回（调用方可对整批
     * 行统一 {@code flatMap(splitOrPass)}，无需调用侧区分）；实体行按数组序拆行并倒推 seq。
     *
     * @param entity 实体行 VO（findByRunId / 学生历史投影行的 VO 形态）
     * @return 事件序 VO 列表（拆行为 0 时返回空列表；解析失败降级单正文行）
     */
    public static List<ChatMessageVO> splitEntity(ChatMessageVO entity) {
        if (entity == null || !"assistant".equals(entity.messageType())) {
            return entity == null ? List.of() : List.of(entity);
        }
        List<Part> parts = parseParts(entity.content());
        if (parts.isEmpty()) {
            return List.of();
        }
        int n = parts.size();
        List<ChatMessageVO> vos = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Part part = parts.get(i);
            // VO.seq 按数组序倒推：实体 seq 为 null（防御）时保持 null（SpotBugs 实证：
            // 用 int 承接三元 null 分支会触发拆箱 NPE，必须 Integer）
            Integer seq = entity.seq() == null ? null : entity.seq() - (n - 1) + i;
            vos.add(new ChatMessageVO(
                    entity.id(),
                    "ASSISTANT",
                    part.content(),
                    part.messageType(),
                    part.thinkingStage(),
                    entity.intentType(),
                    entity.runId(),
                    seq,
                    entity.createdAt()));
        }
        return vos;
    }

    // ========================================================================
    // 拆行内部：content → 行部件序列（事件序）
    // ========================================================================

    /** 拆行中间部件（事件序一行）：与实体化前行形态一一对应 */
    private record Part(String messageType, String thinkingStage, String content) {}

    /**
     * 解析实体 content 为拆行部件序列（唯一判定源：splitEntity 与 voCount 共用）。
     *
     * <p>顺序固定：thinking 行 → query_plan 行（understanding）/ TOOL_CALL 行×N → 正文行；
     * 缺省部件（无思考/无工具调用/无正文）不产出，与实体化前「有内容才落行」语义一致。
     * JSON 解析失败降级为单正文行（content 原文）+ warn（spec §3.6-5）。
     */
    private static List<Part> parseParts(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(contentJson);
        } catch (Exception e) {
            // 降级（spec §3.6-5）：损坏实体行按正文行输出（content 原文），记 warn 不回滚查询
            log.warn("assistant 实体行 JSON 解析失败，降级为正文行输出: content预览={}, err={}", truncate(contentJson), e.getMessage());
            return List.of(new Part(null, null, contentJson));
        }
        String stage = root.path("stage").asText("generating");
        List<Part> parts = new ArrayList<>();
        // 1. thinking 行：reasoning 数组按换行拼接（与捕获侧按换行拆数组 round-trip）
        String reasoning = joinReasoning(root.path("reasoning"));
        if (reasoning != null && !reasoning.isBlank()) {
            parts.add(new Part("thinking", stage, reasoning));
        }
        if ("understanding".equals(stage)) {
            // QU 实体：thinking 行之后 query_plan 行（content=text 原样，前端 parse 契约不变）
            String text = textOf(root);
            if (text != null && !text.isBlank()) {
                parts.add(new Part("query_plan", null, text));
            }
        } else if ("attachments".equals(stage)) {
            // caption 实体：仅 thinking 行——描述文本仅供查看、不渲染为正文（spec §3.1 caption 行）
        } else {
            // 主 agent（generating/未知 stage）：TOOL_CALL 行×N → 正文行
            JsonNode toolCalls = root.path("toolCalls");
            if (toolCalls.isArray()) {
                for (JsonNode toolCall : toolCalls) {
                    parts.add(new Part("TOOL_CALL", null, toolCall.toString()));
                }
            }
            String text = textOf(root);
            if (text != null && !text.isBlank()) {
                parts.add(new Part(null, null, text));
            }
        }
        return parts;
    }

    /** 取实体 JSON 的 text 字段（缺失/非字符串返回 null） */
    private static String textOf(JsonNode root) {
        JsonNode text = root.path("text");
        return text.isTextual() ? text.asText() : null;
    }

    /** 思考全文按换行拆数组（null → 空数组；与拆行时按换行拼回互逆） */
    private static List<String> splitLines(String reasoning) {
        if (reasoning == null || reasoning.isEmpty()) {
            return List.of();
        }
        return List.of(reasoning.split("\n", -1));
    }

    /** reasoning 数组按换行拼接为全文（拆行 VO 的 thinking 行 content；非数组返回 null） */
    private static String joinReasoning(JsonNode reasoningNode) {
        if (!reasoningNode.isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (JsonNode line : reasoningNode) {
            if (!first) {
                sb.append('\n');
            }
            first = false;
            sb.append(line.isTextual() ? line.asText() : "");
        }
        return sb.toString();
    }

    /** 工具参数解析：合法 JSON 嵌入为对象节点，否则按字符串保留（与 buildToolCallContent 同语义） */
    private static Object parseArgument(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readTree(arguments);
        } catch (JsonProcessingException e) {
            return arguments;
        }
    }

    /** 日志摘要截断（warn 不落完整实体 content） */
    private static String truncate(String content) {
        return content.length() <= 60 ? content : content.substring(0, 60) + "...";
    }
}
