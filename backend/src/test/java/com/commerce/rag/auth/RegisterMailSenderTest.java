package com.commerce.rag.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.RegisterProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 注册验证码邮件发送器单元测试 —— 覆盖发件人解析、HTML 正文、脱敏与三类失败转换
 *
 * <p>JavaMailSender 采用真实 {@link JavaMailSenderImpl} 的 spy：不发起真实 SMTP 连接，
 * 仅拦截 createMimeMessage/send 两个出口点，保证 MIME 组装链路全程真实执行。</p>
 */
class RegisterMailSenderTest {

    private static final String FROM_ADDRESS = "noreply-wenqu@test.example";
    private static final String TO_EMAIL = "zhang.san@example.com";

    /** 与生产一致的默认配置（15 分钟有效 → HTML 内应出现“15 分钟”） */
    private final RegisterProperties properties =
            new RegisterProperties(Duration.ofMinutes(15), Duration.ofSeconds(60), 5, "问渠学堂", "【问渠学堂】注册验证码");

    private JavaMailSenderImpl sender;

    @BeforeEach
    void setUp() {
        sender = new JavaMailSenderImpl();
        sender.setUsername(FROM_ADDRESS);
    }

    @Test
    @DisplayName("发送成功：From/To/Subject 与 HTML 正文均正确装配，正文包含六位验证码")
    void sendRegisterCode_assemblesBrandHtmlMailCorrectly() throws Exception {
        JavaMailSenderImpl spySender = Mockito.spy(sender);
        // 不覆写 createMimeMessage：真实 Session 提供完整 MIME Provider 体系（组装链路全程真实）
        Mockito.doNothing().when(spySender).send(any(MimeMessage.class));

        RegisterMailSender mailSender = new RegisterMailSender(spySender, properties);
        mailSender.sendRegisterCode(TO_EMAIL, "482913");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        Mockito.verify(spySender).send(captor.capture());
        MimeMessage realMessage = captor.getValue();

        // 复核真实 MIME 对象上的关键头与正文（multipart 结构中同时存在纯文本回退与 HTML）
        assertThat(InternetAddress.toString(realMessage.getFrom())).contains(FROM_ADDRESS);
        assertThat(InternetAddress.toString(realMessage.getAllRecipients())).isEqualTo(TO_EMAIL);
        assertThat(realMessage.getSubject()).isEqualTo("【问渠学堂】注册验证码");
        String content = dumpContent(realMessage);
        assertThat(content).contains("482913").contains("问渠学堂").contains("15 分钟内有效");
    }

    @Test
    @DisplayName("SMTP 未配置凭据（username 为空）：fail-fast 抛 503 且不触碰 send")
    void sendRegisterCode_failsFastWhenSmtpNotConfigured() {
        sender.setUsername("");
        JavaMailSenderImpl spySender = Mockito.spy(sender);
        RegisterMailSender mailSender = new RegisterMailSender(spySender, properties);

        assertThatThrownBy(() -> mailSender.sendRegisterCode(TO_EMAIL, "111111"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessageContaining("邮件服务未配置");

        Mockito.verify(spySender, Mockito.never()).send(Mockito.any(MimeMessage.class));
    }

    @Test
    @DisplayName("SMTP 发送异常：转 503 BizException（调用方据此清除已存验证码）")
    void sendRegisterCode_translatesSmtpFailureInto503() {
        JavaMailSenderImpl spySender = Mockito.spy(sender);
        Mockito.doReturn(new MimeMessage(Session.getInstance(new Properties())))
                .when(spySender)
                .createMimeMessage();
        doThrow(new MailSendException("smtp down")).when(spySender).send(any(MimeMessage.class));

        RegisterMailSender mailSender = new RegisterMailSender(spySender, properties);
        assertThatThrownBy(() -> mailSender.sendRegisterCode(TO_EMAIL, "222333"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("maskEmail：本地部分仅留首字符加星号；空值与非标输入安全降级")
    void maskEmail_keepsOnlyFirstLocalChar() {
        assertThat(RegisterMailSender.maskEmail("abcdefg@163.com")).isEqualTo("a***@163.com");
        assertThat(RegisterMailSender.maskEmail("@broken")).isEqualTo("***");
        assertThat(RegisterMailSender.maskEmail(null)).isEqualTo("(empty)");
        assertThat(RegisterMailSender.maskEmail("")).isEqualTo("(empty)");
    }

    /** 递归展开多部分邮件正文为字符串（MimeMultipart/PART 兼容处理） */
    private String dumpContent(jakarta.mail.Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof jakarta.mail.Multipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                sb.append(dumpContent(mp.getBodyPart(i)));
            }
            return sb.toString();
        }
        return "";
    }
}
