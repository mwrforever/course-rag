package com.commerce.rag.service;

import com.commerce.rag.dto.RegisterRequest;
import com.commerce.rag.record.RegisterResult;

/**
 * 学员注册服务接口 —— 邮箱验证码链路与自注册开户（role 固定 STUDENT）
 *
 * <p>流程契约（C 端注册两段式）：</p>
 * <ol>
 *   <li>{@link #sendRegisterCode}: 校验邮箱未被占用 → 频控锁 → 生成 6 位码存 Redis(TTL 配置值) → 发送 HTML 邮件</li>
 *   <li>{@link #register}: 原子校验并消费验证码 → 创建学生账户 → 返回签发会话所需最小信息集</li>
 * </ol>
 *
 * <p>Token 签发/互踢/登录审计属认证编排，保留在 Controller 层与登录共用（复用 issueSession）。</p>
 *
 * @author commerce-rag
 */
public interface IRegisterService {

    /**
     * 向指定邮箱发送 6 位数字注册验证码（HTML 邮件，有效期/间隔/防爆破阈值均来自 RegisterProperties）
     *
     * <p>执行顺序刻意安排为：先判重再抢锁最后存码发信——刷接口者在最廉价的查重层即被挡下。</p>
     *
     * @param rawEmail 用户提交的邮箱原文（方法内小写归一化）
     * @param clientIp 发起请求的客户端 IP（跨邮箱批量刷信防护的限速维度，由 Controller 提取）
     * @throws com.commerce.rag.exception.BizException CONFLICT(409)：邮箱已注册 / 重发间隔未到 / IP 配额耗尽；
     *                                                 SERVICE_UNAVAILABLE(503)：SMTP 发送失败（同时清除已存验证码）
     */
    void sendRegisterCode(String rawEmail, String clientIp);

    /**
     * 校验验证码并完成学生账号注册
     *
     * <p>事务边界：用户落库整段在一个事务内（A.4.12）；验证码消费发生在事务前的 Redis 层，
     * 注册中途失败会导致验证码被消费需重新获取——属预期安全行为（宁可多输一次码，不允许验证码复用）。</p>
     *
     * @param request 注册请求（DTO 层已做格式校验；密码 BCrypt 编码于本方法内）
     * @return 新账户最小视图（userId/username/displayName/role），供 Controller 直接签发 Token
     * @throws com.commerce.rag.exception.BizException BAD_REQUEST(400)：验证码过期/错误/锁定；
     *                                                 CONFLICT(409)：邮箱或用户名并发抢注冲突
     */
    RegisterResult register(RegisterRequest request);
}
