package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.auth.RegisterMailSender;
import com.commerce.rag.cache.RegisterCodeCacheService;
import com.commerce.rag.cache.RegisterCodeCacheService.CodeVerifyStatus;
import com.commerce.rag.dto.RegisterRequest;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.RegisterProperties;
import com.commerce.rag.record.RegisterResult;
import com.commerce.rag.service.impl.RegisterServiceImpl;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 学员注册服务单元测试 —— 覆盖发码四层闸门与注册事务全部分支（核心安全路径 100% 覆盖）
 *
 * <p>策略：Redis 原子行为/SMTP 细节已由各自单测与集成测试覆盖，此处专注业务编排分支的
 * 输入输出与调用顺序契约。</p>
 */
@ExtendWith(MockitoExtension.class)
class RegisterServiceImplTest {

    private final RegisterProperties properties =
            new RegisterProperties(Duration.ofMinutes(15), Duration.ofSeconds(60), 5, 10, "问渠学堂", "主题", "");

    @Mock
    private ISysUserService sysUserService;

    @Mock
    private RegisterCodeCacheService codeCache;

    @Mock
    private RegisterMailSender mailSender;

    @Mock
    private PasswordEncoder passwordEncoder;

    private RegisterServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RegisterServiceImpl(sysUserService, codeCache, mailSender, passwordEncoder, properties);
    }

    // ==================== sendRegisterCode ====================

    @Test
    @DisplayName("发码成功：先查重→抢锁→存码→发信全链有序；邮箱小写归一化贯穿")
    void sendRegisterCode_happyPathStoresAndSendsNormalizedEmail() {
        when(codeCache.tryAcquireIpQuota("203.0.113.7")).thenReturn(true);
        when(sysUserService.existsByEmail("zhang.san@example.com")).thenReturn(false);
        when(codeCache.tryAcquireSendSlot("zhang.san@example.com")).thenReturn(true);

        service.sendRegisterCode(" ZHANG.San@Example.COM ", "203.0.113.7");

        // 存储与发信使用的验证码一致且为 6 位数字
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(codeCache).store(org.mockito.ArgumentMatchers.eq("zhang.san@example.com"), codeCaptor.capture());
        verify(mailSender)
                .sendRegisterCode(org.mockito.ArgumentMatchers.eq("zhang.san@example.com"), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches(Pattern.compile("^\\d{6}$"));
    }

    @Test
    @DisplayName("发码被拒：邮箱已注册 → 409，且不触碰频控锁与发件（最廉价拦截在前）")
    void sendRegisterCode_rejectsRegisteredEmailAs409() {
        when(codeCache.tryAcquireIpQuota("1.1.1.1")).thenReturn(true);
        when(sysUserService.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.sendRegisterCode("taken@example.com", "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("已注册");

        verify(codeCache, never()).tryAcquireSendSlot(anyString());
        verify(mailSender, never()).sendRegisterCode(anyString(), anyString());
    }

    @Test
    @DisplayName("发码被拒：重发间隔窗口内 → 409 且提示秒数来自配置")
    void sendRegisterCode_enforcesResendIntervalAs409() {
        when(codeCache.tryAcquireIpQuota("1.1.1.1")).thenReturn(true);
        when(sysUserService.existsByEmail("a@example.com")).thenReturn(false);
        when(codeCache.tryAcquireSendSlot("a@example.com")).thenReturn(false);

        assertThatThrownBy(() -> service.sendRegisterCode("a@example.com", "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("60");

        verify(mailSender, never()).sendRegisterCode(anyString(), anyString());
    }

    @Test
    @DisplayName("发码被拒：IP 分钟窗配额耗尽 → 409，且不触碰邮箱细粒度闸门")
    void sendRegisterCode_rejectsWhenIpQuotaExhausted() {
        when(codeCache.tryAcquireIpQuota("9.9.9.9")).thenReturn(false);

        assertThatThrownBy(() -> service.sendRegisterCode("c@example.com", "9.9.9.9"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("上限");

        verify(sysUserService, never()).existsByEmail(anyString());
        verify(mailSender, never()).sendRegisterCode(anyString(), anyString());
    }

    @Test
    @DisplayName("发码失败兜底：SMTP 异常时清除已存验证码并透传 503")
    void sendRegisterCode_evictsStoredCodeWhenSmtpFails() {
        when(codeCache.tryAcquireIpQuota("8.8.8.8")).thenReturn(true);
        when(sysUserService.existsByEmail("b@example.com")).thenReturn(false);
        when(codeCache.tryAcquireSendSlot("b@example.com")).thenReturn(true);
        BizException smtpFailure = new BizException(ErrorCode.SERVICE_UNAVAILABLE, "验证码邮件发送失败，请稍后重试");
        org.mockito.Mockito.doThrow(smtpFailure).when(mailSender).sendRegisterCode(anyString(), anyString());

        assertThatThrownBy(() -> service.sendRegisterCode("b@example.com", "8.8.8.8"))
                .isSameAs(smtpFailure);

        verify(codeCache).evict("b@example.com");
    }

    // ==================== register ====================

    @Test
    @DisplayName("注册成功：消费验证码→派生唯一用户名→STUDENT 落库→返回签发信息集")
    void register_createsStudentAccountAndReturnsSessionSeed() {
        prepareVerifiedCode("zhang.san@example.com");
        when(sysUserService.existsByUsername(argThat(u -> u != null && u.startsWith("zhangsan_"))))
                .thenReturn(false);
        when(passwordEncoder.encode("Passw0rd!123")).thenReturn("{bcrypt}hash");

        RegisterResult result = service.register(request("Zhang.San@example.com", "654321", "Passw0rd!123", ""));

        assertThat(result.username()).matches("^zhangsan_\\d{4}$");
        assertThat(result.displayName()).isEqualTo("zhangsan"); // 昵称为空回退邮箱前缀
        assertThat(result.role()).isEqualTo("STUDENT");

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserService).save(userCaptor.capture());
        SysUser saved = userCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("zhang.san@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("{bcrypt}hash");
        assertThat(saved.getRole()).isEqualTo("STUDENT");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getUsername()).isEqualTo(result.username());
    }

    @Test
    @DisplayName("注册成功：显式昵称优先生效且去首尾空白")
    void register_prefersExplicitNicknameWhenProvided() {
        prepareVerifiedCode("li4@example.com");
        when(sysUserService.existsByUsername(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}h2");

        RegisterResult result = service.register(request("li4@example.com", "112233", "Password-88", "  李四  "));

        assertThat(result.displayName()).isEqualTo("李四");
    }

    @Test
    @DisplayName("注册失败：验证码过期 → 400 且绝不落库")
    void register_rejectsExpiredCodeWith400() {
        when(codeCache.verifyAndConsume("old@example.com", "111111")).thenReturn(CodeVerifyStatus.EXPIRED);

        assertBizOnRegister(request("old@example.com", "111111", "Password-88", null), ErrorCode.BAD_REQUEST);
        verify(sysUserService, never()).save(any(SysUser.class));
    }

    @Test
    @DisplayName("注册失败：验证码错误 → 400；锁定态给出重新获取指引")
    void register_distinguishesMismatchAndLockedStatuses() {
        when(codeCache.verifyAndConsume("m1@example.com", "222222")).thenReturn(CodeVerifyStatus.MISMATCH);
        when(codeCache.verifyAndConsume("m2@example.com", "333333")).thenReturn(CodeVerifyStatus.LOCKED);

        assertThatThrownBy(() -> service.register(request("m1@example.com", "222222", "Password-88", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码错误");
        assertThatThrownBy(() -> service.register(request("m2@example.com", "333333", "Password-88", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尝试次数过多");

        verify(sysUserService, never()).save(any(SysUser.class));
    }

    @Test
    @DisplayName("注册失败：验证码消费后并发同邮箱已完成注册 → 409（首道防御）")
    void register_rejectsConcurrentDuplicateEmailAfterVerification() {
        prepareVerifiedCode("race@example.com");
        when(sysUserService.existsByEmail("race@example.com")).thenReturn(true);

        assertBizOnRegister(request("race@example.com", "444444", "Password-88", null), ErrorCode.CONFLICT);
        verify(sysUserService, never()).save(any(SysUser.class));
    }

    @Test
    @DisplayName("注册失败：落库撞唯一索引（并发抢注）→ DuplicateKeyException 转 409 友好提示")
    void register_translatesUniqueIndexRaceInto409() {
        prepareVerifiedCode("dup@example.com");
        when(sysUserService.existsByUsername(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}h3");
        when(sysUserService.save(any(SysUser.class))).thenThrow(new DuplicateKeyException("uniq"));

        assertBizOnRegister(request("dup@example.com", "555555", "Password-88", null), ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("用户名派生防碰撞：随机候选连续命中占用后仍能产出可用用户名")
    void register_recoversFromUsernameCollisions() {
        prepareVerifiedCode("wang.wu@example.com");
        when(sysUserService.existsByUsername(anyString())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}h4");
        // 落库行为与本用例无关（返回 boolean 占位即可），断言聚焦用户名兜底派生
        when(sysUserService.save(any(SysUser.class))).thenReturn(true);

        RegisterResult result = service.register(request("Wang.Wu@example.com", "666666", "Password-88", null));

        // 全部候选被占时走毫秒时间戳兜底（base_<timestamp>），保证始终可开户
        assertThat(result.username()).startsWith("wangwu_").matches("^wangwu_\\d+$");
    }

    // ==================== 私有构造辅助 ====================

    /** 预置「验证码校验通过」的通用桩：查重未命中 + 原子消费成功（匹配器须全参一致使用） */
    private void prepareVerifiedCode(String email) {
        when(sysUserService.existsByEmail(eq(email))).thenReturn(false);
        when(codeCache.verifyAndConsume(eq(email), anyString())).thenReturn(CodeVerifyStatus.VERIFIED);
    }

    private RegisterRequest request(String email, String code, String password, String nickname) {
        return new RegisterRequest(email, code, password, nickname);
    }

    /** 断言注册抛出指定错误码的 BizException */
    private void assertBizOnRegister(RegisterRequest req, ErrorCode expected) {
        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(expected));
    }
}
