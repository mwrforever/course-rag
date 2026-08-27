package com.commerce.rag.auth;

import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.RegisterProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 注册验证码邮件发送器 —— JavaMailSender 封装 + 品牌 HTML 模板构建（认证域基础设施）
 *
 * <p>SMTP 凭证一律环境变量注入（MAIL_USERNAME / MAIL_PASSWORD，宪法 A.2.3），本类零明文；
 * 全部失败路径（未配置 / 组装异常 / SMTP 异常）统一转为 {@code SERVICE_UNAVAILABLE},
 * 由上层负责清除已占用的验证码避免「有码无信」死状态。</p>
 *
 * <p>线程安全：无共享可变状态；JavaMailSender 为进程级单例 Bean（A.5.1）。</p>
 *
 * @author commerce-rag
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegisterMailSender {

    private final JavaMailSender javaMailSender;
    private final RegisterProperties properties;

    /**
     * 发送注册验证码邮件（HTML 格式，UTF-8）
     *
     * @param toEmail 收件邮箱（调用方已保证格式合法且小写归一化）
     * @param code    6 位数字验证码明文（仅写入邮件正文与 Redis，不入日志全量）
     * @throws BizException 三种情形均抛 503：SMTP 未配置凭据（fail-fast）/ MIME 组装失败 / SMTP 发送失败
     */
    public void sendRegisterCode(String toEmail, String code) {
        // 第三方接口调用信息级日志：入口打点（响应摘要由成功/异常分支补充）
        log.info("准备发送注册验证码邮件: to={}", maskEmail(toEmail));

        // 发件地址来自 spring.mail.username（env MAIL_USERNAME）；缺失即配置不完整，快速失败并给出可行动提示
        String fromAddress = resolveFromAddress();

        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            // multipart=true 支持纯文本回退内容；UTF-8 保证中文署名/正文不过度编码
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, properties.fromName());
            helper.setTo(toEmail);
            helper.setSubject(properties.subject());
            helper.setText(buildPlainTextFallback(code), buildVerificationHtml(code));
            javaMailSender.send(message);
            // 日志脱敏：邮箱打码、验证码仅露尾位（供邮件到达性对账，不泄露全量可重放的码）
            log.info("注册验证码邮件已发送: to={}, codeTail=*{}", maskEmail(toEmail), code.charAt(code.length() - 1));
        } catch (MessagingException | MailException | java.io.UnsupportedEncodingException e) {
            log.error("注册验证码邮件发送失败: to={}", maskEmail(toEmail), e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "验证码邮件发送失败，请稍后重试", e);
        }
    }

    /**
     * 解析发件地址：优先取 register.from-email 配置（env MAIL_USERNAME 同源），
     * 为空时回退 JavaMailSenderImpl 内的 username；两者皆缺失视为 SMTP 未配置。
     *
     * @return 发件邮箱地址
     * @throws BizException 发件地址缺失（fail-fast，提示运维补齐环境变量）
     */
    private String resolveFromAddress() {
        if (isNotBlank(properties.fromEmail())) {
            return properties.fromEmail();
        }
        if (javaMailSender instanceof JavaMailSenderImpl impl && isNotBlank(impl.getUsername())) {
            return impl.getUsername();
        }
        log.error("SMTP 未配置（缺少 MAIL_USERNAME/MAIL_PASSWORD 环境变量），无法发送验证码邮件");
        throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "邮件服务未配置，请联系管理员");
    }

    /**
     * 构建品牌化验证码 HTML 正文（视觉规范与 C 端一致：奶油底 / 墨色码块 / 棕色点缀）
     *
     * @param code 6 位数字验证码明文
     * @return 完整 HTML 文档字符串
     */
    String buildVerificationHtml(String code) {
        long validMinutes = Math.max(1, properties.codeTtl().toMinutes());
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <body style="margin:0;padding:0;background:#F6F1E7;font-family:'PingFang SC','Microsoft YaHei','Hiragino Sans GB',sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background:#F6F1E7;padding:36px 12px;">
                    <tr><td align="center">
                      <table role="presentation" width="520" cellpadding="0" cellspacing="0" border="0" style="max-width:520px;width:100%%;background:#FFFDF8;border:1px solid rgba(25,21,18,.14);border-radius:18px;padding:42px 46px 34px;color:#191512;">
                        <tr><td style="font-size:22px;font-weight:600;letter-spacing:.24em;">问渠学堂</td></tr>
                        <tr><td style="padding-top:10px;color:#7A4A2B;font-size:13px;">为有源头活水来</td></tr>
                        <tr><td style="padding-top:28px;font-size:15px;line-height:1.9;">您好！您正在注册<b>问渠学堂</b>账号，请使用以下验证码完成邮箱确认：</td></tr>
                        <tr><td align="center" style="padding:28px 0;">
                          <div style="background:#191512;color:#F6F1E7;border-radius:12px;padding:20px 36px;font-size:34px;font-weight:700;letter-spacing:.5em;text-indent:.5em;">%s</div>
                        </td></tr>
                        <tr><td style="font-size:13px;line-height:2;color:#6B6257;">· 验证码 <b>%d 分钟内有效</b>，超时请重新获取<br>· 请勿向任何人透露该验证码（包括自称官方人员者）<br>· 若这不是您本人的操作，请忽略本邮件</td></tr>
                        <tr><td style="padding-top:24px;border-top:1px solid rgba(25,21,18,.12);font-size:11px;line-height:1.8;color:#B3B1AC;">问渠学堂 · AI 课程学习助手<br>本邮件由系统自动发送，请勿直接回复</td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """
                .formatted(code, validMinutes);
    }

    /** 不支持 HTML 渲染的客户端回退纯文本（明文携带验证码，保证无 CSS 客户端同样可用） */
    private String buildPlainTextFallback(String code) {
        return "【问渠学堂】您的注册验证码为：%s（%d 分钟内有效）。请勿向任何人透露。"
                .formatted(code, Math.max(1, properties.codeTtl().toMinutes()));
    }

    /** 邮箱日志脱敏：本地部分仅保留首字符 + 星号（如 a***@163.com）；非标输入原样置为 masked 占位 */
    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return "(empty)";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
