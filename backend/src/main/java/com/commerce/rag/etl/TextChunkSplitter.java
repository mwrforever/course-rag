package com.commerce.rag.etl;

import java.util.List;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * 文本分片器 —— Spring AI TokenTextSplitter 的字符串直出封装
 *
 * <p>Spring AI 1.1.2 的 TextSplitter 公开 API 仅接受 org.springframework.ai.document.Document，
 * 与本项目实体 Document（com.commerce.rag.entity.Document）同名（宪法禁止全路径类名），
 * 故子类化提升 protected splitText(String) 可见性，直接对原始文本分片。
 *
 * <p>框架行为（1.1.2 字节码实锤）：内部以 JTokkit CL100K_BASE 编码 token 序列后按
 * chunkSize 切片再解码——无固定 overlap 参数（见计划决策点 1）；相邻 chunk 之间仅以
 * ASCII 句末标点（. ! ?）或换行符回卷实现连续性（CJK 句末标点 。！？ 不参与回卷），
 * 边界句不截断；decode 往返保留原文（无空格拼接副作用）。
 *
 * @author commerce-rag
 */
public class TextChunkSplitter extends TokenTextSplitter {

    /** 小于该字符数的 chunk 不输出（过滤纯标点/空白碎块，框架按解码后文本字符长度判断） */
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 5;

    /** 单次分片最大 chunk 数（超长文档防御上限） */
    private static final int MAX_NUM_CHUNKS = 10000;

    /**
     * @param chunkSize         目标 chunk token 数（etl.chunk.size）
     * @param minChunkSizeChars 句子边界回卷的最小字符数（etl.chunk.min-chunk-size-chars）
     */
    public TextChunkSplitter(int chunkSize, int minChunkSizeChars) {
        super(chunkSize, minChunkSizeChars, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, true);
    }

    @Override
    public List<String> splitText(String text) {
        return super.splitText(text);
    }
}
