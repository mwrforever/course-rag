package com.commerce.rag.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.record.AssistantMessageCapture.AssistantToolCall;
import com.commerce.rag.vo.ChatMessageVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AssistantEntitySplitter 单元测试 —— assistant 实体行拆行纯函数（2026-08-29 消息实体化）。
 *
 * <p>覆盖：三阶段实体拆行（QU→thinking+query_plan、caption→thinking、主 agent→thinking+
 * TOOL_CALL×N+正文）、VO.seq 按数组序倒推、条件部件（缺思考/缺正文/缺工具调用）、JSON
 * 损坏降级正文行、非实体行透传、toEntityJson/voCount 与拆行同源一致。
 *
 * <p>断言口径：面向拆行业务结果（VO 行类型/内容/seq），不绑定内部 Part 推导细节。
 */
@DisplayName("AssistantEntitySplitter 实体行拆行测试")
class AssistantEntitySplitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 构造实体行 VO（seq 传实体行序号，createdAt 固定——同实体拆出 VO 时间相同） */
    private static ChatMessageVO entityVo(long id, String content, Integer seq) {
        return new ChatMessageVO(
                id,
                "ASSISTANT",
                content,
                "assistant",
                null,
                "knowledge_question",
                10L,
                seq,
                LocalDateTime.of(2026, 8, 29, 10, 0));
    }

    // ==================== toEntityJson ====================

    @Test
    @DisplayName("toEntityJson — 三字段同体 JSON（schema/stage/reasoning 数组/toolCalls/text），reasoning 按换行拆行")
    void toEntityJson_fullCapture_threeFieldsInOne() throws Exception {
        AssistantMessageCapture capture = new AssistantMessageCapture(
                "generating",
                "第一行思考\n第二行思考",
                "最终正文",
                List.of(new AssistantToolCall("call-1", "searchKnowledge", "{\"query\":\"课程\"}")));

        String json = AssistantEntitySplitter.toEntityJson(capture);
        JsonNode root = MAPPER.readTree(json);
        assertEquals("assistant-v1", root.path("schema").asText(), "schema 版本固定 assistant-v1");
        assertEquals("generating", root.path("stage").asText());
        assertEquals(2, root.path("reasoning").size(), "思考按换行拆成数组");
        assertEquals("第一行思考", root.path("reasoning").get(0).asText());
        assertEquals("最终正文", root.path("text").asText());
        // toolCalls 与实时 TOOL_CALL 事件 schema 同构（toolCallId/toolName/input）
        JsonNode tool = root.path("toolCalls").get(0);
        assertEquals("call-1", tool.path("toolCallId").asText());
        assertEquals("searchKnowledge", tool.path("toolName").asText());
        assertEquals("课程", tool.path("input").path("query").asText(), "arguments 合法 JSON 应解析为对象嵌入");
    }

    @Test
    @DisplayName("toEntityJson — 无思考/无工具调用/无正文时对应字段为空数组或 null（QU/caption 形态）")
    void toEntityJson_quShape_reasoningAndToolCallsEmpty() throws Exception {
        AssistantMessageCapture qu =
                new AssistantMessageCapture("understanding", "QU 思考", "{\"intent\":\"chat\"}", List.of());
        JsonNode root = MAPPER.readTree(AssistantEntitySplitter.toEntityJson(qu));
        assertEquals("understanding", root.path("stage").asText());
        assertTrue(root.path("reasoning").isArray());
        assertEquals(1, root.path("reasoning").size());
        assertTrue(root.path("toolCalls").isArray());
        assertEquals(0, root.path("toolCalls").size(), "QU 工具调用恒空数组");
        assertEquals("{\"intent\":\"chat\"}", root.path("text").asText());

        AssistantMessageCapture noText = new AssistantMessageCapture("generating", null, null, List.of());
        JsonNode noTextRoot = MAPPER.readTree(AssistantEntitySplitter.toEntityJson(noText));
        assertEquals(0, noTextRoot.path("reasoning").size(), "无思考输出空数组");
        assertTrue(noTextRoot.path("text").isNull(), "无正文显式输出 null");
    }

    // ==================== splitEntity：QU 实体 ====================

    @Test
    @DisplayName("QU 实体拆行 — thinking(understanding) + query_plan 两 VO，content 与 payload 同构，seq 倒推")
    void splitEntity_quEntity_thinkingAndQueryPlan() throws Exception {
        // 实体 seq=2（拆 2 个 VO：seq 1..2）
        String content = AssistantEntitySplitter.toEntityJson(new AssistantMessageCapture(
                "understanding",
                "分析意图\n收窄查询",
                "{\"intent\":\"knowledge_question\",\"rewritten\":[\"高等数学\"],\"filters\":{\"courseNames\":[\"高等数学\"]}}",
                List.of()));
        ChatMessageVO entity = entityVo(100L, content, 2);

        List<ChatMessageVO> vos = AssistantEntitySplitter.splitEntity(entity);

        assertEquals(2, vos.size(), "QU 实体应拆 thinking + query_plan 两行");
        ChatMessageVO thinking = vos.get(0);
        assertEquals("thinking", thinking.messageType());
        assertEquals("understanding", thinking.thinkingStage(), "thinking 行 stage 取自实体 JSON stage");
        assertEquals("分析意图\n收窄查询", thinking.content(), "thinking 行 content = reasoning 按换行拼接");
        assertEquals(1, thinking.seq(), "VO.seq 按数组序倒推：thinking 在前占 seq-1");
        assertEquals(100L, thinking.id(), "拆出 VO 继承实体行 id（反馈目标）");
        assertEquals("knowledge_question", thinking.intentType());
        assertEquals(entity.createdAt(), thinking.createdAt(), "同实体拆出 VO createdAt 相同");

        ChatMessageVO queryPlan = vos.get(1);
        assertEquals("query_plan", queryPlan.messageType());
        assertEquals(
                "{\"intent\":\"knowledge_question\",\"rewritten\":[\"高等数学\"],\"filters\":{\"courseNames\":[\"高等数学\"]}}",
                queryPlan.content(),
                "query_plan 行 content = text 原样（前端 parse 契约不变）");
        assertEquals(2, queryPlan.seq(), "query_plan 在后占实体 seq");
        assertEquals(entity.createdAt(), queryPlan.createdAt(), "同实体拆出 VO createdAt 相同");
    }

    // ==================== splitEntity：caption 实体 ====================

    @Test
    @DisplayName("caption 实体拆行 — 单 thinking(attachments) VO，seq 即实体 seq")
    void splitEntity_captionEntity_singleThinking() throws Exception {
        String content = AssistantEntitySplitter.toEntityJson(
                new AssistantMessageCapture("attachments", "识别图中公式", "这是一张高等数学公式图", List.of()));
        ChatMessageVO entity = entityVo(101L, content, 5);

        List<ChatMessageVO> vos = AssistantEntitySplitter.splitEntity(entity);

        assertEquals(1, vos.size());
        ChatMessageVO thinking = vos.get(0);
        assertEquals("thinking", thinking.messageType());
        assertEquals("attachments", thinking.thinkingStage());
        assertEquals("识别图中公式", thinking.content());
        assertEquals(5, thinking.seq(), "单 VO 拆行 seq 即实体 seq");
    }

    // ==================== splitEntity：主 agent 实体 ====================

    @Test
    @DisplayName("主 agent 实体拆行 — thinking(generating) → TOOL_CALL×2 → 正文 三态齐备，seq 倒推连续")
    void splitEntity_mainAgentEntity_fullSplit() throws Exception {
        String content = AssistantEntitySplitter.toEntityJson(new AssistantMessageCapture(
                "generating",
                "生成阶段思考",
                "最终回答正文",
                List.of(
                        new AssistantToolCall("call-1", "searchKnowledge", "{\"query\":\"a\"}"),
                        new AssistantToolCall("call-2", "courseApi", "{\"id\":1}"))));
        // 实体 seq=7：4 个 VO 占 seq 4..7
        ChatMessageVO entity = entityVo(102L, content, 7);

        List<ChatMessageVO> vos = AssistantEntitySplitter.splitEntity(entity);

        assertEquals(4, vos.size(), "thinking + TOOL_CALL×2 + 正文 = 4 行");
        assertEquals("thinking", vos.get(0).messageType());
        assertEquals("generating", vos.get(0).thinkingStage());
        assertEquals(4, vos.get(0).seq());

        assertEquals("TOOL_CALL", vos.get(1).messageType());
        assertTrue(vos.get(1).content().contains("\"toolCallId\":\"call-1\""), "TOOL_CALL 行与实时事件 schema 同构");
        assertEquals(5, vos.get(1).seq());
        assertEquals("TOOL_CALL", vos.get(2).messageType());
        assertTrue(vos.get(2).content().contains("\"toolCallId\":\"call-2\""));
        assertEquals(6, vos.get(2).seq());

        assertEquals(null, vos.get(3).messageType(), "末位为正文行（messageType=null）");
        assertEquals("最终回答正文", vos.get(3).content());
        assertEquals(7, vos.get(3).seq(), "正文行占实体 seq 末位");
    }

    @Test
    @DisplayName("主 agent 无工具调用变体 — thinking + 正文两 VO（spec 6.1 含无工具调用变体）")
    void splitEntity_mainAgentWithoutTools_thinkingAndBody() throws Exception {
        String content = AssistantEntitySplitter.toEntityJson(
                new AssistantMessageCapture("generating", "思考内容", "回答内容", List.of()));
        ChatMessageVO entity = entityVo(103L, content, 3);

        List<ChatMessageVO> vos = AssistantEntitySplitter.splitEntity(entity);

        assertEquals(2, vos.size());
        assertEquals("thinking", vos.get(0).messageType());
        assertEquals(null, vos.get(1).messageType());
        assertEquals(2, vos.get(0).seq());
        assertEquals(3, vos.get(1).seq());
    }

    // ==================== splitEntity：条件部件与降级 ====================

    @Test
    @DisplayName("缺思考/缺正文条件部件 — 仅产出有内容的行（与实体化前「有内容才落行」一致）")
    void splitEntity_conditionalParts_skipEmpty() throws Exception {
        // 纯工具调用轮：无思考无正文 → 仅 TOOL_CALL 行
        String toolOnly = AssistantEntitySplitter.toEntityJson(new AssistantMessageCapture(
                "generating", null, null, List.of(new AssistantToolCall("call-1", "searchKnowledge", "{}"))));
        List<ChatMessageVO> toolOnlyVos = AssistantEntitySplitter.splitEntity(entityVo(104L, toolOnly, 1));
        assertEquals(1, toolOnlyVos.size());
        assertEquals("TOOL_CALL", toolOnlyVos.get(0).messageType());
        assertEquals(1, toolOnlyVos.get(0).seq());

        // 纯思考轮：无正文无工具调用 → 仅 thinking 行
        String thinkingOnly = AssistantEntitySplitter.toEntityJson(
                new AssistantMessageCapture("generating", "只有思考", null, List.of()));
        List<ChatMessageVO> thinkingOnlyVos = AssistantEntitySplitter.splitEntity(entityVo(105L, thinkingOnly, 2));
        assertEquals(1, thinkingOnlyVos.size());
        assertEquals("thinking", thinkingOnlyVos.get(0).messageType());
        assertEquals(2, thinkingOnlyVos.get(0).seq());

        // 空实体（全空）→ 拆 0 行
        String empty =
                AssistantEntitySplitter.toEntityJson(new AssistantMessageCapture("generating", null, null, List.of()));
        assertTrue(AssistantEntitySplitter.splitEntity(entityVo(106L, empty, 3)).isEmpty(), "空实体拆 0 行");
    }

    @Test
    @DisplayName("JSON 损坏降级（spec 3.6-5）— 按正文行输出 content 原文，不回滚查询")
    void splitEntity_corruptJson_degradesToBodyRow() {
        ChatMessageVO corrupt = entityVo(107L, "{损坏的 JSON 实体内容", 9);

        List<ChatMessageVO> vos = AssistantEntitySplitter.splitEntity(corrupt);

        assertEquals(1, vos.size());
        assertEquals(null, vos.get(0).messageType(), "损坏实体降级为正文行（messageType=null）");
        assertEquals("{损坏的 JSON 实体内容", vos.get(0).content(), "正文行 content = 实体 content 原文");
        assertEquals(9, vos.get(0).seq(), "降级单行 seq 即实体 seq");
    }

    @Test
    @DisplayName("非实体行（messageType != assistant）— 原样单元素透传，不拆行")
    void splitEntity_nonEntityRow_passthrough() {
        ChatMessageVO thinking =
                new ChatMessageVO(1L, "ASSISTANT", "思考内容", "thinking", "understanding", null, 10L, 2, null);

        List<ChatMessageVO> vos = AssistantEntitySplitter.splitEntity(thinking);

        assertEquals(1, vos.size());
        assertEquals(thinking, vos.get(0), "非实体行原样透传");
    }

    @Test
    @DisplayName("null 实体 — 返回空列表（防御）")
    void splitEntity_nullEntity_emptyList() {
        assertTrue(AssistantEntitySplitter.splitEntity(null).isEmpty());
    }

    // ==================== voCount 与拆行同源一致 ====================

    @Test
    @DisplayName("voCount 与 splitEntity 同源 — 落库侧 seq 赋位与消费侧倒推恒一致")
    void voCount_agreesWithSplitSize() throws Exception {
        // 主 agent 全量实体：4 VO
        String full = AssistantEntitySplitter.toEntityJson(new AssistantMessageCapture(
                "generating",
                "思考",
                "正文",
                List.of(new AssistantToolCall("c1", "t1", "{}"), new AssistantToolCall("c2", "t2", "{}"))));
        assertEquals(4, AssistantEntitySplitter.voCount(full));
        assertEquals(
                AssistantEntitySplitter.splitEntity(entityVo(1L, full, 10)).size(),
                AssistantEntitySplitter.voCount(full),
                "voCount 必须与拆行行数一致");

        // QU 实体：2 VO
        String qu = AssistantEntitySplitter.toEntityJson(
                new AssistantMessageCapture("understanding", "思考", "{\"intent\":\"chat\"}", List.of()));
        assertEquals(2, AssistantEntitySplitter.voCount(qu));

        // 空实体：0 VO（落库侧据此跳过不落行）
        String empty =
                AssistantEntitySplitter.toEntityJson(new AssistantMessageCapture("generating", null, null, List.of()));
        assertEquals(0, AssistantEntitySplitter.voCount(empty));

        // 损坏 JSON：降级 1 VO（正文行）
        assertEquals(1, AssistantEntitySplitter.voCount("非 JSON 内容"));
        // null/空白：0 VO
        assertEquals(0, AssistantEntitySplitter.voCount(null));
        assertEquals(0, AssistantEntitySplitter.voCount("  "));
    }
}
