package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.record.DocumentLocalChunk;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 文档附件局部处理测试 —— 解析（Tika）→ 切分（TextChunkSplitter）→ 向量化（EmbeddingModel 批量）+ Caffeine 缓存命中
 *
 * <p>EmbeddingModel 为接口，测试以匿名类实现 call(EmbeddingRequest)（Spring AI 1.1.2
 * 的 embed(String)/embed(List) 便利方法最终都路由到 call），用固定向量断言处理器批量向量化
 * 的输出与 mock 一致；cacheHit 用例以 embedCalls 计数断言缓存命中只计算一次；
 * 批量用例断言分批调用 embed(List) 且块向量数量/顺序与逐块语义完全一致（P2-1）。
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
        AttachmentDocumentProcessor processor = new AttachmentDocumentProcessor(embedding, cache, 100, 64, 16);
        // 放大测试文本：60 遍重复（每遍约 40 字符 ≈ 百计 token），远超 100 token 切分阈值，
        // 确保真实命中「解析→切分→向量化」全链路而非单块直通
        String text = ("常数的导数为零，sin x 的导数是 cos x，链式法则用于复合函数求导，导数公式表如下。").repeat(60);
        List<DocumentLocalChunk> chunks = processor.processDocument(text.getBytes(StandardCharsets.UTF_8), "note.txt");

        assertNotNull(chunks, "解析成功应返回分片列表");
        // 收紧断言：>1 块证明「切分」路径真实执行（单块退化的弱断言不足以命中切分逻辑）
        assertTrue(chunks.size() > 1, "应切出多块证明切分路径真实执行");
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
        AttachmentDocumentProcessor processor = new AttachmentDocumentProcessor(embedding, cache, 100, 64, 16);
        byte[] bytes = "短文档内容。".repeat(5).getBytes(StandardCharsets.UTF_8);

        List<DocumentLocalChunk> first = processor.processDocument(bytes, "a.txt");
        List<DocumentLocalChunk> second = processor.processDocument(bytes, "a.txt");

        assertEquals(1, embedCalls[0], "同文档重复处理只向量化一次（第二次命中 Caffeine 缓存）");
        assertEquals(first.size(), second.size(), "缓存命中结果应与首次处理一致");
        assertTrue(first.size() >= 1, "短文档也应解析出至少一个分片");
    }

    @Test
    @DisplayName("批量向量化 — 分片按批调用 embed(List)，单请求携带多条文本（P2-1）")
    void processDocument_batchEmbedding_multiTextsPerRequest() {
        // 记录每次 call 请求携带的文本条数（批量化生效 = 每请求多条、请求数 = ceil(分片数/批大小)）
        List<Integer> requestSizes = new ArrayList<>();
        EmbeddingModel embedding = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                requestSizes.add(request.getInstructions().size());
                return new EmbeddingResponse(request.getInstructions().stream()
                        .map(t -> new Embedding(new float[] {1f}, 0))
                        .toList());
            }

            @Override
            public float[] embed(Document document) {
                throw new UnsupportedOperationException("测试 mock 不支持 Document 重载");
            }
        };
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        // 批大小 3：文本切出远多于 3 块，验证跨多批分批调用
        AttachmentDocumentProcessor processor = new AttachmentDocumentProcessor(embedding, cache, 100, 64, 3);
        String text = ("常数的导数为零，sin x 的导数是 cos x，链式法则用于复合函数求导，导数公式表如下。").repeat(60);
        List<DocumentLocalChunk> chunks = processor.processDocument(text.getBytes(StandardCharsets.UTF_8), "note.txt");

        assertTrue(chunks.size() > 3, "应切出多于批大小的分片以覆盖跨批路径");
        // 全部向量非空时块数 = 分片数，批量化后请求携带文本总数仍覆盖全部分片（不丢块）
        int totalTexts = requestSizes.stream().mapToInt(Integer::intValue).sum();
        assertEquals(chunks.size(), totalTexts, "批量请求携带的文本总数应与分片数一致");
        // 请求数 = ceil(分片数/批大小)，首批满批 3 条（逐块调用会退化成每请求 1 条、请求数=分片数）
        assertEquals((chunks.size() + 2) / 3, requestSizes.size(), "请求数应为 ceil(分片数/批大小)");
        assertEquals(3, requestSizes.get(0), "首批应携带批大小条文本（单请求多文本的批量调用）");
        // 序号按文档内顺序 0 起递增（批量化不改变块序号语义）
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index(), "分片序号应由 0 起按序递增");
        }
    }

    @Test
    @DisplayName("空向量降级 — 批内空向量块跳过，其余块保留原始文档序号")
    void processDocument_blankVectorInBatch_skipsChunkKeepsOriginalIndex() {
        // 全局文档序号 1 的块返回空向量（按累计批内位置定位，与文本内容解耦）
        int[] processed = {0};
        EmbeddingModel embedding = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> outputs = new ArrayList<>();
                for (int i = 0; i < request.getInstructions().size(); i++) {
                    boolean blank = processed[0] + i == 1;
                    outputs.add(new Embedding(blank ? new float[0] : new float[] {1f}, 0));
                }
                processed[0] += request.getInstructions().size();
                return new EmbeddingResponse(outputs);
            }

            @Override
            public float[] embed(Document document) {
                throw new UnsupportedOperationException("测试 mock 不支持 Document 重载");
            }
        };
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentDocumentProcessor processor = new AttachmentDocumentProcessor(embedding, cache, 100, 64, 16);
        String text = ("常数的导数为零，sin x 的导数是 cos x，链式法则用于复合函数求导，导数公式表如下。").repeat(60);
        List<DocumentLocalChunk> chunks = processor.processDocument(text.getBytes(StandardCharsets.UTF_8), "note.txt");

        assertTrue(chunks.size() > 3, "应切出多于 3 块以覆盖空向量前后的块");
        // 空向量块（序号 1）不入结果，其余块全部保留且序号为原始文档位置（不重排）
        assertTrue(chunks.stream().noneMatch(c -> c.index() == 1), "空向量块应被跳过");
        assertTrue(chunks.stream().anyMatch(c -> c.index() == 0), "空向量前的块应保留");
        assertTrue(chunks.stream().anyMatch(c -> c.index() == 2), "空向量后的块应保留");
        assertEquals(1, chunks.get(0).vector().length, "保留块向量维度与 mock 输出一致");
    }
}
