package com.commerce.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户附件限额与缓存配置（spec §5.2 用户拍板限额定稿）
 *
 * @param imageMaxSizeMb   单张图片大小上限（MB）
 * @param documentMaxSizeMb 单个文档大小上限（MB）
 * @param maxCount         单次消息附件个数上限
 * @param totalMaxSizeMb   单次消息附件合计大小上限（MB）
 * @param cacheMaxSize     Caffeine 附件处理结果缓存条数（LRU）
 * @param cacheExpireMinutes 附件处理结果缓存失效时间（分钟）
 */
@ConfigurationProperties(prefix = "attachment")
public record AttachmentProperties(
        int imageMaxSizeMb,
        int documentMaxSizeMb,
        int maxCount,
        int totalMaxSizeMb,
        int cacheMaxSize,
        int cacheExpireMinutes) {}
