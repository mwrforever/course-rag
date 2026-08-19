package com.commerce.rag.service;

import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.record.ContentHash;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 附件处理结果缓存 —— 文件字节 sha256 → 处理结果（caption / 局部向量列表）
 *
 * <p>spec §5.1：附件处理结果只在内存（Caffeine，LRU + 失效时间），同文件重复出现只处理一次；
 * 不落库、不进 Milvus、无跨会话归属语义。
 */
@Slf4j
@Service
public class AttachmentCacheService {

    /** 缓存实例：key=文件字节 sha256，value=处理结果 */
    private final Cache<String, Object> cache;

    /** 正式构造器（Spring 注入限额配置，由 AttachmentProperties 推导缓存容量与失效时间） */
    @Autowired
    public AttachmentCacheService(AttachmentProperties properties) {
        this(properties.cacheMaxSize(), properties.cacheExpireMinutes());
    }

    /** 测试构造器（直接给参数，供 service 包内单元测试直接 new，无需 Spring 上下文） */
    AttachmentCacheService(int maxSize, int expireMinutes) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(expireMinutes))
                .build();
    }

    /** 文件字节 sha256 十六进制摘要（缓存键） */
    public String computeHash(byte[] bytes) {
        return ContentHash.sha256Hex(bytes);
    }

    /**
     * 按 hash 取缓存；未命中时执行 processor 并把结果写入缓存
     *
     * <p>用 Caffeine {@code get(key, fn)} 原子单次计算：Caffeine 保证每个 key 的 mapping
     * 函数至多执行一次（同 hash 并发首次 miss 不重复跑 caption/向量化等昂贵 LLM 处理，spec §5.1）；
     * 函数返回 null 时自动不入缓存，无需手写 null 判断。
     *
     * @param hash      文件字节 sha256
     * @param processor 处理函数（入参为文件字节，返回处理结果；返回 null 不入缓存）
     * @param bytes     文件字节（未命中时作为 processor 入参，避免调用方重复持有字节）
     * @return 处理结果
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrProcess(String hash, Function<byte[], T> processor, byte[] bytes) {
        return (T) cache.get(hash, k -> processor.apply(bytes));
    }

    /** 缓存条目数（测试/监控用） */
    public long size() {
        return cache.estimatedSize();
    }
}
