package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.PreferenceCandidate;
import com.commerce.rag.record.PreferenceExtractionResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

/** 偏好提取流水线测试 —— 防抖合并 / 提取失败丢弃 / 空输入跳过 / 同 key+同 value 候选去重（M-5） */
class MemoryExtractionPipelineTest {

    private MemoryExtractionPipeline newPipeline(MemoryProperties props) {
        PreferenceExtractionService extract = mock(PreferenceExtractionService.class);
        IPreferenceService pref = mock(IPreferenceService.class);
        when(pref.findExistingValuesText(any())).thenReturn("无");
        MemoryExtractionPipeline p =
                new MemoryExtractionPipeline(props, new MemoryExtractionInputAssembler(), extract, pref);
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
        MemoryExtractionPipeline p =
                new MemoryExtractionPipeline(props, new MemoryExtractionInputAssembler(), extract, pref);

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
        MemoryExtractionPipeline p =
                new MemoryExtractionPipeline(props, new MemoryExtractionInputAssembler(), extract, pref);

        p.executeInternal(1L, List.of(new UserMessage("当前问题"), new AssistantMessage("回答")));

        ArgumentCaptor<PreferenceExtractionResult> captor = ArgumentCaptor.forClass(PreferenceExtractionResult.class);
        verify(pref).applyExtraction(eq(1L), captor.capture());
        PreferenceExtractionResult captured = captor.getValue();
        assertEquals(3, captured.candidates().size(), "同 (key,value) 去重保首，4 条入参应剩 3 条");
        assertEquals("中文", captured.candidates().get(0).value(), "重复候选保留首次出现");
        assertEquals("英文", captured.candidates().get(1).value(), "同 key 异质 value 保留");
        assertEquals("简洁", captured.candidates().get(2).value());
    }
}
