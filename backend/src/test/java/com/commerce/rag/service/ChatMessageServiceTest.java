package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.mapper.ChatMessageMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ChatMessageService 单元测试 —— 消息批量持久化与查询
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMessageService 消息服务测试")
class ChatMessageServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private ChatMessageMapper messageMapper;

    @InjectMocks
    private ChatMessageService messageService;

    @Test
    @DisplayName("batchInsert → 空列表直接返回，不触碰 mapper")
    void batchInsert_empty_returnsEarly() {
        messageService.batchInsert(List.of());

        verify(messageMapper, never()).batchInsert(anyList());
    }

    @Test
    @DisplayName("batchInsert → 为无 ID 消息分配雪花 ID、sourcesJson 兜底为 [] 后批量插入")
    void batchInsert_fillsIdAndSourcesJson() {
        ChatMessage msg1 = new ChatMessage();
        msg1.setSourcesJson("[{\"doc\":1}]");
        ChatMessage msg2 = new ChatMessage();
        // 无 ID、无 sourcesJson —— 触发兜底逻辑

        messageService.batchInsert(List.of(msg1, msg2));

        assertNotNull(msg1.getId());
        assertNotNull(msg2.getId());
        assertEquals("[{\"doc\":1}]", msg1.getSourcesJson());
        assertEquals("[]", msg2.getSourcesJson());
        verify(messageMapper).batchInsert(List.of(msg1, msg2));
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
    @DisplayName("findBySessionId → 按会话查询全部消息")
    void findBySessionId_returnsMessages() {
        when(messageMapper.selectList(any())).thenReturn(List.of());

        List<ChatMessage> result = messageService.findBySessionId(1L);

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
