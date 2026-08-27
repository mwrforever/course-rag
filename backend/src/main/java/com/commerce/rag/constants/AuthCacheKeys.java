package com.commerce.rag.constants;

/**
 * 认证域 Redis 缓存键常量 —— 注册验证码链路的键名统一出口（宪法 A.2.1 业务常量集中）
 *
 * <p>键命名遵循「业务:实体:id」三段式（A.5.5）；本组键由清理任务与
 * 集成测试按 {@code auth:*} 前缀批量清理，勿改前缀归属层级。</p>
 *
 * @author commerce-rag
 */
public interface AuthCacheKeys {

    /** 注册验证码键前缀（后接邮箱；值 = 6 位数字验证码，TTL 由 register.code-ttl 配置） */
    String REGISTER_CODE_PREFIX = "auth:reg:code:";

    /** 注册验证码错误尝试计数键前缀（后接邮箱；值 = 连续错误次数，防爆破） */
    String REGISTER_ATTEMPT_PREFIX = "auth:reg:att:";

    /** 发送间隔锁键前缀（后接邮箱；值 = 占位符，TTL = register.resend-interval，SET NX 原子抢占） */
    String REGISTER_SEND_LOCK_PREFIX = "auth:reg:send:";

    /** 按 IP 发码配额计数键前缀（后接客户端 IP；值 = 窗口内已发送次数，跨邮箱批量刷信防护） */
    String REGISTER_IP_QUOTA_PREFIX = "auth:reg:ipq:";
}
