package com.commerce.rag.etl.chunker;

import com.commerce.rag.etl.parser.DocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义分块器
 *
 * 将解析后的文档章节切分为固定大小的 chunk，保留语义连贯性。
 *
 * 分块策略：
 * 1. 优先保持章节完整性：如果一个章节小于 chunkSize，整个章节作为一个 chunk
 * 2. 超长章节使用滑动窗口切分：chunk_size tokens + overlap tokens
 * 3. 每个 chunk 保留标题层级路径元数据
 *
 * token 估算：中文约 1.5 字符/token，英文约 4 字符/token。
 * 此处简化为按字符数估算（中文内容 1 char ≈ 1 token）。
 */
@Slf4j
@Component
public class SemanticChunker {

    @Value("${etl.chunk-size:512}")
    private int chunkSize;

    @Value("${etl.chunk-overlap:64}")
    private int chunkOverlap;

    /**
     * 将解析后的文档切分为 chunk 列表
     *
     * @param docId          文档 ID
     * @param parsedDocument 解析后的文档结构
     * @return chunk 列表，每个 chunk 包含文本内容和元数据
     */
    public List<TextChunk> chunk(String docId, DocumentParser.ParsedDocument parsedDocument) {
        log.info("开始分块: 文档={}, 章节数={}", parsedDocument.title(), parsedDocument.sections().size());

        List<TextChunk> chunks = new ArrayList<>();
        int globalIndex = 0;

        for (DocumentParser.ParsedSection section : parsedDocument.sections()) {
            int estimatedTokens = estimateTokens(section.content());

            if (estimatedTokens <= chunkSize) {
                // 章节未超过 chunk 大小限制，整块保留
                chunks.add(new TextChunk(
                        section.content(),
                        new ChunkMetadata(
                                docId, globalIndex++,
                                section.parentTitle(), section.headingPath(),
                                section.startPage(), section.endPage(),
                                estimatedTokens
                        )
                ));
            } else {
                // 超长章节：滑动窗口切分
                List<String> subChunks = slidingWindowSplit(section.content());
                for (String subChunk : subChunks) {
                    chunks.add(new TextChunk(
                            subChunk,
                            new ChunkMetadata(
                                    docId, globalIndex++,
                                    section.parentTitle(), section.headingPath(),
                                    section.startPage(), section.endPage(),
                                    estimateTokens(subChunk)
                            )
                    ));
                }
            }
        }

        log.info("分块完成: 文档={}, 生成 chunk 数={}", parsedDocument.title(), chunks.size());
        return chunks;
    }

    /**
     * 滑动窗口切分长文本
     *
     * 按 chunkSize 切分，相邻 chunk 之间保留 chunkOverlap 重叠，
     * 保证跨 chunk 边界的语义不丢失。
     *
     * @param text 待切分的长文本
     * @return 子文本列表
     */
    private List<String> slidingWindowSplit(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            result.add(text.substring(start, end));

            if (end >= text.length()) break;

            // 滑动窗口前进步长 = chunkSize - overlap
            start += chunkSize - chunkOverlap;
        }
        return result;
    }

    /**
     * 估算文本的 token 数量
     *
     * 简化策略：中文内容按字符数估算（1 char ≈ 1 token），
     * 英文内容按字符数/4 估算。混合内容取加权平均。
     * 生产环境应使用 tiktoken 或模型对应的 tokenizer。
     *
     * @param text 待估算文本
     * @return 估算的 token 数量
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 简单统计中文字符比例
        long chineseChars = text.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        double chineseRatio = (double) chineseChars / text.length();
        // 中文 1 char/token，英文 4 chars/token
        return (int) (text.length() * chineseRatio + text.length() * (1 - chineseRatio) / 4);
    }
}
