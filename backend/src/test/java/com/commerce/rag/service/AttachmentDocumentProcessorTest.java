package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.record.DocumentLocalChunk;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 文档附件局部处理测试 —— 解析（Tika）→ 切分（TextChunkSplitter）→ 向量化（EmbeddingModel）+ Caffeine 缓存命中
 *
 * <p>EmbeddingModel 为接口，测试以匿名类实现 call(EmbeddingRequest)（Spring AI 1.1.2
 * 的 embed(String)/embed(List) 便利方法最终都路由到 call），用固定向量断言处理器逐块向量化
 * 的输出与 mock 一致；cacheHit 用例以 embedCalls 计数断言缓存命中只计算一次。
 */
class AttachmentDocumentProcessorTest {

    /** 构建固定 N 维向量的 mock EmbeddingModel（匿名类实现 call，供 embed(String) 内部路由调用） */
    private static EmbeddingModel fixedVectorModel(float[] vector) {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                // 每个输入文本返回同一固定向量（Spring AI 1.1.2 Embedding 构造须携带 index）
                return new EmbeddingResponse(request.getInstructions().stream()
                        .map(t -> new Embedding(vector, 0))
                        .toList());
            }

            @Override
            public float[] embed(Document document) {
                // 本处理器只走 embed(String) 便利方法，Document 重载不在测试路径内
                throw new UnsupportedOperationException("测试 mock 不支持 Document 重载");
            }
        };
    }

    @Test
    @DisplayName("TXT 文档 — 解析切分向量化全链路，按序生成局部分片且保留特征词")
    void processDocument_txtChunks_returnsVectorizedLocalChunks() {
        EmbeddingModel embedding = fixedVectorModel(new float[] {1f, 0f});
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentDocumentProcessor processor = new AttachmentDocumentProcessor(embedding, cache, 100, 64);
        // 放大测试文本：60 遍重复（每遍约 40 字符 ≈ 百计 token），远超 100 token 切分阈值，
        // 确保真实命中「解析→切分→向量化」全链路而非单块直通
        String text = ("常数的导数为零，sin x 的导数是 cos x，链式法则用于复合函数求导，导数公式表如下。").repeat(60);
        List<DocumentLocalChunk> chunks = processor.processDocument(text.getBytes(StandardCharsets.UTF_8), "note.txt");

        assertNotNull(chunks, "解析成功应返回分片列表");
        assertTrue(chunks.size() >= 1, "应至少产生一个局部分片");
        // 向量维度与 mock EmbeddingModel 输出一致（2 维），且逐块输出即 mock 固定向量
        assertEquals(2, chunks.get(0).vector().length, "分片向量维度应与 mock EmbeddingModel 输出一致");
        assertEquals(1f, chunks.get(0).vector()[0], 0.001);
        assertEquals(0f, chunks.get(0).vector()[1], 0.001);
        // 特征词在分片文本中保留（解析→切分链路未丢内容）
        assertTrue(chunks.get(0).text().contains("导数"), "分片应包含原始文本特征词\"导数\"");
        // 序号按文档内顺序 0 起递增（检索结果定位依据）
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index(), "分片序号应由 0 起按序递增");
        }
    }

    @Test
    @DisplayName("同文档重复处理 — Caffeine 命中，向量化只执行一次")
    void processDocument_cacheHit_embedsOnce() {
        final int[] embedCalls = {0};
        EmbeddingModel embedding = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                embedCalls[0]++;
                return new EmbeddingResponse(request.getInstructions().stream()
                        .map(t -> new Embedding(new float[] {1f}, 0))
                        .toList());
            }

            @Override
            public float[] embed(Document document) {
                // 本处理器只走 embed(String) 便利方法，Document 重载不在测试路径内
                throw new UnsupportedOperationException("测试 mock 不支持 Document 重载");
            }
        };
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentDocumentProcessor processor = new AttachmentDocumentProcessor(embedding, cache, 100, 64);
        byte[] bytes = "短文档内容。".repeat(5).getBytes(StandardCharsets.UTF_8);

        List<DocumentLocalChunk> first = processor.processDocument(bytes, "a.txt");
        List<DocumentLocalChunk> second = processor.processDocument(bytes, "a.txt");

        assertEquals(1, embedCalls[0], "同文档重复处理只向量化一次（第二次命中 Caffeine 缓存）");
        assertEquals(first.size(), second.size(), "缓存命中结果应与首次处理一致");
        assertTrue(first.size() >= 1, "短文档也应解析出至少一个分片");
    }
}
