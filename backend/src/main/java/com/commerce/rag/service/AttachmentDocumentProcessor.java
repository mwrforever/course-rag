package com.commerce.rag.service;

import com.commerce.rag.etl.TextChunkSplitter;
import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.record.DocumentLocalChunk;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.jsoup.Jsoup;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * 文档附件局部处理器 —— 解析（Tika）→ 切分（TextChunkSplitter）→ 向量化（EmbeddingModel）
 *
 * <p>spec §5.4：文档 = 局部检索语料；结果按文件字节 hash 缓存在 Caffeine（同文档只处理一次，
 * 解析失败返回空列表仍入缓存，失败文档重复出现不必重解析）。
 * 首版仅文本类文档（PDF/Word/TXT/MD），不做文档内嵌图片提取。
 *
 * <p>依赖注入：构造器注入 EmbeddingModel 与 AttachmentCacheService（手写双构造器——
 * 正式构造器供 Spring 装配，包私有测试构造器供 service 包内单测直 new，与 Task 4 先例一致）。
 *
 * @author commerce-rag
 */
@Slf4j
@Service
public class AttachmentDocumentProcessor {

    /** 向量化模型（qwen3-text-embedding 经 Spring AI EmbeddingModel 抽象） */
    private final EmbeddingModel embeddingModel;

    /** 附件处理结果缓存（文件字节 sha256 → 局部分片列表） */
    private final AttachmentCacheService cacheService;

    /** 分片目标 token 数（spec §4.1 与 ETL 同参） */
    private final int chunkSize;

    /** 分片最小字符数（spec §4.1 与 ETL 同参，过滤纯标点/空白碎块） */
    private final int minChunkSizeChars;

    /**
     * 正式构造器（Spring 依赖注入）
     *
     * <p>chunkSize/minChunkSizeChars 按 spec §4.1 硬编码 768/64（与 ETL 文档分片同参），
     * 不新增配置项。
     *
     * @param embeddingModel 向量化模型（Spring AI 装配的 EmbeddingModel bean）
     * @param cacheService   附件处理结果缓存（Task 4 产物）
     * @param properties     附件限额配置（当前仅承载缓存容量/失效时间，构造器暂不消费细节）
     */
    public AttachmentDocumentProcessor(
            EmbeddingModel embeddingModel, AttachmentCacheService cacheService, AttachmentProperties properties) {
        this.embeddingModel = embeddingModel;
        this.cacheService = cacheService;
        this.chunkSize = 768;
        this.minChunkSizeChars = 64;
    }

    /**
     * 测试构造器（直接给切分参数，服务包内单测传 100/64 触发真实切分）
     *
     * @param embeddingModel    向量化模型 mock（匿名类实现 call）
     * @param cacheService      附件处理结果缓存
     * @param chunkSize         分片目标 token 数
     * @param minChunkSizeChars 分片最小字符数
     */
    AttachmentDocumentProcessor(
            EmbeddingModel embeddingModel, AttachmentCacheService cacheService, int chunkSize, int minChunkSizeChars) {
        this.embeddingModel = embeddingModel;
        this.cacheService = cacheService;
        this.chunkSize = chunkSize;
        this.minChunkSizeChars = minChunkSizeChars;
    }

    /**
     * 处理文档附件：解析 → 切分 → 逐块向量化（结果按字节 hash 缓存）
     *
     * <p>缓存未命中才真正执行解析/向量化；同文件字节重复出现直接命中 Caffeine（同文档只处理一次）。
     *
     * @param bytes 文档字节（不允许为空）
     * @param name  原始文件名（Tika 类型识别提示，仅日志/审计用）
     * @return 局部分片列表（解析失败返回空列表，不中断对话）
     */
    public List<DocumentLocalChunk> processDocument(byte[] bytes, String name) {
        // 文件字节 sha256 作为缓存键（与 Task 4 图片 caption 缓存同键语义）
        String hash = cacheService.computeHash(bytes);
        // getOrProcess：Caffeine 原子单次计算，未命中执行 doProcess 并缓存整个结果列表
        return cacheService.getOrProcess(hash, b -> doProcess(b, name), bytes);
    }

    /**
     * 实际处理（缓存未命中时执行）：Tika 解析 → jsoup 提取纯文本 → TextChunkSplitter 切分 → 逐块向量化
     *
     * <p>解析失败返回空列表（不抛异常、不中断对话，warn 中文日志留痕）。
     *
     * @param bytes 文档字节
     * @param name  原始文件名（日志记录）
     * @return 局部分片列表（失败为空列表）
     */
    private List<DocumentLocalChunk> doProcess(byte[] bytes, String name) {
        try {
            // 1. Tika 解析 → XHTML → jsoup 提取纯文本（文档附件只需纯文本，不需 XhtmlDocumentParser 章节结构）
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ToHTMLContentHandler handler = new ToHTMLContentHandler(out, "UTF-8");
            Metadata metadata = new Metadata();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(new ByteArrayInputStream(bytes), handler, metadata, new ParseContext());
            String plainText = Jsoup.parse(out.toString(StandardCharsets.UTF_8)).text();

            // 2. 切分（复用 ETL 组件，spec §4.1 同参数）
            TextChunkSplitter splitter = new TextChunkSplitter(chunkSize, minChunkSizeChars);
            List<String> pieces = splitter.splitText(plainText);

            // 3. 逐块向量化（embed(String) 返回 float[]，Spring AI 1.1.2 便利方法）
            List<DocumentLocalChunk> chunks = new ArrayList<>(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                float[] vector = embeddingModel.embed(pieces.get(i));
                if (vector != null && vector.length > 0) {
                    chunks.add(new DocumentLocalChunk(pieces.get(i), vector, i));
                }
            }
            log.info("文档附件处理完成: name={}, 分片数={}", name, chunks.size());
            return chunks;
        } catch (Exception e) {
            // 解析失败不中断对话，返回空列表作为局部语料（spec §5.4）
            log.warn("文档附件解析失败，返回空语料: name={}, error={}", name, e.getMessage());
            return new ArrayList<>();
        }
    }
}
