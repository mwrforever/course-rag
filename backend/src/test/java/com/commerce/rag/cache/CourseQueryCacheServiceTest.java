package com.commerce.rag.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.properties.CacheTtlProperties;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 课程查询领域缓存测试（2026-08-25 多实例 §1：课程查询缓存 Caffeine→Redis 领域缓存类）
 *
 * <p>覆盖契约：
 * 1. get 命中返回缓存值且不执行 loader；未命中执行 loader 并写回（带 TTL）
 * 2. loader 返回 null 不入缓存（与 Caffeine「Null 不缓存」语义一致）
 * 3. put/invalidate/invalidateAll：键前缀拼接 + 委托 Redis
 * 4. evictCourse：单键三连 + search:/byTitle: 前缀 SCAN 清理（course 前缀保留）
 * 5. 全量清空 invalidateAll = SCAN 前缀全清（禁 KEYS，A.5.5 游标迭代）
 *
 * <p>mock RedisTemplate + RedisConnection（SCAN 游标队列驱动，多次 SCAN 独立），不依赖外部容器。
 */
@DisplayName("CourseQueryCacheService 课程查询领域缓存测试")
class CourseQueryCacheServiceTest {

    private static final String PREFIX = "course:query:";

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> ops;
    private CourseQueryCacheService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        service = new CourseQueryCacheService(
                redisTemplate,
                new CacheTtlProperties(Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("get 命中：返回缓存值，不执行 loader")
    void get_hit_returnsCachedWithoutLoading() {
        when(ops.get(PREFIX + "course:1")).thenReturn("cached");

        Object result = service.get("course:1", k -> {
            throw new AssertionError("命中缓存不应执行 loader");
        });

        assertThat(result).isEqualTo("cached");
    }

    @Test
    @DisplayName("get 未命中：执行 loader 写回（带 TTL）；再取命中缓存")
    void get_miss_loadingAndWriteBack() {
        when(ops.get(PREFIX + "course:1")).thenReturn(null, "loaded");

        Object first = service.get("course:1", k -> "loaded");
        Object second = service.get("course:1", k -> "loaded");

        assertThat(first).isEqualTo("loaded");
        assertThat(second).isEqualTo("loaded");
        verify(ops).set(PREFIX + "course:1", "loaded", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("loader 返回 null：不写 Redis 并返回 null（Caffeine「Null 不缓存」语义）")
    void get_missNull_notCached() {
        when(ops.get(PREFIX + "course:missing")).thenReturn(null);

        Object result = service.get("course:missing", k -> null);

        assertThat(result).isNull();
        verify(ops, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("put/invalidate：键带业务前缀；单键与批量删除委托 Redis")
    void putAndInvalidate_keysPrefixed() {
        service.put("course:1", "v1");
        verify(ops).set(PREFIX + "course:1", "v1", Duration.ofMinutes(5));

        service.invalidate("course:1");
        verify(redisTemplate).delete(PREFIX + "course:1");
    }

    @Test
    @DisplayName("evictCourse：单键三连 + search/byTitle 前缀 SCAN 清理（course 前缀保留）")
    void evictCourse_singleKeysAndPrefixClearing() {
        // SCAN 队列驱动游标：search:java:1 / byTitle:高数 / course:2 三个键（每次 scan 独立）
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.scan(any(ScanOptions.class)))
                .thenAnswer(inv -> scanCursor("search:java:1", "byTitle:高数", "course:2"));
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenAnswer(inv -> ((RedisCallback<?>) inv.getArgument(0)).doInRedis(connection));

        service.evictCourse(1L);

        // 单键三连（course:/contents:/schedule: 三个业务键）
        verify(redisTemplate).delete(PREFIX + "course:1");
        verify(redisTemplate).delete(PREFIX + "contents:1");
        verify(redisTemplate).delete(PREFIX + "schedule:1");
        // 前缀批量清理（SCAN → connection.del）：search:/byTitle: 命中删除；course:2 不匹配保留
        org.mockito.ArgumentCaptor<byte[][]> captor = org.mockito.ArgumentCaptor.forClass(byte[][].class);
        verify(connection).del(captor.capture());
        byte[][] delKeys = captor.getValue();
        assertThat(delKeys.length).isEqualTo(2);
        assertThat(delKeys[0]).isEqualTo(bytes(PREFIX + "search:java:1"));
        assertThat(delKeys[1]).isEqualTo(bytes(PREFIX + "byTitle:高数"));
    }

    @Test
    @DisplayName("invalidateAll：SCAN 游标迭代删除本前缀全部键（禁 KEYS）")
    void invalidateAll_scansAndDeletes() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.scan(any(ScanOptions.class))).thenAnswer(inv -> scanCursor("search:a:1", "course:1"));
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenAnswer(inv -> ((RedisCallback<?>) inv.getArgument(0)).doInRedis(connection));

        service.invalidateAll();

        org.mockito.ArgumentCaptor<byte[][]> captor = org.mockito.ArgumentCaptor.forClass(byte[][].class);
        verify(connection).del(captor.capture());
        byte[][] delKeys = captor.getValue();
        assertThat(delKeys.length).isEqualTo(2);
        assertThat(delKeys[0]).isEqualTo(bytes(PREFIX + "search:a:1"));
        assertThat(delKeys[1]).isEqualTo(bytes(PREFIX + "course:1"));
    }

    /** 队列驱动 SCAN 游标 mock（每次 scan 新建独立游标，多次 SCAN 互不影响） */
    private Cursor<byte[]> scanCursor(String... keys) {
        Cursor<byte[]> cursor = mock(Cursor.class);
        Queue<byte[]> queue = new ArrayDeque<>();
        for (String k : keys) {
            queue.add(bytes(PREFIX + k));
        }
        when(cursor.hasNext()).thenAnswer(inv -> !queue.isEmpty());
        when(cursor.next()).thenAnswer(inv -> queue.poll());
        return cursor;
    }

    /** UTF-8 字节（SCAN 游标返回的 key 载荷） */
    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
