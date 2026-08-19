package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.record.DocumentLocalChunk;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 附件局部检索测试（内存余弦相似度） */
class AttachmentLocalSearchServiceTest {

    @Test
    @DisplayName("余弦相似度 — 相同向量=1，正交向量=0")
    void cosine_basic() {
        AttachmentLocalSearchService svc = new AttachmentLocalSearchService();
        assertEquals(1.0, svc.cosine(new float[] {1f, 0f}, new float[] {1f, 0f}), 0.0001);
        assertEquals(0.0, svc.cosine(new float[] {1f, 0f}, new float[] {0f, 1f}), 0.0001);
    }

    @Test
    @DisplayName("Top-K 检索 — 按相似度降序返回")
    void search_topKOrdered() {
        AttachmentLocalSearchService svc = new AttachmentLocalSearchService();
        List<DocumentLocalChunk> chunks = List.of(
                new DocumentLocalChunk("a", new float[] {1f, 0f}, 0),
                new DocumentLocalChunk("b", new float[] {0.9f, 0.1f}, 1),
                new DocumentLocalChunk("c", new float[] {0f, 1f}, 2));
        List<DocumentLocalChunk> top = svc.search(chunks, new float[] {1f, 0.05f}, 2);
        assertEquals(2, top.size());
        assertEquals("a", top.get(0).text());
        assertEquals("b", top.get(1).text());
    }

    @Test
    @DisplayName("空语料 / 空查询 — 返回空列表")
    void search_empty() {
        AttachmentLocalSearchService svc = new AttachmentLocalSearchService();
        assertTrue(svc.search(List.of(), new float[] {1f}, 5).isEmpty());
        assertTrue(svc.search(List.of(new DocumentLocalChunk("a", new float[] {1f}, 0)), null, 5)
                .isEmpty());
    }
}
