package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.EpisodicExtractionResult;
import com.commerce.rag.record.EpisodicMemoryExtraction;
import com.commerce.rag.record.PreferenceCandidate;
import com.commerce.rag.record.PreferenceExtractionResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

/** 记忆提取流水线测试 —— 偏好通道（防抖/丢弃/去重 M-5）+ 经历通道（spec §8.4 防抖/超时/独立） */
class MemoryExtractionPipelineTest {

    private MemoryExtractionPipeline newPipeline(MemoryProperties props) {
        PreferenceExtractionService extract = mock(PreferenceExtractionService.class);
        IPreferenceService pref = mock(IPreferenceService.class);
        when(pref.findExistingValuesText(any())).thenReturn("无");
        MemoryExtractionPipeline p = new MemoryExtractionPipeline(
                props,
                new MemoryExtractionInputAssembler(),
                extract,
                pref,
                mock(EpisodicExtractionService.class),
                mock(IEpisodicMemoryService.class));
        return p;
    }

    /**
     * 构建经历记忆通道可直测的 pipeline（注入 mock 经历提取/记忆服务；内部偏好服务用 mock，
     * 需断言偏好 mock 的用例沿用 {@link #newPipeline} 或直接构造）
     */
    private MemoryExtractionPipeline newEpisodicPipeline(
            MemoryProperties props, EpisodicExtractionService episodicExtract, IEpisodicMemoryService episodicMem) {
        PreferenceExtractionService extract = mock(PreferenceExtractionService.class);
        IPreferenceService pref = mock(IPreferenceService.class);
        when(episodicMem.findActiveMemoriesText(any())).thenReturn("无");
        MemoryExtractionPipeline p = new MemoryExtractionPipeline(
                props, new MemoryExtractionInputAssembler(), extract, pref, episodicExtract, episodicMem);
        return p;
    }

    @Test
    @DisplayName("submit — 空消息直接跳过（不调度任何执行任务）")
    void submit_emptyMessagesSkips() throws Exception {
        MemoryProperties props = new MemoryProperties();
        MemoryExtractionPipeline p = newPipeline(props);
        p.submit(1L, List.of());

        Object futures = ReflectionTestUtils.getField(p, "futures");
        @SuppressWarnings("unchecked")
        Map<Long, ScheduledFuture<?>> map = (Map<Long, ScheduledFuture<?>>) futures;
        assertTrue(map.isEmpty(), "空消息不应产生待执行任务");
        // 显式泛型实参含通配符会被 javac 退化为 Object，先取字段再向下转型判空
        Object pending = ReflectionTestUtils.getField(p, "pending");
        assertTrue(((Map<?, ?>) pending).isEmpty(), "空消息不应预留 pending 数据");
    }

    @Test
    @DisplayName("防抖 — 同一窗口两次 submit，第二次取消第一次（只调度一次执行）")
    void submit_debouncesByUserId() throws Exception {
        MemoryProperties props = new MemoryProperties();
        props.getExtraction().setDebounceWindowSeconds(30);
        MemoryExtractionPipeline p = newPipeline(props);

        p.submit(1L, List.of(new UserMessage("第一条")));
        p.submit(1L, List.of(new UserMessage("第二条")));

        // 内部 futures 表应只有该 userId 一个待执行任务（第二次覆盖调度）
        Object futures = ReflectionTestUtils.getField(p, "futures");
        @SuppressWarnings("unchecked")
        Map<Long, ScheduledFuture<?>> map = (Map<Long, ScheduledFuture<?>>) futures;
        assertTrue(map.containsKey(1L));
        assertEquals(1, map.size(), "同一用户窗口内应合并为单个待执行任务");
    }

    @Test
    @DisplayName("执行 — 提取返回空（LLM 失败/无偏好）→ 不触发落库、不留 pending")
    void execute_skipsWriteWhenEmpty() {
        MemoryProperties props = new MemoryProperties();
        props.getExtraction().setDebounceWindowSeconds(1);
        PreferenceExtractionService extract = mock(PreferenceExtractionService.class);
        IPreferenceService pref = mock(IPreferenceService.class);
        when(pref.findExistingValuesText(any())).thenReturn("无");
        // 默认 mock 返回 null → 流水线按空处理
        MemoryExtractionPipeline p = new MemoryExtractionPipeline(
                props,
                new MemoryExtractionInputAssembler(),
                extract,
                pref,
                mock(EpisodicExtractionService.class),
                mock(IEpisodicMemoryService.class));

        // 直接调用 execute（包可见，防抖已合并完）
        p.executeInternal(1L, List.of(new UserMessage("当前问题"), new AssistantMessage("回答")));
        verify(pref, never()).applyExtraction(eq(1L), any());
    }

