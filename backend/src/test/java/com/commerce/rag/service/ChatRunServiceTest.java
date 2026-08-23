package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.commerce.rag.convert.ChatRunConverterImpl;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.exception.ConcurrentRunException;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.service.impl.ChatRunServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.ChatRunVO;
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

    @Test
    @DisplayName("findStaleActive → 查询超时 ACTIVE run 并转 VO（M-8 巡检用）")
    void findStaleActive_returnsActiveRuns() {
        ChatRun stale = new ChatRun();
        stale.setId(1L);
        stale.setStatus("ACTIVE");
        when(runMapper.selectList(any())).thenReturn(List.of(stale));

        LocalDateTime now = LocalDateTime.now();
        List<ChatRunVO> result = runService.findStaleActive(now.minusMinutes(10), now.minusMinutes(5));

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals("ACTIVE", result.get(0).status());
    }

    @Test
    @DisplayName("findStaleActive → 同时覆盖滞留 ACTIVE 与滞留 QUEUED（B2-3：QUEUED 按 created_at 超阈值判定）")
    @SuppressWarnings("unchecked")
    void findStaleActive_coversStaleActiveAndStaleQueued() {
        // Given: 一条滞留 ACTIVE + 一条滞留 QUEUED（附件处理窗口崩溃/停机丢任务的 run）
        ChatRun active = new ChatRun();
        active.setId(1L);
        active.setStatus("ACTIVE");
        ChatRun queued = new ChatRun();
        queued.setId(2L);
        queued.setStatus("QUEUED");
        when(runMapper.selectList(any())).thenReturn(List.of(active, queued));

        LocalDateTime now = LocalDateTime.now();
        List<ChatRunVO> result = runService.findStaleActive(now.minusMinutes(10), now.minusMinutes(5));

        // Then: 两类滞留 run 均转 VO 返回（巡检统一置 ERROR 解锁会话）
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(r -> "ACTIVE".equals(r.status())));
        assertTrue(result.stream().anyMatch(r -> "QUEUED".equals(r.status())));

        // Then: 查询条件同时包含 ACTIVE（started_at 超时）与 QUEUED（created_at 超时）分支
        ArgumentCaptor<LambdaQueryWrapper<ChatRun>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runMapper).selectList(captor.capture());
        LambdaQueryWrapper<ChatRun> wrapper = captor.getValue();
        // 注意：先取 sqlSegment（触发嵌套条件合并），嵌套分支的参数才会并入根 wrapper 参数表
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("started_at"), "ACTIVE 分支应按 started_at 判超时");
        assertTrue(sqlSegment.contains("created_at"), "QUEUED 分支应按 created_at 判超时");
        assertTrue(sqlSegment.contains("OR"), "ACTIVE 与 QUEUED 两分支应为 OR 关系");
        Collection<Object> params = wrapper.getParamNameValuePairs().values();
        assertTrue(params.contains("ACTIVE"), "查询应含 ACTIVE 状态分支");
        assertTrue(params.contains("QUEUED"), "查询应含 QUEUED 状态分支（B2-3 巡检扩展）");
    }

    // ==================== collectUniqueAttachments 后续轮次附件重建聚合（Task 11，spec §5.1） ====================
    // 说明：findRecentAttachments 的 SQL 获取段走 this.lambdaQuery()（宪法主表内置链式），
    // MP 3.5.12 该链式构建时内窥真实 MapperProxy（MybatisUtils.getMapperProxy），纯 Mockito 无法
    // mock selectList；故聚合逻辑下沉 collectUniqueAttachments（纯函数）在此直测业务语义。

    /** 构造带附件 JSON 的 run 行（id + attachmentsJson，select 按需取列仅这两列） */
    private ChatRun runWithAttachments(Long id, String attachmentsJson) {
        ChatRun run = new ChatRun();
        run.setId(id);
        run.setAttachmentsJson(attachmentsJson);
        return run;
    }

    @Test
    @DisplayName("collectUniqueAttachments → 多 run 重复 url 仅保留最近一条（orderByDesc 后 putIfAbsent 语义）")
    void collectUniqueAttachments_deduplicatesByUrl_keepsMostRecent() {
        // Given: 模拟 orderByDesc(id) 后的查询行（最近 run 在前）；0/a.png 在 run3/run2 重复出现，文件名不同以区分归属
        ChatRun run3 =
                runWithAttachments(3L, "[{\"type\":\"image\",\"url\":\"0/a.png\",\"name\":\"a.png\",\"size\":1}]");
        ChatRun run2 =
                runWithAttachments(2L, "[{\"type\":\"image\",\"url\":\"0/a.png\",\"name\":\"old-a.png\",\"size\":1}]");
        ChatRun run1 = runWithAttachments(
                1L, "[{\"type\":\"document\",\"url\":\"0/doc.pdf\",\"name\":\"doc.pdf\",\"size\":2}]");

        // When: 最近 3 个 run 行进入聚合
        List<AttachmentRecord> result = runService.collectUniqueAttachments(List.of(run3, run2, run1));

        // Then: 去重后 2 条；相同 url 保留最近 run（run3）的记录
        assertEquals(2, result.size());
        AttachmentRecord keptImage = result.stream()
                .filter(r -> "0/a.png".equals(r.url()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应保留 0/a.png"));
        assertEquals("a.png", keptImage.name(), "同 url 去重应保留最近 run 一条（run3 的 name 优先）");
        assertTrue(result.stream().anyMatch(r -> "0/doc.pdf".equals(r.url()) && "doc.pdf".equals(r.name())));
    }

    @Test
    @DisplayName("collectUniqueAttachments → 单个 run 非法 JSON 跳过，不影响其余 run")
    void collectUniqueAttachments_invalidJsonRun_skippedOthersKept() {
        // Given: run2 的 attachments_json 为损坏数据（非法 JSON），run1/run3 为合法附件
        ChatRun run1 = runWithAttachments(
                1L, "[{\"type\":\"image\",\"url\":\"0/good.png\",\"name\":\"good.png\",\"size\":1}]");
        ChatRun run2 = runWithAttachments(2L, "not-json{");
        ChatRun run3 = runWithAttachments(
                3L, "[{\"type\":\"document\",\"url\":\"0/doc.pdf\",\"name\":\"doc.pdf\",\"size\":2}]");

        // When: 非法 run 参与重建
        List<AttachmentRecord> result = runService.collectUniqueAttachments(List.of(run3, run2, run1));

        // Then: 非法 run 被跳过且不抛异常（不扩散损坏数据），其余两 run 记录完整保留
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(r -> "0/good.png".equals(r.url()) && "good.png".equals(r.name())));
        assertTrue(result.stream().anyMatch(r -> "0/doc.pdf".equals(r.url()) && "doc.pdf".equals(r.name())));
        assertTrue(result.stream().noneMatch(r -> r.url().contains("bad")));
    }

    @Test
    @DisplayName("collectUniqueAttachments → null/空白 attachmentsJson 跳过，仅保留有效记录")
    void collectUniqueAttachments_nullOrBlankJson_skipped() {
        // Given: run1 attachmentsJson 为 null、run2 为空白串（isNotNull 未过滤的空白行由服务防御性跳过）
        ChatRun run1 = runWithAttachments(1L, null);
        ChatRun run2 = runWithAttachments(2L, "   ");
        ChatRun run3 =
                runWithAttachments(3L, "[{\"type\":\"image\",\"url\":\"0/img.png\",\"name\":\"img.png\",\"size\":1}]");

        // When
        List<AttachmentRecord> result = runService.collectUniqueAttachments(List.of(run3, run2, run1));

        // Then: 仅保留有效行
        assertEquals(1, result.size());
        assertEquals("0/img.png", result.get(0).url());
        assertEquals("img.png", result.get(0).name());
    }

    @Test
    @DisplayName("collectUniqueAttachments → 空输入返回空列表（无匹配 run 场景，不抛异常）")
    void collectUniqueAttachments_noMatches_returnsEmpty() {
        // When: 该会话从未带过附件（selectList 无行返回）
        List<AttachmentRecord> result = runService.collectUniqueAttachments(List.of());

        // Then: 返回空列表而非 null，worker 据此跳过附件处理
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
