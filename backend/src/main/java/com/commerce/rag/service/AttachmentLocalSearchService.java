package com.commerce.rag.service;

import com.commerce.rag.record.DocumentLocalChunk;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 附件局部检索 —— 内存余弦相似度 Top-K（spec §5.4：文档作局部检索语料）
 *
 * <p>不经过 Milvus（附件不进系统知识库）；文档量级小（≤50MB 文本），内存线性扫描足够。
 */
@Service
public class AttachmentLocalSearchService {

    /**
     * 按余弦相似度检索局部语料 Top-K
     *
     * @param chunks       局部语料（文档附件分片）
     * @param queryVector  查询向量（用户问题 embedding）
     * @param topK         返回条数上限
     * @return 按相似度降序的分片列表（空语料/空查询返回空列表）
     */
    public List<DocumentLocalChunk> search(List<DocumentLocalChunk> chunks, float[] queryVector, int topK) {
        if (chunks == null || chunks.isEmpty() || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        return chunks.stream()
                .filter(c -> c.vector() != null && c.vector().length > 0)
                .map(c -> new ScoredChunk(c, cosine(c.vector(), queryVector)))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .map(ScoredChunk::chunk)
                .toList();
    }

    /** 余弦相似度（零向量返回 0） */
    public double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 带分数的分片载体 */
    private record ScoredChunk(DocumentLocalChunk chunk, double score) {}
}
