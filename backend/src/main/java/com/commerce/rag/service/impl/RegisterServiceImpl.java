package com.commerce.rag.service.impl;

import com.commerce.rag.auth.RegisterMailSender;
import com.commerce.rag.cache.RegisterCodeCacheService;
import com.commerce.rag.dto.RegisterRequest;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.enums.UserRole;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.RegisterProperties;
import com.commerce.rag.record.RegisterResult;
import com.commerce.rag.service.IRegisterService;
import com.commerce.rag.service.ISysUserService;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学员注册服务实现 —— 邮箱验证码发送与自注册开户
 *
 * <p>依赖：ISysUserService（账户落库与查重，跨 service 仅经对方接口链式能力 B.2.4）、
 * RegisterCodeCacheService（验证码 Redis 原子读写）、RegisterMailSender（SMTP 出站）、
 * PasswordEncoder（BCrypt）。自注册角色锁定 STUDENT，杜绝自我提权。</p>
 *
 * <p>并发说明：查重-插入竞态由 DB 部分唯一索引兜底转 409；验证码消费经 Lua 一次完成无 TOCTOU。
 * 本类无可变共享状态（SecureRandom 实例自身线程安全），@Transactional 仅覆盖用户落库段。</p>
 *
 * @author commerce-rag
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterServiceImpl implements IRegisterService {

    private final ISysUserService sysUserService;
    private final RegisterCodeCacheService registerCodeCacheService;
    private final RegisterMailSender registerMailSender;
    private final PasswordEncoder passwordEncoder;
    private final RegisterProperties properties;

    /** 安全随机源：验证码与用户名随机后缀共用（独立实例，避免与全局工具共享竞争） */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 发送注册验证码邮件（HTML）
     *
     * <p>执行顺序：判重（最廉价拦截）→ 抢发送间隔锁 → 生成并存储验证码 → SMTP 外呼；
     * SMTP 失败必须回滚已存验证码（evict），避免出现永远无人能获知的死验证码。</p>
     *
     * @param rawEmail 用户提交的邮箱原文（内部小写归一化后参与全部键名与查重）
     */
    @Override
    public void sendRegisterCode(String rawEmail, String clientIp) {
        String email = normalizeEmail(rawEmail);

        // 0. 跨邮箱批量刷信防护（审查 M2）：先做按 IP 分钟窗配额，再进按邮箱细粒度闸门——
        //    攻击者换邮箱打点也无法绕过本层（先挡人群、再挡个体，从廉价到昂贵排序）
        if (!registerCodeCacheService.tryAcquireIpQuota(clientIp)) {
            throw new BizException(ErrorCode.CONFLICT, "发送次数已达上限，请稍后再试");
        }

        // 1. 已注册拦截（含 DISABLED——禁用账户同样占用邮箱唯一索引，不允许重复建号）
        if (sysUserService.existsByEmail(email)) {
            throw new BizException(ErrorCode.CONFLICT, "该邮箱已注册，请直接登录");
        }

        // 2. 发送频控锁（SET NX EX 原子抢占；窗口内重复请求在此层直接拒绝）
        if (!registerCodeCacheService.tryAcquireSendSlot(email)) {
            throw new BizException(
                    ErrorCode.CONFLICT,
                    "验证码发送过于频繁，请 %d 秒后再试".formatted(properties.resendInterval().toSeconds()));
        }

        // 3. 生成 6 位数字码（%06d 保零填充防降位）并入 Redis，TTL 由配置控制（默认 15 分钟）
        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        registerCodeCacheService.store(email, code);

        // 4. 发送 HTML 邮件；失败清码兜底后原样抛出（503 语义由 MailSender 统一转换）
        try {
            registerMailSender.sendRegisterCode(email, code);
        } catch (BizException e) {
            registerCodeCacheService.evict(email);
            throw e;
        }

        log.info(
                "注册验证码下发成功: email={}, ttlSeconds={}s",
                RegisterMailSender.maskEmail(email),
                properties.codeTtl().toSeconds());
    }

    /**
     * 校验验证码并完成学生账号注册（事务段：仅用户落库）
     *
     * <p>注意：验证码消费在事务前完成且不可逆（一次性），若后续落库失败用户需重新获取验证码——
     * 这是刻意的安全取舍（拒绝「失败后复用同一验证码」带来的重放窗口）。</p>
     *
     * @param request 注册请求（DTO 已校验格式；nickname 可空则回退邮箱前缀）
     * @return 新账户最小视图，供 Controller 直接签发 Token
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegisterResult register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        // 1. 原子校验并消费验证码（比对+计数+作废在 Lua 内一次完成，防并发爆破）
        RegisterCodeCacheService.CodeVerifyStatus status =
                registerCodeCacheService.verifyAndConsume(email, request.code().trim());
        switch (status) {
            case VERIFIED -> log.info("注册验证码校验通过: email={}", RegisterMailSender.maskEmail(email));
            case EXPIRED -> throw new BizException(ErrorCode.BAD_REQUEST, "验证码已过期，请重新获取");
            case MISMATCH -> throw new BizException(ErrorCode.BAD_REQUEST, "验证码错误");
            case LOCKED -> throw new BizException(ErrorCode.BAD_REQUEST, "尝试次数过多，验证码已失效，请重新获取");
        }

        // 2. 并发兜底查重（Lua 消费期间另一请求可能刚完成同邮箱注册）
        if (sysUserService.existsByEmail(email)) {
            throw new BizException(ErrorCode.CONFLICT, "该邮箱刚刚已被注册，请直接登录");
        }

        // 3. 用户名派生：邮箱前缀清洗 + 随机后缀保证唯一（不存在「以邮箱为 username」的可预测性风险面）
        String basePrefix = sanitizeUsernameBase(email);
        String username = generateUniqueUsername(basePrefix);

        // 4. 组装学生实体（ID 由雪花 ASSIGN_ID 自动填充；昵称缺省回退邮箱前缀，可读性与隐私平衡）
        String displayName = (request.nickname() == null || request.nickname().isBlank())
                ? basePrefix
                : request.nickname().trim();
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(displayName);
        user.setRole(UserRole.STUDENT.name());
        user.setStatus("ACTIVE");

        // 5. 落库：唯一索引竞态（并发同邮箱/同名）由 DuplicateKeyException 转 409 友好提示，
        //    与 create() 的 check-then-insert 兜底策略保持一致（B2-8 同款）
        try {
            sysUserService.save(user);
        } catch (DuplicateKeyException e) {
            log.warn("注册并发唯一索引冲突: email={}", RegisterMailSender.maskEmail(email));
            throw new BizException(ErrorCode.CONFLICT, "该邮箱刚刚已被注册，请直接登录", e);
        }

        log.info(
                "学员注册成功: userId={}, username={}, email={}",
                user.getId(),
                username,
                RegisterMailSender.maskEmail(email));
        return new RegisterResult(user.getId(), username, displayName, user.getRole());
    }

    /**
     * 邮箱归一化：trim + 全小写。后续查重、Redis 键、DB 列均以归一化值为准，
     * 保证 "Abc@x.com" 与 "abc@X.COM" 指向同一账号身份。
     *
     * @param rawEmail 用户输入原文
     * @return 归一化邮箱（非空调用约定由 DTO 校验前置保证）
     */
    private String normalizeEmail(String rawEmail) {
        return rawEmail == null ? "" : rawEmail.trim().toLowerCase();
    }

    /**
     * 从邮箱本地部分派生用户名基底：保留 [a-z0-9_]，其余字符剔除；截断至 30 位；
     * 清洗结果为空时回退固定前缀 student。
     *
     * @param normalizedEmail 归一化邮箱（必须含 @）
     * @return 用户名基底串
     */
    private String sanitizeUsernameBase(String normalizedEmail) {
        int at = normalizedEmail.indexOf('@');
        String local = at > 0 ? normalizedEmail.substring(0, at) : "";
        StringBuilder sb = new StringBuilder();
        for (char c : local.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
            if (sb.length() >= 30) {
                break;
            }
        }
        String base = sb.toString();
        return base.isEmpty() ? "student" : base;
    }

    /**
     * 生成未占用的唯一用户名：基底 + 4 位随机数探测最多 5 次，均命中时退化为毫秒时间戳后缀
     * （概率趋近于零；仍受 uniq_sys_user_username 最终保护，冲突路径 register 内已转 409）。
     *
     * @param base 用户名基底串
     * @return 未被占用的候选用户名
     */
    private String generateUniqueUsername(String base) {
        for (int i = 0; i < 5; i++) {
            String candidate = base + "_" + (1000 + secureRandom.nextInt(9000));
            if (!sysUserService.existsByUsername(candidate)) {
                return candidate;
            }
        }
        return base + "_" + System.currentTimeMillis();
    }
}
