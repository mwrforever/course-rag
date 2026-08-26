package com.commerce.rag.cache;

import com.commerce.rag.properties.CacheTtlProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

/**
 * 课程查询领域缓存（复杂失效语义场景，宪法 A.5.4 / B.1 cache/ 目录）
 *
 * <p>2026-08-25 多实例部署 §1 拍板 Redis 分布式化（Caffeine 本地缓存多实例写失效不互通）；
 * 属于复杂场景（键前缀批量失效无法用 Spring Cache 单键/全量表达），故以领域缓存类实现。
 *
 * <p>职责语义与旧 Caffeine 版一致（perf P2-2，写方失效钩子依赖的窗口不变）：
 * <ul>
 *   <li>{@link #get(String, Function)} 一击式加载：未命中调加载函数（幂等 DB 查询）并写回，返回 null 不入缓存</li>
 *   <li>{@link #evictCourse(Long)} 课程变更失效：单键三连（course:/contents:/schedule:）+ search:/byTitle:
 *       前缀批量失效（SCAN 游标，宪法 A.5.5 禁 KEYS）</li>
 * </ul>
 *
 * <p>键统一「业务前缀 + 业务键」（A.5.5 三段式）：{@code course:query:search:java:1} 等；
 * TTL 配置化（cache.ttl.course-query），显式 TTL 铁律（A.5.4）。
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class CourseQueryCacheService {

    private static final Logger log = LoggerFactory.getLogger(CourseQueryCacheService.class);

    /** Redis 键前缀（业务:实体定位，多缓存隔离） */
    public static final String KEY_PREFIX = "course:query:";

    /** 前缀批量失效的目标业务键前缀（课程数据变更影响可见性/排序/名称映射） */
    private static final List<String> EVICT_PREFIXES = List.of("search:", "byTitle:");

    /** 对象序列化 Redis 模板（key String / value GenericJackson2JsonRedisSerializer，见 CacheConfig） */
    private final RedisTemplate<String, Object> cacheObjectRedisTemplate;

    /** 缓存 TTL 配置（cache.ttl.course-query） */
    private final CacheTtlProperties cacheTtlProperties;

    /** 当前缓存 TTL（启动绑定后固定；配置化来源见 CacheTtlProperties） */
    private Duration ttl() {
        return cacheTtlProperties.courseQuery();
    }

    /** 拼接完整 Redis 键 */
    private String redisKey(String key) {
        return KEY_PREFIX + key;
    }

    /**
     * 一击式读取：命中直接返回；未命中调加载函数（幂等 DB 查询）写回并返回
     *
     * <p>跨实例无法原子执行加载函数（同 key 并发 miss 可能重复计算，幂等读可接受）；
     * 加载函数返回 null 时不写缓存（与 Caffeine「Null 不入缓存」语义一致）。
     *
     * @param key            业务键（如 course:1 / search:java:1 / contents:1 / schedule:1）
     * @param loader         加载函数（通常为 mapper 查询），返回 null 表示不存在
     * @return 缓存值或加载结果
     */
    public Object get(String key, Function<String, Object> loader) {
        Objects.requireNonNull(loader);
        Object cached = getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Object value = loader.apply(key);
        if (value != null) {
            // 写入即带 TTL（显式 TTL 铁律；先写 DB 后失效缓存的一致性铁律由调用方保证）
            cacheObjectRedisTemplate.opsForValue().set(redisKey(key), value, ttl());
        }
        return value;
    }

    /** 取缓存值（未命中返回 null） */
    public Object getIfPresent(String key) {
        return cacheObjectRedisTemplate.opsForValue().get(redisKey(key));
    }

    /** 显式写入（带 TTL；通常经 get(key, loader) 一击式写入，本方法供集成测试/特殊场景直接落值） */
    public void put(String key, Object value) {
        cacheObjectRedisTemplate.opsForValue().set(redisKey(key), value, ttl());
    }

    /** 单键失效（写方在写 DB 后调用） */
    public void invalidate(String key) {
        cacheObjectRedisTemplate.delete(redisKey(key));
    }

    /** 全量清空本缓存（SCAN 前缀删除）；常规失效走 evictCourse 单键 + 前缀，本方法供整缓重建场景 */
    public void invalidateAll() {
        deleteByPrefixes(List.of(""));
    }

    /**
     * 课程变更失效（先写 DB 后失效铁律）：单键三连 + search:/byTitle: 前缀批量失效
     *
     * <p>search:* 影响列表可见性与排序；byTitle:* 影响课程名→course_id 映射（P1-2 注释：
     * 漏失效最长 5 分钟脏读——已删课程仍被映射、改名后新旧名映射错位）。
     *
     * @param courseId 发生变更的课程 ID
     */
    public void evictCourse(Long courseId) {
        String id = String.valueOf(courseId);
        invalidate("course:" + id);
        invalidate("contents:" + id);
        invalidate("schedule:" + id);
        deleteByPrefixes(EVICT_PREFIXES);
        log.info("课程查询缓存失效: courseId={}", courseId);
    }

    /**
     * SCAN 游标删除匹配前缀的键（宪法 A.5.5：禁 KEYS 全量遍历，一律 SCAN 迭代）
     *
     * <p>一次 SCAN 收集本缓存全部键，Java 侧按前缀过滤删除（键量受缓存容量约束，短事务）。
     */
    private void deleteByPrefixes(List<String> prefixes) {
        cacheObjectRedisTemplate.execute((RedisCallback<Object>) connection -> {
            ScanOptions options =
                    ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build();
            List<byte[]> batch = new ArrayList<>();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    byte[] keyBytes = cursor.next();
                    String fullKey = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
                    boolean hit = prefixes.stream().anyMatch(p -> fullKey.substring(KEY_PREFIX.length())
                            .startsWith(p));
                    if (hit) {
                        batch.add(keyBytes);
                        if (batch.size() >= 100) {
                            connection.del(batch.toArray(new byte[0][]));
                            batch.clear();
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    connection.del(batch.toArray(new byte[0][]));
                }
            }
            return null;
        });
    }
}
