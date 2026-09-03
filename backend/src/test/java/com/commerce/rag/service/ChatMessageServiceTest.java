package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.convert.ChatSessionConverterImpl;
import com.commerce.rag.convert.StudentConverterImpl;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.mapper.ChatMessageMapper;
import com.commerce.rag.record.AssistantEntitySplitter;
import com.commerce.rag.record.AssistantMessageCapture;
import com.commerce.rag.service.impl.ChatMessageServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.ChatRunStatusVO;
import com.commerce.rag.vo.StudentMessageVO;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IChatMessageService 单元测试 —— 消息批量持久化与查询
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IChatMessageService 消息服务测试")
class ChatMessageServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private ChatMessageMapper messageMapper;

    @Mock
    private IChatRunService chatRunService;

    /** 被测实现（spy：saveBatch 为 MP 框架方法，mock 环境无 SqlSessionFactory，stub 后验证调用） */
    private ChatMessageServiceImpl messageService;

    @BeforeEach
    void setUp() {
        // 构造参数顺序与 @RequiredArgsConstructor 字段声明一致：
        // messageMapper → chatSessionConverter → studentConverter → chatRunService
        messageService = spy(new ChatMessageServiceImpl(
                messageMapper, new ChatSessionConverterImpl(), new StudentConverterImpl(), chatRunService));
    }

    @Test
    @DisplayName("batchInsert → 空列表直接返回，不触发 saveBatch")
    void batchInsert_empty_returnsEarly() {
        messageService.batchInsert(List.of());

        verify(messageService, never()).saveBatch(anyList());
    }

    @Test
    @DisplayName("batchInsert → sourcesJson 兜底为 [] 后调用 saveBatch（ID 由 MP ASSIGN_ID 自动填充）")
    void batchInsert_fillsSourcesJsonAndCallsSaveBatch() {
        ChatMessage msg1 = new ChatMessage();
        msg1.setSourcesJson("[{\"doc\":1}]");
        ChatMessage msg2 = new ChatMessage();
        // 无 sourcesJson —— 触发兜底逻辑
        doReturn(true).when(messageService).saveBatch(anyList());

        messageService.batchInsert(List.of(msg1, msg2));

        assertEquals("[{\"doc\":1}]", msg1.getSourcesJson());
        assertEquals("[]", msg2.getSourcesJson());
        verify(messageService).saveBatch(List.of(msg1, msg2));
    }

    @Test
    @DisplayName("findByRunId → 按 run 查询消息并转为消息 VO（含 thinking_stage，剔除内部字段）")
    void findByRunId_returnsMessages() {
        ChatMessage msg = new ChatMessage();
        msg.setId(1L);
        msg.setRole("ASSISTANT");
        msg.setContent("回答1");
        msg.setMessageType("thinking");
        msg.setThinkingStage("understanding");
        msg.setRunId(10L);
        msg.setSeq(1);
        when(messageMapper.selectList(any())).thenReturn(List.of(msg));

        List<ChatMessageVO> result = messageService.findByRunId(10L);

        assertEquals(1, result.size());
        ChatMessageVO vo = result.get(0);
        assertEquals("ASSISTANT", vo.role());
        assertEquals("回答1", vo.content());
        assertEquals("thinking", vo.messageType());
        // thinking_stage 随 VO 下发（replayFromPg 降级回放据此重建带 stage 的 THINKING 事件）
        assertEquals("understanding", vo.thinkingStage());
        assertEquals(10L, vo.runId());
        verify(messageMapper).selectList(any());
    }

    @Test
    @DisplayName("findByRunId → assistant 实体行拆行还原事件序 VO（QU→thinking+query_plan，seq 倒推连续）")
    void findByRunId_splitsAssistantEntityRows() {
        // Given: 实体行 content = spec §3.1 JSON（QU 形态：reasoning + query_plan payload）
        ChatMessage entity = new ChatMessage();
        entity.setId(5L);
        entity.setRole("ASSISTANT");
        entity.setMessageType("assistant");
        entity.setContent(AssistantEntitySplitter.toEntityJson(new AssistantMessageCapture(
                "understanding",
                "分析意图\n收窄查询",
                "{\"intent\":\"chat\",\"rewritten\":[\"你好\"],\"filters\":{\"courseNames\":[]}}",
                List.of())));
        entity.setRunId(10L);
        entity.setSeq(2);
        when(messageMapper.selectList(any())).thenReturn(List.of(entity));

        List<ChatMessageVO> result = messageService.findByRunId(10L);

        // Then: 拆出 thinking(understanding) + query_plan 两 VO（与实体化前 VO 形态一致，前端零改动）
        assertEquals(2, result.size(), "实体行应拆成事件序 VO 列表");
        ChatMessageVO thinking = result.get(0);
        assertEquals("thinking", thinking.messageType());
        assertEquals("understanding", thinking.thinkingStage(), "thinking 行 stage 取自实体 JSON（不依赖列）");
        assertEquals("分析意图\n收窄查询", thinking.content());
        assertEquals(1, thinking.seq(), "VO.seq 按数组序倒推");
        assertEquals(5L, thinking.id(), "拆出 VO 继承实体行 id（反馈目标可用）");

        ChatMessageVO queryPlan = result.get(1);
        assertEquals("query_plan", queryPlan.messageType());
        assertEquals(
                "{\"intent\":\"chat\",\"rewritten\":[\"你好\"],\"filters\":{\"courseNames\":[]}}",
                queryPlan.content(),
                "query_plan 行 content 与 SSE payload 同构（前端 parse 契约不变）");
        assertEquals(2, queryPlan.seq());
    }

    @Test
    @DisplayName("findByRunId → 主 agent 实体拆 thinking + TOOL_CALL×N + 正文，与增量行混合排序正确")
    void findByRunId_splitsMainAgentEntityAndInterleavesWithToolResultRows() {
        // Given: 实体行（主 agent：思考+工具调用+正文，3 个拆行 VO 占 seq 3..5）+ TOOL_RESULT 独立行（seq=6）
        ChatMessage entity = new ChatMessage();
        entity.setId(6L);
        entity.setRole("ASSISTANT");
        entity.setMessageType("assistant");
        entity.setContent(AssistantEntitySplitter.toEntityJson(new AssistantMessageCapture(
                "generating",
                "生成思考",
                "最终正文",
                List.of(new AssistantMessageCapture.AssistantToolCall("call-1", "searchKnowledge", "{}")))));
        entity.setRunId(10L);
        entity.setSeq(5);
        ChatMessage toolResult = new ChatMessage();
        toolResult.setId(7L);
        toolResult.setRole("ASSISTANT");
        toolResult.setMessageType("TOOL_RESULT");
        toolResult.setContent("{\"toolCallId\":\"call-1\",\"status\":\"success\",\"output\":\"x\"}");
        toolResult.setRunId(10L);
        toolResult.setSeq(6);
        when(messageMapper.selectList(any())).thenReturn(List.of(entity, toolResult));

        List<ChatMessageVO> result = messageService.findByRunId(10L);

        // Then: 事件序 = [thinking(3), TOOL_CALL(4), 正文(5), TOOL_RESULT(6)]（实体拆行与增量行混合排序正确）
        assertEquals(4, result.size());
        assertEquals("thinking", result.get(0).messageType());
        assertEquals(3, result.get(0).seq(), "VO.seq 按数组序倒推：实体 seq=5、3 个 VO 占 3..5");
        assertEquals("TOOL_CALL", result.get(1).messageType());
        assertTrue(result.get(1).content().contains("\"toolCallId\":\"call-1\""));
        assertEquals(4, result.get(1).seq());
        assertEquals(null, result.get(2).messageType(), "正文行为末位拆行 VO（seq=实体 seq）");
        assertEquals("最终正文", result.get(2).content());
        assertEquals(5, result.get(2).seq());
        assertEquals("TOOL_RESULT", result.get(3).messageType());
        assertEquals(6, result.get(3).seq());
    }

    @Test
    @DisplayName("findByRunId → 实体行 JSON 损坏降级为正文行输出（spec 3.6-5，不回滚查询）")
    void findByRunId_corruptEntity_degradesToBodyRow() {
        ChatMessage corrupt = new ChatMessage();
        corrupt.setId(8L);
        corrupt.setRole("ASSISTANT");
        corrupt.setMessageType("assistant");
        corrupt.setContent("{损坏的 JSON");
        corrupt.setRunId(10L);
        corrupt.setSeq(1);
        when(messageMapper.selectList(any())).thenReturn(List.of(corrupt));

        List<ChatMessageVO> result = messageService.findByRunId(10L);

        assertEquals(1, result.size());
        assertEquals(null, result.get(0).messageType(), "损坏实体降级为正文行");
        assertEquals("{损坏的 JSON", result.get(0).content(), "正文行 content = 原文");
        assertEquals(1, result.get(0).seq());
    }

    @Test
    @DisplayName("findBySessionId → 按会话查询全部消息（实体行原样返回，投影仅实体行所需 7 列）")
    @SuppressWarnings("unchecked")
    void findBySessionId_returnsMessages() {
        ChatMessage msg = new ChatMessage();
        msg.setId(1L);
        msg.setSessionId(8L);
        msg.setRole("ASSISTANT");
        msg.setContent("{\"schema\":\"assistant-v1\",\"stage\":\"generating\"}");
        msg.setMessageType("assistant");
        msg.setSourcesJson("[1]");
        msg.setTokenCount(10);
        msg.setRunId(10L);
        msg.setSeq(1);
        msg.setTraceId("trace-1");
        msg.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 1));
        when(messageMapper.selectList(any())).thenReturn(List.of(msg));

        List<ChatMessageVO> result = messageService.findBySessionId(1L);

        // Then: 实体行原样返回（B 端一行看全貌，不拆行）
        assertEquals(1, result.size());
        ChatMessageVO vo = result.get(0);
        assertEquals(1L, vo.id());
        assertEquals("ASSISTANT", vo.role());
        assertEquals("{\"schema\":\"assistant-v1\",\"stage\":\"generating\"}", vo.content());
        assertEquals("assistant", vo.messageType());
        assertEquals(10L, vo.runId());
        // 投影修订（2026-08-29）：SQL 不再投影 thinking_stage/intent_type（stage 在 content JSON 内）
        ArgumentCaptor<LambdaQueryWrapper<ChatMessage>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectList(captor.capture());
        String sqlSelect = captor.getValue().getSqlSelect();
        assertTrue(sqlSelect.contains("content"), "投影应含 content: " + sqlSelect);
        assertFalse(sqlSelect.contains("thinking_stage"), "投影不得含 thinking_stage: " + sqlSelect);
        assertFalse(sqlSelect.contains("intent_type"), "投影不得含 intent_type: " + sqlSelect);
    }

    @Test
    @DisplayName("findBySessionId → 无消息时返回空列表")
    void findBySessionId_noMessages_returnsEmpty() {
        when(messageMapper.selectList(any())).thenReturn(List.of());

        List<ChatMessageVO> result = messageService.findBySessionId(1L);

        assertTrue(result.isEmpty());
        verify(messageMapper).selectList(any());
    }

    @Test
    @DisplayName("countByRunId → 返回消息数量")
    void countByRunId_returnsCount() {
        when(messageMapper.selectCount(any())).thenReturn(5L);

        long count = messageService.countByRunId(10L);

        assertEquals(5L, count);
    }

    // ==================== findStudentMessagesBySession（R1 补口 A：学生历史消息） ====================

    /** 构造带 sources/attachments JSON 的消息行（模拟 DB 投影行） */
    private ChatMessage studentRow(Long id, String role, Long runId) {
        ChatMessage msg = new ChatMessage();
        msg.setId(id);
        msg.setSessionId(1L);
        msg.setRole(role);
        msg.setContent("内容-" + id);
        msg.setIntentType("knowledge_question");
        msg.setRunId(runId);
        msg.setSeq(1);
        msg.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 1));
        msg.setSourcesJson("[{\"chunkId\":101,\"docTitle\":\"RAG 讲义\",\"headingPath\":\"Ch3 > 3.2\",\"score\":0.87}]");
        msg.setAttachmentsJson("[{\"type\":\"image\",\"url\":\"0/a.png\",\"name\":\"a.png\",\"size\":1024}]");
        return msg;
    }

    /** 构造 understanding 阶段 thinking 行（2026-08-28 时间线改版：thinking_stage 投影下发） */
    private ChatMessage thinkingRow(Long id, Long runId) {
        ChatMessage msg = studentRow(id, "ASSISTANT", runId);
        msg.setMessageType("thinking");
        msg.setThinkingStage("understanding");
        msg.setContent("意图分析思考");
        return msg;
    }

    @Test
    @DisplayName(
            "findStudentMessagesBySession → 复合排序 createdAt asc + seq asc，投影含 sources_json/attachments_json/thinking_stage")
    @SuppressWarnings("unchecked")
    void findStudentMessagesBySession_ordersByCreatedAtAscSeqAsc() {
        // Given: 会话内有一个 COMPLETED run（含一行 understanding thinking 行）
        when(chatRunService.findVisibleRunStatuses(1L))
                .thenReturn(List.of(new ChatRunStatusVO(10L, "COMPLETED", null)));
        Page<ChatMessage> returned = new Page<>(1, 200);
        returned.setRecords(
                List.of(studentRow(1L, "USER", null), thinkingRow(2L, 10L), studentRow(3L, "ASSISTANT", 10L)));
        returned.setTotal(3);
        when(messageMapper.selectPage(any(), any())).thenReturn(returned);

        // When: 查询学生历史消息
        IPage<StudentMessageVO> result = messageService.findStudentMessagesBySession(1L, 1, 200);

        // Then: 分页结果转 VO（sources/attachments JSON 解析为对象数组）
        assertEquals(3, result.getRecords().size());
        StudentMessageVO first = result.getRecords().get(0);
        assertEquals("USER", first.role());
        assertEquals("RAG 讲义", first.sources().get(0).docTitle());
        assertEquals("0/a.png", first.attachments().get(0).url());
        assertEquals(3L, result.getTotal());
        // Then: thinking 行的 thinking_stage 随 VO 下发（2026-08-28 时间线改版：前端分段渲染）
        StudentMessageVO thinking = result.getRecords().get(1);
        assertEquals("thinking", thinking.messageType());
        assertEquals("understanding", thinking.thinkingStage());

        // Then: 排序为 createdAt asc + seq asc 复合（M5 同根因：批内 created_at 相同排序不稳定）
        ArgumentCaptor<LambdaQueryWrapper<ChatMessage>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectPage(any(), captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("created_at ASC"), "应为 createdAt 升序: " + sqlSegment);
        assertTrue(sqlSegment.contains("seq ASC"), "应为 seq 升序（复合排序第二键）: " + sqlSegment);

        // Then: 按需取列——投影含 sources_json/attachments_json/thinking_stage（服务端解析 JSON 与阶段键下发用）
        String sqlSelect = captor.getValue().getSqlSelect();
        assertTrue(sqlSelect.contains("sources_json"), "投影应含 sources_json: " + sqlSelect);
        assertTrue(sqlSelect.contains("attachments_json"), "投影应含 attachments_json: " + sqlSelect);
        assertTrue(sqlSelect.contains("thinking_stage"), "投影应含 thinking_stage: " + sqlSelect);
    }

    @Test
    @DisplayName("findStudentMessagesBySession → CANCELLED/ERROR run 的半截行保留（M4 新口径），ACTIVE/QUEUED 行剔除")
    @SuppressWarnings("unchecked")
    void findStudentMessagesBySession_keepsCancelledAndErrorRows() {
        // Given: 会话内三个终态 run（M4 三态口径）；ACTIVE run 9004 的行由库侧 IN 条件剔除
        when(chatRunService.findVisibleRunStatuses(1L))
                .thenReturn(List.of(
                        new ChatRunStatusVO(9001L, "COMPLETED", null),
                        new ChatRunStatusVO(9002L, "CANCELLED", null),
                        new ChatRunStatusVO(9003L, "ERROR", "模型调用失败")));
        Page<ChatMessage> returned = new Page<>(1, 200);
        returned.setRecords(List.of(studentRow(1L, "USER", null), studentRow(2L, "ASSISTANT", 9001L)));
        returned.setTotal(2);
        when(messageMapper.selectPage(any(), any())).thenReturn(returned);

        messageService.findStudentMessagesBySession(1L, 1, 200);

        // Then: 过滤条件为 role = 'USER' OR run_id IN (三态终态 runId)（取消/失败半截内容保留，无 N+1）
        ArgumentCaptor<LambdaQueryWrapper<ChatMessage>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectPage(any(), captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("role"), "应含 role 条件: " + sqlSegment);
        assertTrue(sqlSegment.contains("run_id"), "应含 run_id IN 条件: " + sqlSegment);
        assertTrue(sqlSegment.contains("OR"), "USER 行与终态 run 行应为 OR 关系: " + sqlSegment);
        Collection<Object> params = captor.getValue().getParamNameValuePairs().values();
        assertTrue(params.contains("USER"), "参数应含 USER 角色值");
        assertTrue(params.contains(9001L), "参数应含 COMPLETED runId");
        assertTrue(params.contains(9002L), "参数应含 CANCELLED runId（M4：取消 run 半截行保留）");
        assertTrue(params.contains(9003L), "参数应含 ERROR runId（M4：失败 run 半截行保留）");
    }

    @Test
    @DisplayName("findStudentMessagesBySession → 会话无终态 run 时退化为仅查 USER 行，size 超限钳制 500")
    @SuppressWarnings("unchecked")
    void findStudentMessagesBySession_noTerminalRunAndSizeClamp() {
        // Given: 会话内无任何终态 run（全部 run 仍在 QUEUED/ACTIVE 进行中）
        when(chatRunService.findVisibleRunStatuses(1L)).thenReturn(List.of());
        Page<ChatMessage> returned = new Page<>(1, 500);
        returned.setRecords(List.of(studentRow(1L, "USER", null)));
        returned.setTotal(1);
        when(messageMapper.selectPage(any(), any())).thenReturn(returned);

        // When: 传入超限 size=10000
        IPage<StudentMessageVO> result = messageService.findStudentMessagesBySession(1L, 1, 10000);

        // Then: 仅返回 USER 行，size 已钳制为 500
        assertEquals(1, result.getRecords().size());
        assertEquals("USER", result.getRecords().get(0).role());
        assertEquals(500, result.getSize());

        // Then: 空 visibleRunIds 不生成 IN ()（非法 SQL），条件退化为 role = 'USER'
        ArgumentCaptor<LambdaQueryWrapper<ChatMessage>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        ArgumentCaptor<Page<ChatMessage>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(messageMapper).selectPage(pageCaptor.capture(), captor.capture());
        assertEquals(500, pageCaptor.getValue().getSize(), "分页对象 size 应钳制为 500");
        String sqlSegment = captor.getValue().getSqlSegment();
        assertFalse(sqlSegment.contains("IN"), "空 runId 列表不应生成 IN 条件: " + sqlSegment);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("USER"));
    }

    @Test
    @DisplayName("StudentMessageVO → 终态行携带 runStatus；ERROR 行携带 errorMessage；USER 行两字段为 null")
    void studentMessageVo_carriesRunStatusAndError() {
        // Given: 会话内三态 run 各一行 assistant + 一行 USER（USER 行 runId 亦命中终态 run——
        // 按 spec「仅终态行」语义，USER 行两字段应恒 null）
        when(chatRunService.findVisibleRunStatuses(1L))
                .thenReturn(List.of(
                        new ChatRunStatusVO(9001L, "COMPLETED", null),
                        new ChatRunStatusVO(9002L, "CANCELLED", null),
                        new ChatRunStatusVO(9003L, "ERROR", "模型调用失败")));
        Page<ChatMessage> returned = new Page<>(1, 200);
        returned.setRecords(List.of(
                studentRow(1L, "USER", 9001L),
                studentRow(2L, "ASSISTANT", 9001L),
                studentRow(3L, "ASSISTANT", 9002L),
                studentRow(4L, "ASSISTANT", 9003L)));
        returned.setTotal(4);
        when(messageMapper.selectPage(any(), any())).thenReturn(returned);

        IPage<StudentMessageVO> result = messageService.findStudentMessagesBySession(1L, 1, 200);

        // Then: USER 行两字段恒 null（即使 runId 命中终态 run）
        StudentMessageVO userRow = result.getRecords().get(0);
        assertEquals("USER", userRow.role());
        assertNull(userRow.runStatus());
        assertNull(userRow.errorMessage());
        // COMPLETED / CANCELLED 行携带 runStatus，无 errorMessage
        StudentMessageVO completedRow = result.getRecords().get(1);
        assertEquals("COMPLETED", completedRow.runStatus());
        assertNull(completedRow.errorMessage());
        StudentMessageVO cancelledRow = result.getRecords().get(2);
        assertEquals("CANCELLED", cancelledRow.runStatus());
        assertNull(cancelledRow.errorMessage());
        // ERROR 行携带 runStatus + errorMessage（前端「生成失败」徽标 tooltip 数据源）
        StudentMessageVO errorRow = result.getRecords().get(3);
        assertEquals("ERROR", errorRow.runStatus());
        assertEquals("模型调用失败", errorRow.errorMessage());
    }

    // ==================== 消息实体化：学生历史拆行（2026-08-29，C 端历史消费面） ====================

    /** 构造 assistant 实体行（spec §3.1 JSON 内容，sources 携带真实来源；seq 传实体行序号） */
    private ChatMessage entityRow(Long id, Long runId, int seq, String stage, String reasoning, String text) {
        ChatMessage entity = studentRow(id, "ASSISTANT", runId);
        entity.setMessageType("assistant");
        entity.setContent(
                AssistantEntitySplitter.toEntityJson(new AssistantMessageCapture(stage, reasoning, text, List.of())));
        entity.setSeq(seq);
        return entity;
    }

    @Test
    @DisplayName("findStudentMessagesBySession → assistant 实体行拆行还原事件序 VO（前端 history-adapter 零改动）")
    void findStudentMessagesBySession_splitsAssistantEntityRows() {
        // Given: COMPLETED run 含 QU 实体行（拆 thinking+query_plan，实体 seq=2）与主 agent 实体行
        // （拆 thinking+正文，实体 seq=4——seq 按拆行末位倒推，与 persistMessages 赋位同源）
        when(chatRunService.findVisibleRunStatuses(1L))
                .thenReturn(List.of(new ChatRunStatusVO(10L, "COMPLETED", null)));
        ChatMessage quEntity = entityRow(
                2L,
                10L,
                2,
                "understanding",
                "QU 思考",
                "{\"intent\":\"chat\",\"rewritten\":[\"你好\"],\"filters\":{\"courseNames\":[]}}");
        ChatMessage mainEntity = entityRow(3L, 10L, 4, "generating", "生成思考", "最终回答");
        Page<ChatMessage> returned = new Page<>(1, 200);
        returned.setRecords(List.of(studentRow(1L, "USER", null), quEntity, mainEntity));
        returned.setTotal(3);
        when(messageMapper.selectPage(any(), any())).thenReturn(returned);

        // When
        IPage<StudentMessageVO> result = messageService.findStudentMessagesBySession(1L, 1, 200);

        // Then: 实体行拆成事件序 VO——QU 实体→thinking+query_plan、主 agent 实体→thinking+正文，
        // 行类型与实体化前完全一致（前端零改动）；正文行携带实体行 sources（来源卡不回归）
        List<StudentMessageVO> records = result.getRecords();
        assertEquals(5, records.size(), "USER + QU 拆 2 + 主 agent 拆 2");
        assertEquals("USER", records.get(0).role());
        StudentMessageVO quThinking = records.get(1);
        assertEquals("thinking", quThinking.messageType());
        assertEquals("understanding", quThinking.thinkingStage());
        assertEquals("QU 思考", quThinking.content());
        assertEquals(1, quThinking.seq(), "QU 实体拆 2 VO，thinking 在前占 seq-1");
        assertTrue(quThinking.sources().isEmpty(), "thinking 行 sources 恒空");
        StudentMessageVO queryPlan = records.get(2);
        assertEquals("query_plan", queryPlan.messageType());
        assertEquals(
                "{\"intent\":\"chat\",\"rewritten\":[\"你好\"],\"filters\":{\"courseNames\":[]}}",
                queryPlan.content(),
                "query_plan 行 content 与 SSE payload 同构（前端 parse 契约不变）");
        assertEquals(2, queryPlan.seq());
        StudentMessageVO mainThinking = records.get(3);
        assertEquals("thinking", mainThinking.messageType());
        assertEquals("generating", mainThinking.thinkingStage());
        assertEquals(3, mainThinking.seq(), "主 agent 实体拆 2 VO，thinking 在前占 seq-1");
        StudentMessageVO body = records.get(4);
        assertEquals(null, body.messageType(), "末位为正文行");
        assertEquals("最终回答", body.content());
        assertEquals(4, body.seq(), "正文行占实体 seq 末位");
        assertEquals("RAG 讲义", body.sources().get(0).docTitle(), "正文行 sources 取实体行 sources_json（来源卡不回归）");
    }

    // ==================== M5 replay：消息行软删 ====================

    @Test
    @DisplayName("softDeleteFromRun → session 内 runId >= fromRunId 的消息行逻辑删除（wrapper 携带范围条件）")
    void softDeleteFromRun_marksRowsDeleted() throws Exception {
        // 链式 remove() 依赖继承字段（baseMapper/entityClass），预置后 baseMapper.delete 可被 mock 驱动
        java.lang.reflect.Field baseMapper =
                com.baomidou.mybatisplus.extension.repository.CrudRepository.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(messageService, messageMapper);
        java.lang.reflect.Field entityClass =
                com.baomidou.mybatisplus.extension.repository.AbstractRepository.class.getDeclaredField("entityClass");
        entityClass.setAccessible(true);
        entityClass.set(messageService, ChatMessage.class);
        when(messageMapper.delete(any())).thenReturn(3);

        messageService.softDeleteFromRun(456L, 900L);

        // 范围逻辑删除：链式 remove 产生 LambdaUpdateWrapper，session_id 等值 + run_id >= fromRunId
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessage>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(messageMapper).delete(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("session_id"), "软删应按会话过滤: " + sqlSegment);
        assertTrue(sqlSegment.contains("run_id"), "软删应按 runId>=fromRunId 范围过滤: " + sqlSegment);
        Collection<Object> params = captor.getValue().getParamNameValuePairs().values();
        assertTrue(params.contains(456L), "会话参数应为入参 sessionId");
        assertTrue(params.contains(900L), "应携带起始 runId 作范围参数");
    }
}
