package com.commerce.rag.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.cache.RegisterCodeCacheService.CodeVerifyStatus;
import com.commerce.rag.constants.AuthCacheKeys;
import com.commerce.rag.properties.RegisterProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 注册验证码缓存服务单元测试 —— 覆盖发送锁抢占、验证码存储、原子校验结果映射与兜底清码
 *
 * <p>边界说明：Redis 真实行为由集成测试覆盖（真实 Lua 执行），此处验证模板交互契约与状态机映射。</p>
 */
@ExtendWith(MockitoExtension.class)
class RegisterCodeCacheServiceTest {

    private static final String EMAIL = "student@example.com";

    /** 与生产一致的默认配置实例（record 直出，无 Spring 上下文依赖） */
    private final RegisterProperties properties =
            new RegisterProperties(Duration.ofMinutes(15), Duration.ofSeconds(60), 5, "问渠学堂", "主题");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RegisterCodeCacheService service;

    @BeforeEach
    void setUp() {
        service = new RegisterCodeCacheService(redisTemplate, properties);
    }

    @Test
    @DisplayName("tryAcquireSendSlot：SET NX 成功返回 true，窗口内后续请求返回 false")
    void tryAcquireSendSlot_setNxPreemptsSendRight() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(AuthCacheKeys.REGISTER_SEND_LOCK_PREFIX + EMAIL, "1", Duration.ofSeconds(60)))
                .thenReturn(true)
                .thenReturn(false)
                .thenReturn(null);

        assertThat(service.tryAcquireSendSlot(EMAIL)).isTrue();
        assertThat(service.tryAcquireSendSlot(EMAIL)).isFalse();
        // Redis 异常返回 null 时按「未抢到」处理，宁可拒绝也不放行刷信箱请求
        assertThat(service.tryAcquireSendSlot(EMAIL)).isFalse();
    }

    @Test
    @DisplayName("store：写入带 TTL 的验证码并同步重置错误计数（新一轮从零计错）")
    void store_setsCodeWithTtlAndResetsAttemptCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.store(EMAIL, "123456");

        // 先删旧计数再写新码，顺序保证并发场景不出现「新码被旧计数误杀」
        verify(redisTemplate).delete(AuthCacheKeys.REGISTER_ATTEMPT_PREFIX + EMAIL);
        verify(valueOps).set(eq(AuthCacheKeys.REGISTER_CODE_PREFIX + EMAIL), eq("123456"), eq(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("evict：同时清除验证码与错误计数两键（邮件失败兜底用）")
    void evict_removesBothKeysTogether() {
        List<String> expectedKeys =
                List.of(AuthCacheKeys.REGISTER_CODE_PREFIX + EMAIL, AuthCacheKeys.REGISTER_ATTEMPT_PREFIX + EMAIL);

        service.evict(EMAIL);

        verify(redisTemplate).delete(expectedKeys);
    }

    @Test
    @DisplayName("verifyAndConsume：Lua 四个返回串逐一到枚举映射，含脚本异常的过期兜底")
    void verifyAndConsume_mapsLuaResultsToStatuses() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any()))
                .thenReturn("VERIFIED", "MISMATCH", "LOCKED", "EXPIRED", (String) null);

        assertThat(service.verifyAndConsume(EMAIL, "123456")).isEqualTo(CodeVerifyStatus.VERIFIED);
        assertThat(service.verifyAndConsume(EMAIL, "123456")).isEqualTo(CodeVerifyStatus.MISMATCH);
        assertThat(service.verifyAndConsume(EMAIL, "123456")).isEqualTo(CodeVerifyStatus.LOCKED);
        assertThat(service.verifyAndConsume(EMAIL, "123456")).isEqualTo(CodeVerifyStatus.EXPIRED);
        // 脚本异常返回 null：按过期处理不放行未知态
        assertThat(service.verifyAndConsume(EMAIL, "123456")).isEqualTo(CodeVerifyStatus.EXPIRED);

        // 原子脚本调用参数契约：验证码 + 最大次数 + 计数器 TTL（秒），防止脚本与应用阈值漂移（共 5 次调用同参）
        verify(redisTemplate, org.mockito.Mockito.times(5)).execute(any(), anyList(), eq("123456"), eq("5"), eq("900"));
    }

    @Test
    @DisplayName("evict 之外的方法不再触碰删除语义（store 删的是计数键而非验证码键）")
    void store_neverDeletesTheFreshCodeKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.store(EMAIL, "654321");

        verify(redisTemplate, never()).delete(AuthCacheKeys.REGISTER_CODE_PREFIX + EMAIL);
    }
}
