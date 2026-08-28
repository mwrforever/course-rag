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
import com.commerce.rag.service.impl.ChatMessageServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.ChatMessageVO;
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
    @DisplayName("findBySessionId → 按会话查询全部消息（返回消息 VO，剔除内部字段）")
    void findBySessionId_returnsMessages() {
        ChatMessage msg = new ChatMessage();
        msg.setId(1L);
        msg.setSessionId(8L);
        msg.setRole("user");
        msg.setContent("问题1");
        msg.setMessageType("TEXT");
        msg.setIntentType("knowledge_question");
        msg.setSourcesJson("[1]");
        msg.setTokenCount(10);
        msg.setRunId(10L);
        msg.setSeq(1);
        msg.setTraceId("trace-1");
        msg.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 1));
        when(messageMapper.selectList(any())).thenReturn(List.of(msg));

        List<ChatMessageVO> result = messageService.findBySessionId(1L);

        assertEquals(1, result.size());
        ChatMessageVO vo = result.get(0);
        assertEquals(1L, vo.id());
        assertEquals("user", vo.role());
        assertEquals("问题1", vo.content());
        assertEquals("knowledge_question", vo.intentType());
        assertEquals(10L, vo.runId());
        verify(messageMapper).selectList(any());
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
        when(chatRunService.findCompletedRunIds(1L)).thenReturn(List.of(10L));
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
    @DisplayName("findStudentMessagesBySession → 非 COMPLETED 的 run 仅保留 USER 行（M3 半截过滤）")
    @SuppressWarnings("unchecked")
    void findStudentMessagesBySession_filtersIncompleteRunRows() {
        // Given: 会话内 run 10 已完成、run 20 已取消（CANCELLED run 的 assistant 行应被 SQL 过滤剔除）
        when(chatRunService.findCompletedRunIds(1L)).thenReturn(List.of(10L));
        Page<ChatMessage> returned = new Page<>(1, 200);
        returned.setRecords(List.of(studentRow(1L, "USER", null), studentRow(2L, "ASSISTANT", 10L)));
        returned.setTotal(2);
        when(messageMapper.selectPage(any(), any())).thenReturn(returned);

        messageService.findStudentMessagesBySession(1L, 1, 200);

        // Then: 过滤条件为 role = 'USER' OR run_id IN (completedRunIds)（半截内容剔除下沉 SQL，无 N+1）
        ArgumentCaptor<LambdaQueryWrapper<ChatMessage>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectPage(any(), captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("role"), "应含 role 条件: " + sqlSegment);
        assertTrue(sqlSegment.contains("run_id"), "应含 run_id IN 条件: " + sqlSegment);
        assertTrue(sqlSegment.contains("OR"), "USER 行与 COMPLETED run 行应为 OR 关系: " + sqlSegment);
        Collection<Object> params = captor.getValue().getParamNameValuePairs().values();
        assertTrue(params.contains("USER"), "参数应含 USER 角色值");
        assertTrue(params.contains(10L), "参数应含已完成 runId");
        assertFalse(params.contains(20L), "未完成 runId 不应进入查询参数");
    }

    @Test
    @DisplayName("findStudentMessagesBySession → 会话无 COMPLETED run 时退化为仅查 USER 行，size 超限钳制 500")
    @SuppressWarnings("unchecked")
    void findStudentMessagesBySession_noCompletedRunAndSizeClamp() {
        // Given: 会话内无 COMPLETED run（全部 run 被取消/异常）
        when(chatRunService.findCompletedRunIds(1L)).thenReturn(List.of());
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

        // Then: 空 completedRunIds 不生成 IN ()（非法 SQL），条件退化为 role = 'USER'
        ArgumentCaptor<LambdaQueryWrapper<ChatMessage>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        ArgumentCaptor<Page<ChatMessage>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(messageMapper).selectPage(pageCaptor.capture(), captor.capture());
        assertEquals(500, pageCaptor.getValue().getSize(), "分页对象 size 应钳制为 500");
        String sqlSegment = captor.getValue().getSqlSegment();
        assertFalse(sqlSegment.contains("IN"), "空 runId 列表不应生成 IN 条件: " + sqlSegment);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("USER"));
    }
}
