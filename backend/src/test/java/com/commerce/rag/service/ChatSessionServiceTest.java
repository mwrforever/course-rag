package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.convert.ChatSessionConverter;
import com.commerce.rag.convert.ChatSessionConverterImpl;
import com.commerce.rag.convert.StudentConverter;
import com.commerce.rag.convert.StudentConverterImpl;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.mapper.ChatMessageMapper;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.mapper.ChatSessionMapper;
import com.commerce.rag.service.impl.ChatSessionServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.ChatSessionVO;
import com.commerce.rag.vo.SessionVO;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * IChatSessionService 单元测试 —— 会话 CRUD 与级联删除
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IChatSessionService 会话管理测试")
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

    /** 会话转换器用真实实现（MapStruct 生成类），转换行为由 ChatSessionConverterTest 单独覆盖 */
    @Spy
    private ChatSessionConverter chatSessionConverter = new ChatSessionConverterImpl();

    /** 学生端转换器用真实实现（MapStruct 生成类），转换行为由 StudentConverterTest 单独覆盖 */
    @Spy
    private StudentConverter studentConverter = new StudentConverterImpl();

    @InjectMocks
    private ChatSessionServiceImpl sessionService;

    /** 构造测试用会话实体（含摘要 VO 全部业务字段） */
    private ChatSession session(Long id) {
        ChatSession s = new ChatSession();
        s.setId(id);
        s.setUserId(5L);
        s.setTitle("会话" + id);
        s.setStatus("ACTIVE");
        s.setLastMessageAt(LocalDateTime.of(2026, 8, 15, 10, 0));
        s.setModel("qwen3.8-max");
        s.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 0));
        return s;
    }

    @Test
    @DisplayName("createSession → 插入 ACTIVE 会话并返回带 ID 的 SessionVO")
    void createSession_insertsActiveSession() {
        SessionVO result = sessionService.createSession(5L, "新对话");

        // 插入实体为 ACTIVE 会话（userId/title/status 透传）
        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionMapper).insert(captor.capture());
        ChatSession inserted = captor.getValue();
        assertEquals(5L, inserted.getUserId());
        assertEquals("新对话", inserted.getTitle());
        assertEquals("ACTIVE", inserted.getStatus());
        // 返回契约为 C 端会话 VO（不含 userId）
        assertEquals("新对话", result.title());
        assertEquals("ACTIVE", result.status());
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
    @DisplayName("findById → 返回会话摘要 VO，不存在返回 null")
    void findById_returnsSession() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L));

        ChatSessionVO result = sessionService.findById(1L);

        assertEquals(1L, result.id());
        assertEquals(5L, result.userId());
        assertEquals("会话1", result.title());
        assertEquals("ACTIVE", result.status());
        assertEquals("qwen3.8-max", result.model());

        // 会话不存在返回 null（controller 层 404）
        when(sessionMapper.selectById(99L)).thenReturn(null);
        assertNull(sessionService.findById(99L));
    }

    @Test
    @DisplayName("findAllSessions → 管理端分页查询全部会话（records 转摘要 VO）")
    void findAllSessions_returnsPage() {
        Page<ChatSession> page = new Page<>(1, 20);
        page.setRecords(List.of(session(1L)));
        page.setTotal(1);
        when(sessionMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<ChatSessionVO> result = sessionService.findAllSessions(1, 20);

        assertEquals(1, result.getRecords().size());
        ChatSessionVO vo = result.getRecords().get(0);
        assertEquals(5L, vo.userId());
        assertEquals("会话1", vo.title());
        assertEquals("qwen3.8-max", vo.model());
        assertEquals(1, result.getTotal());
    }

    @Test
    @DisplayName("findSessionsByUser → 按用户分页查询（records 转 SessionVO）")
    void findSessionsByUser_returnsPage() {
        Page<ChatSession> page = new Page<>(1, 20);
        page.setRecords(List.of(session(1L)));
        page.setTotal(1);
        when(sessionMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<SessionVO> result = sessionService.findSessionsByUser(5L, 1, 20);

        assertEquals(1, result.getRecords().size());
        SessionVO vo = result.getRecords().get(0);
        assertEquals(1L, vo.id());
        assertEquals("会话1", vo.title());
        assertEquals("ACTIVE", vo.status());
        assertEquals(1, result.getTotal());
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

    // ==================== B2-5 级联软删事务原子性 ====================

    /** Spring 事务元数据解析器 —— 与生产事务切面同一解析路径，验证注解会被识别且异常触发回滚 */
    private static final AnnotationTransactionAttributeSource TX_SOURCE = new AnnotationTransactionAttributeSource();

    @Test
    @DisplayName("B2-5: deleteSession 标注 @Transactional 且运行时异常触发回滚")
    void deleteSession_isTransactional_rollsBackOnRuntimeFailure() throws NoSuchMethodException {
        Method method = ChatSessionServiceImpl.class.getMethod("deleteSession", Long.class, Long.class);
        TransactionAttribute attr = TX_SOURCE.getTransactionAttribute(method, ChatSessionServiceImpl.class);

        // 注解存在（事务切面可识别）且 RuntimeException 触发回滚（默认回滚规则）
        assertNotNull(attr, "deleteSession 应标注 @Transactional（B2-5：三连 UPDATE 原子性）");
        assertTrue(attr.rollbackOn(new RuntimeException("级联软删中途失败")));
    }

    @Test
    @DisplayName("B2-5: deleteSession 中途失败 → 异常上抛且会话软删不执行（已执行的消息软删由事务回滚）")
    void deleteSession_midwayFailure_blocksSessionDelete() {
        // 第二步 chat_run 软删抛异常（模拟连接池耗尽/瞬时故障）——第一步 chat_message 已执行
        when(runMapper.update(isNull(), any())).thenThrow(new RuntimeException("chat_run 软删失败"));

        assertThrows(RuntimeException.class, () -> sessionService.deleteSession(1L, 9L));

        // 第一步已执行（其不落库由 @Transactional 回滚保证），后续会话软删被异常阻断
        verify(messageMapper).update(isNull(), any());
        verify(sessionMapper, never()).update(isNull(), any());
    }
}