    @Test
    @DisplayName("执行 — 同 key+同 value 候选去重保首（顺序不变）；同 key 异质 value 保留（M-5）")
    void execute_dedupesCandidatesByKeyAndValue() {
        MemoryProperties props = new MemoryProperties();
        PreferenceExtractionService extract = mock(PreferenceExtractionService.class);
        IPreferenceService pref = mock(IPreferenceService.class);
        when(pref.findExistingValuesText(any())).thenReturn("无");
        PreferenceCandidate dup = new PreferenceCandidate("response_language", "中文", 0.9, 0.8);
        when(extract.extract(any(), any()))
                .thenReturn(new PreferenceExtractionResult(
                        List.of(
                                dup,
                                new PreferenceCandidate("response_language", "英文", 0.6, 0.5),
                                dup,
                                new PreferenceCandidate("response_verbosity", "简洁", 0.9, 0.8)),
                        List.of()));
        MemoryExtractionPipeline p = new MemoryExtractionPipeline(
                props,
                new MemoryExtractionInputAssembler(),
                extract,
                pref,
                mock(EpisodicExtractionService.class),
                mock(IEpisodicMemoryService.class));

        p.executeInternal(1L, List.of(new UserMessage("当前问题"), new AssistantMessage("回答")));

        ArgumentCaptor<PreferenceExtractionResult> captor = ArgumentCaptor.forClass(PreferenceExtractionResult.class);
        verify(pref).applyExtraction(eq(1L), captor.capture());
        PreferenceExtractionResult captured = captor.getValue();
        assertEquals(3, captured.candidates().size(), "同 (key,value) 去重保首，4 条入参应剩 3 条");
        assertEquals("中文", captured.candidates().get(0).value(), "重复候选保留首次出现");
        assertEquals("英文", captured.candidates().get(1).value(), "同 key 异质 value 保留");
        assertEquals("简洁", captured.candidates().get(2).value());
    }

