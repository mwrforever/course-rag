package com.commerce.rag.service;

import com.commerce.rag.entity.UserPreference;
import com.commerce.rag.properties.MemoryProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 偏好块缓存 —— 冻结机制（spec §7.8 防 prefix cache 破坏）
 *
 * <p>key=user_id、value=偏好块文本、expireAfterWrite=30min（配置化）：缓存期内注入内容
 * 字节不变 → 前缀稳定 → prefix cache 命中；过期后拉最新同步。空块也缓存（避免每轮查库）。
 *
 * <p>一致性：写偏好后不主动失效缓存（30min 到点自然过期拉新）——设计即「冻结一段时间 +
 * 定期同步」，偏好一变 prompt 立即变反会持续破坏 prefix cache。
 *
 * @author commerce-rag
 */
@Slf4j
@Service
public class PreferenceCacheService {

    private final Cache<Long, String> cache;
    private final IPreferenceService preferenceService;
    private final PreferenceBlockService blockService;

    // 手写构造器（非 @RequiredArgsConstructor）：需在构造器内用 MemoryProperties 构建 Caffeine
    // 实例（maximumSize/expireAfterWrite 全部配置化），属初始化逻辑场景，故不交给 Lombok 生成。
    public PreferenceCacheService(
            MemoryProperties properties, IPreferenceService preferenceService, PreferenceBlockService blockService) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getPreference().getCacheMaxSize())
                .expireAfterWrite(Duration.ofMinutes(properties.getPreference().getCacheExpireMinutes()))
                .build();
        this.preferenceService = preferenceService;
        this.blockService = blockService;
    }

    /**
     * 取该用户偏好块（缓存命中直接返回；未命中查 DB 组装最新并写入缓存）
     *
     * @param userId 所属用户
     * @return &lt;preference&gt; 块文本；无偏好返回空串（拦截器据此不注入）
     */
    public String getOrBuild(Long userId) {
        String cached = cache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        List<UserPreference> active = preferenceService.findActiveForInjection(userId);
        String block = blockService.build(active);
        cache.put(userId, block);
        log.debug("偏好块已缓存: userId={}, 长度={}", userId, block.length());
        return block;
    }

    /** 缓存条目数（测试/监控用） */
    public long size() {
        return cache.estimatedSize();
    }
}
