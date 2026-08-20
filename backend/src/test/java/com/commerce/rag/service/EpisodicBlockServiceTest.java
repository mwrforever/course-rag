package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.EpisodicMemoryRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 经历记忆块组装测试 —— 状态标注 / 独立预算截断 / 空块 / 未知 type 用原 key（spec §8.7/§8.8）
 *
 * @author commerce-rag
 */
class EpisodicBlockServiceTest {

    private final MemoryProperties properties = new MemoryProperties();

    private final EpisodicBlockService blockService = new EpisodicBlockService(properties);

    /** 构造召回引用（id/type/content/summary/validity/score） */
    private EpisodicMemoryRef ref(String type, String content, String validity) {
        return new EpisodicMemoryRef(1L, type, content, "", validity, 0.9);
    }

    @Test
    @DisplayName("build → active 行标「当前」、历史行标「历史记录」")
    void build_formatsWithStatusAnnotation() {
        List<EpisodicMemoryRef> refs = List.of(
                ref("learning_progress", "已完成 Spring Boot 基础", "active"),
                ref("resolved_question", "Docker 容器端口映射问题已解决", "superseded"));

        String block = blockService.build(refs);

        assertTrue(block.contains("学习进度(当前):已完成 Spring Boot 基础"), "active 行应标「当前」");
        assertTrue(block.contains("已解决问题(历史记录):Docker 容器端口映射问题已解决"), "历史行应标「历史记录」");
        assertTrue(block.startsWith("<episodic>\n"), "块应以 <episodic> 开头");
        assertTrue(block.endsWith("</episodic>"), "块应以 </episodic> 收尾");
    }

    @Test
    @DisplayName("build → 超预算行组的累积 token 被截断在预算内")
    void build_truncatesByBudget() {
        // 预算压到 50 token：前两条短行累加不足预算，第三条超长行累加必超 → 被截断丢弃
        MemoryProperties tiny = new MemoryProperties();
        tiny.getEpisodic().setTokenBudget(50);
        EpisodicBlockService small = new EpisodicBlockService(tiny);

        List<EpisodicMemoryRef> refs = List.of(
                ref("learning_progress", "进展A", "active"),
                ref("learning_progress", "进展B", "active"),
                ref("personal_context", "超预算应被截断，这是一段很长很长很长的个人背景记忆内容，用于验证独立预算截断逻辑是否正确生效，正常不应进入输出块。", "active"));

        String block = small.build(refs);

        assertTrue(block.contains("进展A"), "预算内第一条应保留");
        assertTrue(block.contains("进展B"), "预算内第二条应保留");
        assertFalse(block.contains("超预算应被截断"), "超预算行应被截断丢弃");
        assertTrue(block.endsWith("</episodic>"), "截断后块仍完整收尾");
    }

    @Test
    @DisplayName("build → 首行即超预算 → 返回空串（无任何行可放，拦截器据此不注入）")
    void build_firstLineExceedsBudget_returnsEmpty() {
        // 预算压到 1 token：任何非空行的估算必超预算 → 首行 break → 只剩 <episodic> 头 → 返回空串
        MemoryProperties tiny = new MemoryProperties();
        tiny.getEpisodic().setTokenBudget(1);
        EpisodicBlockService small = new EpisodicBlockService(tiny);

        List<EpisodicMemoryRef> refs = List.of(ref("learning_progress", "任意内容行首行即超预算", "active"));

        assertEquals("", small.build(refs), "首行即超预算应返回空串（不注入半截块）");
    }

    @Test
    @DisplayName("build → null / 空列表返回空串（拦截器据此不注入）")
    void build_emptyReturnsEmptyString() {
        assertEquals("", blockService.build(null), "null 引用应返回空串");
        assertEquals("", blockService.build(List.of()), "空引用列表应返回空串");
    }

    @Test
    @DisplayName("build → type 不在 LABELS 时用原始 type 作标签")
    void build_unknownTypeUsesRawKey() {
        List<EpisodicMemoryRef> refs = List.of(ref("custom_type", "自定义内容", "active"));

        String block = blockService.build(refs);

        assertTrue(block.contains("custom_type(当前):自定义内容"), "未知 type 应使用原始 key 作标签");
    }
}
