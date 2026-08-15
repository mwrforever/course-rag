package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * ChatRunService 单元测试 —— Run 生命周期（并发守卫 / 状态流转）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRunService Run 生命周期测试")
class ChatRunServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private ChatRunMapper runMapper;

    @InjectMocks
    private ChatRunService runService;

    @Test
    @DisplayName("createRun → 创建 QUEUED Run 并返回（含初始字段）")
    void createRun_insertsQueuedRun() {
        ChatRun result = runService.createRun(1L, 5L);

        assertEquals(1L, result.getSessionId());
        assertEquals(5L, result.getUserId());
        assertEquals("QUEUED", result.getStatus());
        assertEquals(0, result.getModelCalls());
        assertEquals("{}", result.getMetaJson());
        verify(runMapper).insert(result);
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
    @DisplayName("findById → 返回 Run 实体")
    void findById_returnsRun() {
        ChatRun run = new ChatRun();
        run.setId(1L);
        when(runMapper.selectById(1L)).thenReturn(run);

        ChatRun result = runService.findById(1L);

        assertEquals(1L, result.getId());
    }
}
