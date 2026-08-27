package com.commerce.rag.cache;

import com.commerce.rag.constants.AuthCacheKeys;
import com.commerce.rag.properties.RegisterProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 注册验证码缓存服务 —— 验证码存储 / 错误尝试计数 / 发送间隔锁的领域读写出口
 *
 * <p>分层依据（宪法 A.5.4 复杂场景）：「校验 + 消费」是多步骤原子语义（比对 → 错误计数 → 达上限作废 → 正确双键删除），
 * 经 Lua 脚本一次完成防并发绕过；发送间隔锁为 SET NX EX 单命令天然原子。
 * 键名统一出自 {@link AuthCacheKeys}，键值均为短字符串（Token 域键空间按 auth:* 前缀整体清理）。</p>
 *
 * <p>线程安全：无共享可变状态，全部依赖线程安全的 StringRedisTemplate（A.5.2）。</p>
 *
 * @author commerce-rag
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterCodeCacheService {

    /** 校验消费 Lua 脚本（单例复用走 EVALSHA，A.5.6） */
    private static final DefaultRedisScript<String> VERIFY_SCRIPT = buildVerifyScript();

    private final StringRedisTemplate redisTemplate;
    private final RegisterProperties properties;

    /** 构建一次性加载的校验脚本定义（结果类型 String，对应脚本内四个返回串） */
    private static DefaultRedisScript<String> buildVerifyScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/register_code_verify.lua"));
        script.setResultType(String.class);
        return script;
    }

    /**
     * 尝试抢占「同一邮箱」的发送间隔锁
     *
     * <p>SET NX EX 原子抢占：首个请求写入成功获得发送权，窗口期内的后续请求自然失败。</p>
     *
     * @param email 归一化后的邮箱
     * @return true = 允许本次发送；false = 处于重发间隔窗口内，应拒绝
     */
    public boolean tryAcquireSendSlot(String email) {
        Boolean acquired = redisTemplate
                .opsForValue()
                .setIfAbsent(AuthCacheKeys.REGISTER_SEND_LOCK_PREFIX + email, "1", properties.resendInterval());
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 存储新验证码（TTL = register.code-ttl 配置值）
     *
     * <p>语义约定：存新码同时重置该邮箱的错误尝试计数——新一轮验证从零计错，
     * 避免「上一轮试错数」误杀本轮全新验证码。</p>
     *
     * @param email 归一化后的邮箱
     * @param code  6 位数字验证码明文（仅入 Redis 与邮件正文，禁止写日志全量）
     */
    public void store(String email, String code) {
        redisTemplate.delete(AuthCacheKeys.REGISTER_ATTEMPT_PREFIX + email);
        redisTemplate.opsForValue().set(AuthCacheKeys.REGISTER_CODE_PREFIX + email, code, properties.codeTtl());
    }

    /** 配额窗口长度（秒）：每击刷新 ⇒ 语义为无静默期的滑动窗计数器 */
    private static final long IP_QUOTA_WINDOW_SECONDS = 60;

    /**
     * 尝试为该客户端 IP 扣减发码配额（跨邮箱批量刷信防护——审查 M2 加固，F1/F2 复核修订）
     *
     * <p>实现：INCR 计数 + 每次调用刷新 TTL 的滑动窗计数器。每击刷 TTL 而非「首击才设过期」，
     * 彻底消除 INCR 成功而 EXPIRE 前崩溃导致的永生键自伤；Redis 访问异常一律 fail-open
     * （额度不计入或过期续写失败均放行），避免基础设施抖动阻断正常注册或制造误封。</p>
     *
     * @param clientIp 客户端 TCP 对端地址（Controller 固定传 remoteAddr 这类攻击者不可伪造的来源，
     *                 可空/未知回退字面量 unknown 以保留全局限速兜底）
     * @return true = 允许本次发送；false = 当前滑动窗内配额耗尽，应拒绝
     */
    public boolean tryAcquireIpQuota(String clientIp) {
        String key = AuthCacheKeys.REGISTER_IP_QUOTA_PREFIX
                + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
            if (count != null) {
                redisTemplate.expire(key, java.time.Duration.ofSeconds(IP_QUOTA_WINDOW_SECONDS));
            }
        } catch (DataAccessException e) {
            log.warn("IP 发码配额 Redis 访问失败，fail-open 放行: {}", e.getMessage());
            return true;
        }
        return count == null || count <= properties.maxSendPerIpPerMinute();
    }

    /**
     * 清除某邮箱的全部注册相关键（验证码 + 尝试计数）
     *
     * <p>当前仅用于邮件发送失败的兜底清码：避免产生「Redis 里始终存在但无人能获知」的死验证码。</p>
     *
     * @param email 归一化后的邮箱
     */
    public void evict(String email) {
        redisTemplate.delete(
                List.of(AuthCacheKeys.REGISTER_CODE_PREFIX + email, AuthCacheKeys.REGISTER_ATTEMPT_PREFIX + email));
    }

    /** 验证结果枚举 —— 与 lua/register_code_verify.lua 的四个返回串一一对应 */
    public enum CodeVerifyStatus {
        /** 校验通过且验证码已一次性消费 */
        VERIFIED,
        /** 验证码不存在或已过期 */
        EXPIRED,
        /** 验证码错误（已累计一次失败计数） */
        MISMATCH,
        /** 连续错误达到上限，验证码已作废 */
        LOCKED
    }

    /**
     * 原子校验并消费验证码（比对/计数/删除在 Lua 内一步完成，不暴露读-改-写窗口）
     *
     * @param email         归一化后的邮箱
     * @param submittedCode 用户提交的验证码（调用方应已 trim；长度/字符集由 DTO 校验兜底）
     * @return 校验结果四态；脚本异常返回视为 EXPIRED（宁可让用户重新获取，不放行未知态）
     */
    public CodeVerifyStatus verifyAndConsume(String email, String submittedCode) {
        String result = redisTemplate.execute(
                VERIFY_SCRIPT,
                List.of(AuthCacheKeys.REGISTER_CODE_PREFIX + email, AuthCacheKeys.REGISTER_ATTEMPT_PREFIX + email),
                submittedCode,
                String.valueOf(properties.maxVerifyAttempts()),
                // 计数器窗口与验证码 TTL 同步：验证码过期时残留计数随之消失
                String.valueOf(Math.max(1, properties.codeTtl().toSeconds())));
        if (result == null) {
            return CodeVerifyStatus.EXPIRED;
        }
        return switch (result) {
            case "VERIFIED" -> CodeVerifyStatus.VERIFIED;
            case "MISMATCH" -> CodeVerifyStatus.MISMATCH;
            case "LOCKED" -> CodeVerifyStatus.LOCKED;
            default -> CodeVerifyStatus.EXPIRED;
        };
    }
}