    @Test
    @DisplayName("超时 — 提取 LLM 阻塞超时：快速返回、取消任务、不落库、不抛异常（spec §7.6 超时丢弃本批）")
    void execute_timeoutDropsBatchWithoutWrite() {
        MemoryProperties props = new MemoryProperties();
        props.getExtraction().setTimeoutMs(50L);
        PreferenceExtractionService extract = mock(PreferenceExtractionService.class);
        IPreferenceService pref = mock(IPreferenceService.class);
        when(pref.findExistingValuesText(any())).thenReturn("无");
        // 模拟外部模型卡死：提取调用远比超时窗口久（永不返回），用来触发 TimeoutException 分支
        when(extract.extract(any(), any())).thenAnswer(inv -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                // cancel(true) 中断了提取线程，属预期，恢复中断标记后正常结束
                Thread.currentThread().interrupt();
            }
            return null;
        });
        MemoryExtractionPipeline p = new MemoryExtractionPipeline(
                props,
                new MemoryExtractionInputAssembler(),
                extract,
                pref,
                mock(EpisodicExtractionService.class),
                mock(IEpisodicMemoryService.class));

        // executeInternal 应俘获超时并丢弃本批：正常返回、不等待外部 LLM、不落库、不抛异常
        long start = System.currentTimeMillis();
        p.executeInternal(1L, List.of(new UserMessage("当前问题"), new AssistantMessage("回答")));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 800, "超时路径应快速返回（不等待外部 LLM），实际耗时 " + elapsed + "ms");
        verify(pref, never()).applyExtraction(eq(1L), any());
    }

    // ==================== 经历记忆通道（spec §8.4） ====================

    @Test
    @DisplayName("经历 submit — 同窗口重复调度取消前一窗口、pending 保留最新消息（防抖合并，独立 futuresEpisodic）")
    void submitEpisodic_schedulesAndDebounces() throws Exception {
        MemoryProperties props = new MemoryProperties();
        props.getExtraction().setDebounceWindowSeconds(30);
        MemoryExtractionPipeline p =
                newEpisodicPipeline(props, mock(EpisodicExtractionService.class), mock(IEpisodicMemoryService.class));

        p.submitEpisodic(1L, 2L, List.of(new UserMessage("第一条")));
        p.submitEpisodic(1L, 2L, List.of(new UserMessage("第二条")));

        // 独立 futuresEpisodic 表：同用户窗口内合并为单个待执行任务（第二次覆盖调度）
        Object futures = ReflectionTestUtils.getField(p, "futuresEpisodic");
        @SuppressWarnings("unchecked")
        Map<Long, ScheduledFuture<?>> map = (Map<Long, ScheduledFuture<?>>) futures;
        assertTrue(map.containsKey(1L));
        assertEquals(1, map.size(), "同用户经历记忆窗口内应合并为单个待执行任务");

        // pendingEpisodic 保留最新消息（latest wins 防抖合并）
        Object pending = ReflectionTestUtils.getField(p, "pendingEpisodic");
        @SuppressWarnings("unchecked")
        Map<Long, List<Message>> pendingMap = (Map<Long, List<Message>>) pending;
        List<Message> merged = pendingMap.get(1L);
        assertEquals(1, merged.size(), "防抖合并后应只剩最新一批");
        assertEquals("第二条", merged.get(0).getText(), "pending 保留最新消息");

        // 到期执行：取走合并后的最新批次，pending/futures 均清理
        p.executeEpisodic(1L, 2L);
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(p, "pendingEpisodic")).isEmpty(), "执行后 pending 应清空");
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(p, "futuresEpisodic")).isEmpty(), "执行后 futures 应移除该用户");
    }

    @Test
    @DisplayName("经历执行 — 提取 LLM 阻塞超时：快速返回、取消任务、不落库（spec §8.4 超时丢弃本批）")
    void executeEpisodicInternal_timeout_discardsBatch() {
        MemoryProperties props = new MemoryProperties();
        props.getExtraction().setTimeoutMs(50L);
        EpisodicExtractionService episodicExtract = mock(EpisodicExtractionService.class);
        IEpisodicMemoryService episodicMem = mock(IEpisodicMemoryService.class);
        // 模拟外部模型卡死：提取调用远比超时窗口久（永不返回），用来触发 TimeoutException 分支
        when(episodicExtract.extract(any(), any())).thenAnswer(inv -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                // cancel(true) 中断了提取线程，属预期，恢复中断标记后正常结束
                Thread.currentThread().interrupt();
            }
            return null;
        });
        MemoryExtractionPipeline p = newEpisodicPipeline(props, episodicExtract, episodicMem);

        // executeEpisodicInternal 应俘获超时并丢弃本批：正常返回、不等待外部 LLM、不落库、不抛异常
        long start = System.currentTimeMillis();
        p.executeEpisodicInternal(1L, 2L, List.of(new UserMessage("当前问题"), new AssistantMessage("回答")));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 800, "超时路径应快速返回（不等待外部 LLM），实际耗时 " + elapsed + "ms");
        verify(episodicMem, never()).applyExtraction(eq(1L), any(), any());
    }

    @Test
    @DisplayName("经历执行 — 提取返回空结果（LLM 失败/无记忆）→ 不触发落库")
    void executeEpisodicInternal_emptyResult_skips() {
        MemoryProperties props = new MemoryProperties();
        EpisodicExtractionService episodicExtract = mock(EpisodicExtractionService.class);
        IEpisodicMemoryService episodicMem = mock(IEpisodicMemoryService.class);
        // 显式返回空结果（与 null 同走「无条目」跳过分支，spec §8.4 失败降级）
        when(episodicExtract.extract(any(), any())).thenReturn(EpisodicExtractionResult.empty());
        MemoryExtractionPipeline p = newEpisodicPipeline(props, episodicExtract, episodicMem);

        p.executeEpisodicInternal(1L, 2L, List.of(new UserMessage("当前问题"), new AssistantMessage("回答")));

        verify(episodicMem, never()).applyExtraction(eq(1L), any(), any());
    }

    @Test
    @DisplayName("经历执行 — 提取返回 1 条 → applyExtraction(userId, sessionId, result) 被调用、生效数返回")
    void executeEpisodicInternal_writes() {
        MemoryProperties props = new MemoryProperties();
        EpisodicExtractionResult result = new EpisodicExtractionResult(List.of(new EpisodicMemoryExtraction(
                true, "CREATE", "learning_goal", "目标是完成 Java 课程", "学习目标", null, 0.9, 0.8, 0.9, null)));
        EpisodicExtractionService episodicExtract = mock(EpisodicExtractionService.class);
        IEpisodicMemoryService episodicMem = mock(IEpisodicMemoryService.class);
        when(episodicExtract.extract(any(), any())).thenReturn(result);
        // mock 生效动作数（applyExtraction 返回）——流水线仅透传并记日志
        when(episodicMem.applyExtraction(eq(1L), eq(2L), any())).thenReturn(1);
        MemoryExtractionPipeline p = newEpisodicPipeline(props, episodicExtract, episodicMem);

        p.executeEpisodicInternal(1L, 2L, List.of(new UserMessage("当前问题"), new AssistantMessage("回答")));

        ArgumentCaptor<EpisodicExtractionResult> captor = ArgumentCaptor.forClass(EpisodicExtractionResult.class);
        verify(episodicMem).applyExtraction(eq(1L), eq(2L), captor.capture());
        assertSame(result, captor.getValue(), "落库结果应为提取返回的同一结果对象");
    }

    @Test
    @DisplayName("经历 submit — userId/消息为空 → 不调度任何执行任务（空守卫）")
    void submitEpisodic_nullGuard_skips() {
        MemoryProperties props = new MemoryProperties();
        MemoryExtractionPipeline p =
                newEpisodicPipeline(props, mock(EpisodicExtractionService.class), mock(IEpisodicMemoryService.class));

        p.submitEpisodic(null, 2L, List.of(new UserMessage("消息")));
        p.submitEpisodic(1L, 2L, null);
        p.submitEpisodic(1L, 2L, List.of());

        Object futures = ReflectionTestUtils.getField(p, "futuresEpisodic");
        assertTrue(((Map<?, ?>) futures).isEmpty(), "空输入不应产生待执行任务");
        Object pending = ReflectionTestUtils.getField(p, "pendingEpisodic");
        assertTrue(((Map<?, ?>) pending).isEmpty(), "空输入不应预留 pending 数据");
    }

    @Test
    @DisplayName("两通道独立 — 同 userId 同窗口偏好与经历各自调度，互不取消对方（spec §8.4 互不阻塞）")
    void episodicAndPreferenceChannelsIndependent() throws Exception {
        MemoryProperties props = new MemoryProperties();
        props.getExtraction().setDebounceWindowSeconds(30);
        MemoryExtractionPipeline p =
                newEpisodicPipeline(props, mock(EpisodicExtractionService.class), mock(IEpisodicMemoryService.class));

        // 同一 userId 同一窗口分别投递偏好与经历两条通道
        p.submit(1L, List.of(new UserMessage("偏好消息"), new AssistantMessage("偏好回答")));
        p.submitEpisodic(1L, 2L, List.of(new UserMessage("经历消息"), new AssistantMessage("经历回答")));

        // 偏好与经历各有独立的待执行任务（互不覆盖、互不取消）
        Object futures = ReflectionTestUtils.getField(p, "futures");
        Object futuresEpisodic = ReflectionTestUtils.getField(p, "futuresEpisodic");
        assertEquals(1, ((Map<?, ?>) futures).size(), "偏好通道应有独立调度任务");
        assertEquals(1, ((Map<?, ?>) futuresEpisodic).size(), "经历通道应有独立调度任务");

        // 偏好池到期执行：仅清理偏好 pending，经历 pending 不受影响
        p.execute(1L);
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(p, "pending")).isEmpty(), "偏好通道执行后偏好 pending 清空");
        assertEquals(1, ((Map<?, ?>) ReflectionTestUtils.getField(p, "pendingEpisodic")).size(), "偏好通道执行不影响经历 pending");
    }
}
