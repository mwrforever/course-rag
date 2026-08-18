package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TextChunkSplitter 单元测试 —— TokenTextSplitter 字符串直出封装
 *
 * @author commerce-rag
 */
class TextChunkSplitterTest {

    @Test
    @DisplayName("长文本分片 — 每片 token 数不超过 chunkSize，文本不丢")
    void longText_splitsWithinTokenLimit() {
        TextChunkSplitter splitter = new TextChunkSplitter(100, 20);
        String longText = ("检索增强生成是一种结合检索与生成的架构范式，向量数据库负责存储嵌入向量，" + "混合检索融合了向量相似度与关键词匹配两种召回信号。").repeat(20);

        List<String> chunks = splitter.splitText(longText);

        assertTrue(chunks.size() > 1, "长文本应拆分为多片");
        for (String chunk : chunks) {
            assertTrue(
                    TokenEstimator.estimate(chunk) <= 120,
                    "单片 token 不应明显超过 chunkSize: " + TokenEstimator.estimate(chunk));
        }
        // 关键句不丢（decode 往返保留原文）
        String joined = String.join("", chunks);
        assertTrue(joined.contains("混合检索融合了向量相似度与关键词匹配"));
    }

    @Test
    @DisplayName("短文本 — 单块直出")
    void shortText_singleChunk() {
        TextChunkSplitter splitter = new TextChunkSplitter(768, 64);

        List<String> chunks = splitter.splitText("这是短文本内容，不足一个分片大小。");

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("短文本内容"));
    }

    @Test
    @DisplayName("空文本 — 空列表")
    void blankText_emptyList() {
        TextChunkSplitter splitter = new TextChunkSplitter(768, 64);

        assertEquals(0, splitter.splitText("   ").size());
        assertEquals(0, splitter.splitText("").size());
    }

    @Test
    @DisplayName("中文文本 — 无空格拼接副作用（decode 往返保留原文）")
    void chineseText_noSpaceCorruption() {
        TextChunkSplitter splitter = new TextChunkSplitter(768, 64);
        String chinese = "中文段落应当保持连续无空格。".repeat(50);

        List<String> chunks = splitter.splitText(chinese);

        for (String chunk : chunks) {
            assertTrue(
                    !chunk.contains(" 中") && !chunk.contains("文 "),
                    "中文不应被空格拆散: " + chunk.substring(0, Math.min(30, chunk.length())));
        }
    }
}
