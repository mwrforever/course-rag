package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.convert.ChatSessionConverterImpl;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.mapper.ChatMessageMapper;
import com.commerce.rag.service.impl.ChatMessageServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.ChatMessageVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    /** 被测实现（spy：saveBatch 为 MP 框架方法，mock 环境无 SqlSessionFactory，stub 后验证调用） */
    private ChatMessageServiceImpl messageService;

    @BeforeEach
    void setUp() {
        messageService = spy(new ChatMessageServiceImpl(messageMapper, new ChatSessionConverterImpl()));
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
    @DisplayName("findByRunId → 按 run 查询消息（seq 升序）")
    void findByRunId_returnsMessages() {
        ChatMessage msg = new ChatMessage();
        when(messageMapper.selectList(any())).thenReturn(List.of(msg));

        List<ChatMessage> result = messageService.findByRunId(10L);

        assertEquals(1, result.size());
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
}
