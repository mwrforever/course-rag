package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.mapper.ChatMessageMapper;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.mapper.ChatSessionMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ChatSessionService 单元测试 —— 会话 CRUD 与级联删除
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatSessionService 会话管理测试")
class ChatSessionServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private ChatMessageMapper messageMapper;
    @Mock
    private ChatRunMapper runMapper;

    @InjectMocks
    private ChatSessionService sessionService;

    @Test
    @DisplayName("createSession → 插入 ACTIVE 会话并返回带 ID 实体")
    void createSession_insertsActiveSession() {
        ChatSession result = sessionService.createSession(5L, "新对话");

        assertEquals(5L, result.getUserId());
        assertEquals("新对话", result.getTitle());
        assertEquals("ACTIVE", result.getStatus());
        verify(sessionMapper).insert(result);
    }

    @Test
    @DisplayName("findActiveSessions → 按用户分页查询活跃会话")
    void findActiveSessions_returnsPage() {
        Page<ChatSession> page = new Page<>(1, 20);
        when(sessionMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<ChatSession> result = sessionService.findActiveSessions(5L, 1);

        assertSame(page, result);
        verify(sessionMapper).selectPage(any(Page.class), any());
    }

    @Test
    @DisplayName("updateTitle → 更新会话标题")
    void updateTitle_updatesTitle() {
        sessionService.updateTitle(1L, "新标题");

        verify(sessionMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("updateLastMessageAt → 刷新最后消息时间")
    void updateLastMessageAt_updatesTimestamp() {
        sessionService.updateLastMessageAt(1L);

        verify(sessionMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("closeSession → 状态置为 CLOSED")
    void closeSession_setsClosed() {
        sessionService.closeSession(1L);

        verify(sessionMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("findById → 返回会话实体")
    void findById_returnsSession() {
        ChatSession session = new ChatSession();
        session.setId(1L);
        when(sessionMapper.selectById(1L)).thenReturn(session);

        ChatSession result = sessionService.findById(1L);

        assertEquals(1L, result.getId());
    }

    @Test
    @DisplayName("findAllSessions → 管理端分页查询全部会话")
    void findAllSessions_returnsPage() {
        Page<ChatSession> page = new Page<>(1, 20);
        when(sessionMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<ChatSession> result = sessionService.findAllSessions(1, 20);

        assertSame(page, result);
    }

    @Test
    @DisplayName("findSessionsByUser → 按用户分页查询（不限状态）")
    void findSessionsByUser_returnsPage() {
        Page<ChatSession> page = new Page<>(1, 20);
        when(sessionMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<ChatSession> result = sessionService.findSessionsByUser(5L, 1, 20);

        assertSame(page, result);
        verify(sessionMapper).selectPage(any(Page.class), any());
    }

    @Test
    @DisplayName("deleteSession → 级联软删消息、Run 与会话")
    void deleteSession_cascadesSoftDelete() {
        sessionService.deleteSession(1L, 9L);

        // 三个 mapper 均收到软删更新（消息、Run、会话本身）
        verify(messageMapper).update(isNull(), any());
        verify(runMapper).update(isNull(), any());
        verify(sessionMapper).update(isNull(), any());
    }
}
