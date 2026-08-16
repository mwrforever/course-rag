package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.convert.ChatRunConverterImpl;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.exception.ConcurrentRunException;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.service.impl.ChatRunServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.ChatRunVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * IChatRunService 单元测试 —— Run 生命周期（并发守卫 / 状态流转 / VO 出边界）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IChatRunService Run 生命周期测试")
class ChatRunServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private ChatRunMapper runMapper;

    /** 被测实现（手动构造：注入真实转换器，保证实体 → VO 映射可验证） */
    private ChatRunServiceImpl runService;

    @BeforeEach
    void setUp() {
        runService = new ChatRunServiceImpl(runMapper, new ChatRunConverterImpl());
    }

    @Test
    @DisplayName("createRun → 创建 QUEUED Run 并返回 VO（初始字段随落库实体）")
    void createRun_insertsQueuedRun() {
        ChatRunVO result = runService.createRun(1L, 5L);

        assertEquals(1L, result.sessionId());
        assertEquals(5L, result.userId());
        assertEquals("QUEUED", result.status());
        // 落库实体携带初始字段（modelCalls/metaJson 为内部字段不随 VO 出边界，经 captor 校验落库实体）
        ArgumentCaptor<ChatRun> captor = ArgumentCaptor.forClass(ChatRun.class);
        verify(runMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getModelCalls());
        assertEquals("{}", captor.getValue().getMetaJson());
    }

    @Test
    @DisplayName("createRun → 同会话并发冲突时抛 ConcurrentRunException")
    void createRun_conflict_throwsConcurrentRunException() {
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(runMapper)
                .insert(any(ChatRun.class));

        ConcurrentRunException ex = assertThrows(ConcurrentRunException.class, () -> runService.createRun(1L, 5L));

        assertTrue(ex.getMessage().contains("已有活跃的 Run"));
    }

    @Test
    @DisplayName("updateStatus → ACTIVE 时记录 startedAt")
    void updateStatus_active_setsStartedAt() {
        runService.updateStatus(1L, "ACTIVE");

        verify(runMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("updateStatus → 终态（COMPLETED/CANCELLED/ERROR）记录 endedAt")
    void updateStatus_terminal_setsEndedAt() {
        runService.updateStatus(1L, "COMPLETED");
        runService.updateStatus(2L, "CANCELLED");
        runService.updateStatus(3L, "ERROR");

        verify(runMapper, times(3)).update(isNull(), any());
    }

    @Test
    @DisplayName("findById → 返回 Run VO（业务字段完整映射）")
    void findById_returnsRun() {
        ChatRun run = new ChatRun();
        run.setId(1L);
        run.setSessionId(1L);
        run.setUserId(5L);
        run.setStatus("ACTIVE");
        when(runMapper.selectById(1L)).thenReturn(run);

        ChatRunVO result = runService.findById(1L);

        assertEquals(1L, result.id());
        assertEquals(5L, result.userId());
        assertEquals("ACTIVE", result.status());
    }

    @Test
    @DisplayName("findById → Run 不存在返回 null（调用方据此判 404）")
    void findById_notFound_returnsNull() {
        when(runMapper.selectById(99L)).thenReturn(null);

        assertNull(runService.findById(99L));
    }
}
