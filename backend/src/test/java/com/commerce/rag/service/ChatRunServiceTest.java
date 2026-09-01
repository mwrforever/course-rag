package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.repository.AbstractRepository;
import com.baomidou.mybatisplus.extension.repository.CrudRepository;
import com.commerce.rag.convert.ChatRunConverterImpl;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.exception.ConcurrentRunException;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.service.impl.ChatRunServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.ChatRunVO;
import java.lang.reflect.Field;
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

    // ==================== BUG-01 状态机条件守卫（终态不可复活 / ACTIVE 仅自 QUEUED） ====================

    @Test
    @DisplayName("BUG-01: updateStatus(ACTIVE) → UPDATE 携带 status=QUEUED 前置守卫（迟到任务无法复活终态 run）")
    @SuppressWarnings("unchecked")
    void updateStatus_active_guardedByQueuedPrecondition() {
        runService.updateStatus(1L, "ACTIVE");

        // 捕获 UPDATE wrapper：WHERE 必须含 status 前置条件（仅 QUEUED 可迁 ACTIVE），
        // 修复前为无条件 UPDATE——已被巡检置 ERROR 的 run 会被迟到队列任务复活
        ArgumentCaptor<LambdaUpdateWrapper<ChatRun>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ChatRun> wrapper = captor.getValue();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("status"), "ACTIVE 迁移必须带状态前置守卫: " + sqlSegment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("QUEUED"), "守卫应限定当前状态为 QUEUED");
        // startedAt 仍随 ACTIVE 写入（既有语义不变）
        assertTrue(wrapper.getSqlSet().contains("started_at"), "ACTIVE 迁移应写入 started_at");
    }

    @Test
    @DisplayName("BUG-01: updateStatus(终态) → UPDATE 携带 status IN (QUEUED,ACTIVE) 守卫（终态 run 不可再被改写）")
    @SuppressWarnings("unchecked")
    void updateStatus_terminal_guardedByNonTerminalPrecondition() {
        runService.updateStatus(1L, "ERROR");

        ArgumentCaptor<LambdaUpdateWrapper<ChatRun>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ChatRun> wrapper = captor.getValue();
        String sqlSegment = wrapper.getSqlSegment();
        // 终态仅可自 QUEUED/ACTIVE 迁移（覆盖 runPool 拒绝/入队回滚的 QUEUED→终态 与 正常执行的 ACTIVE→终态）；
        // 已终态的 run 不再命中任何迁移（含不可复活为 ACTIVE）
        assertTrue(sqlSegment.toUpperCase().contains("IN"), "终态守卫应为 IN (QUEUED,ACTIVE) 条件: " + sqlSegment);
        Collection<Object> params = wrapper.getParamNameValuePairs().values();
        assertTrue(params.contains("QUEUED"), "守卫集合应含 QUEUED");
        assertTrue(params.contains("ACTIVE"), "守卫集合应含 ACTIVE");
        assertTrue(wrapper.getSqlSet().contains("ended_at"), "终态迁移应写入 ended_at");
    }

    @Test
    @DisplayName("BUG-01: updateStatus → 返回影响行数（0=守卫拒绝，调用方据此短路）")
    void updateStatus_returnsAffectedRows() {
        // mapper 返回 1 行（守卫放行）→ 服务原样返回
        when(runMapper.update(isNull(), any())).thenReturn(1);
        assertEquals(1, runService.updateStatus(1L, "ACTIVE"));

        // mapper 返回 0 行（run 已离开迁移前提状态）→ 服务返回 0 供调用方跳过图执行
        when(runMapper.update(isNull(), any())).thenReturn(0);
        assertEquals(0, runService.updateStatus(1L, "ACTIVE"));
    }

    @Test
    @DisplayName("BUG-01: updateStatus → 未知状态直接拒绝（防止无守卫的无条件 UPDATE 漏洞）")
    void updateStatus_unknownStatus_rejected() {
        assertThrows(IllegalArgumentException.class, () -> runService.updateStatus(1L, "RUNNING"));
        verify(runMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("BUG-01: markErrorIfCurrent → 以观察状态为前提的 CAS 置 ERROR（巡检 TOCTOU 原子判定）")
    @SuppressWarnings("unchecked")
    void markErrorIfCurrent_casOnObservedStatus() {
        when(runMapper.update(isNull(), any())).thenReturn(1);

        int rows = runService.markErrorIfCurrent(1L, "QUEUED");

        // WHERE 必须同时绑定 id 与期望状态（SELECT 时观察值）：SELECT→UPDATE 窗口内刚转 ACTIVE
        // 的 run 不被误杀（0 行命中），主路径滞留 run 置 ERROR 行为不变
        assertEquals(1, rows);
        ArgumentCaptor<LambdaUpdateWrapper<ChatRun>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ChatRun> wrapper = captor.getValue();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("status"), "CAS 必须含期望状态前提: " + sqlSegment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("QUEUED"), "期望状态应为 SELECT 观察值 QUEUED");
        assertTrue(wrapper.getParamNameValuePairs().containsValue("ERROR"), "目标状态应为 ERROR");
        assertTrue(wrapper.getSqlSet().contains("ended_at"), "置 ERROR 应写入 ended_at");
    }

    @Test
    @DisplayName("BUG-01: markErrorIfCurrent → 0 行命中原样返回（run 状态已迁移，调用方跳过）")
    void markErrorIfCurrent_zeroRows_propagated() {
        when(runMapper.update(isNull(), any())).thenReturn(0);

        assertEquals(0, runService.markErrorIfCurrent(1L, "ACTIVE"));
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

    @Test
    @DisplayName("findCompletedRunIds → 查会话内 COMPLETED run 的 ID 列表（按需取列，R1 历史消息两步查询第一步）")
    @SuppressWarnings("unchecked")
    void findCompletedRunIds_returnsCompletedRunIdsOnly() {
        ChatRun completed = new ChatRun();
        completed.setId(10L);
        completed.setStatus("COMPLETED");
        when(runMapper.selectList(any())).thenReturn(List.of(completed));

        List<Long> runIds = runService.findCompletedRunIds(1L);

        // Then: 仅返回 runId 列表（供消息表 run_id IN 过滤，剔除取消/异常 run 的半截内容）
        assertEquals(List.of(10L), runIds);

        // Then: 查询条件为 session_id + status=COMPLETED，投影仅 id 列
        ArgumentCaptor<LambdaQueryWrapper<ChatRun>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runMapper).selectList(captor.capture());
        LambdaQueryWrapper<ChatRun> wrapper = captor.getValue();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("session_id"), "应按会话过滤: " + sqlSegment);
        assertTrue(sqlSegment.contains("status"), "应按状态过滤: " + sqlSegment);
        assertTrue(wrapper.getSqlSelect().contains("id"), "投影应仅取 id 列: " + wrapper.getSqlSelect());
        assertFalse(wrapper.getSqlSelect().contains("meta_json"), "不应取 meta_json 等大字段");
        assertTrue(wrapper.getParamNameValuePairs().containsValue("COMPLETED"), "状态参数应为 COMPLETED");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1L), "会话参数应为入参 sessionId");
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

    // ==================== existsActiveRun 活跃 run 存在性守卫（R3 删除接口 409 前置校验） ====================

    /**
     * 注入链式查询依赖的继承字段（baseMapper/entityClass）
     *
     * <p>纯 Mockito 下 {@code this.lambdaQuery()} 构建链时会经 getEntityClass → getMapperClass
     * → MybatisUtils.getMapperProxy 内窥真实 Mapper 代理（mock 非代理对象直接失败）；
     * 预置 entityClass 与 baseMapper 两个字段即可绕开内窥，使 selectCount 可被 mock 驱动。
     */
    private void injectChainFields() throws Exception {
        Field baseMapper = CrudRepository.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(runService, runMapper);
        Field entityClass = AbstractRepository.class.getDeclaredField("entityClass");
        entityClass.setAccessible(true);
        entityClass.set(runService, ChatRun.class);
    }

    @Test
    @DisplayName("existsActiveRun → 会话存在 QUEUED/ACTIVE run 时返回 true（R3 删除 409 守卫依据）")
    @SuppressWarnings("unchecked")
    void existsActiveRun_queuedOrActiveRun_returnsTrue() throws Exception {
        // Given: 会话内有活跃 run（selectCount 命中 1 行）
        injectChainFields();
        when(runMapper.selectCount(any())).thenReturn(1L);

        // When
        boolean exists = runService.existsActiveRun(1L);

        // Then: 活跃 run 存在（调用方据此抛 409 阻断删除）
        assertTrue(exists);

        // Then: 查询条件为 session_id 等值 + status IN (QUEUED, ACTIVE)
        ArgumentCaptor<LambdaQueryWrapper<ChatRun>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runMapper).selectCount(captor.capture());
        LambdaQueryWrapper<ChatRun> wrapper = captor.getValue();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("session_id"), "应按会话过滤: " + sqlSegment);
        assertTrue(sqlSegment.toUpperCase().contains("IN"), "活跃状态应为 IN 条件（QUEUED/ACTIVE）: " + sqlSegment);
        Collection<Object> params = wrapper.getParamNameValuePairs().values();
        assertTrue(params.contains(1L), "会话参数应为入参 sessionId");
        assertTrue(params.contains("QUEUED"), "状态集合应含 QUEUED");
        assertTrue(params.contains("ACTIVE"), "状态集合应含 ACTIVE");
    }

    @Test
    @DisplayName("existsActiveRun → 会话仅剩终态 run（COMPLETED 等）时返回 false（允许删除）")
    @SuppressWarnings("unchecked")
    void existsActiveRun_onlyTerminalRuns_returnsFalse() throws Exception {
        // Given: 会话内仅终态 run（QUEUED/ACTIVE 无命中，selectCount 为 0）
        injectChainFields();
        when(runMapper.selectCount(any())).thenReturn(0L);

        // When
        boolean exists = runService.existsActiveRun(1L);

        // Then: 无活跃 run，删除链路放行
        assertFalse(exists);
        // 查询仍发出（确认 false 来自 DB 判空而非异常短路）
        verify(runMapper).selectCount(any());
    }
}
