/**
 * 站点运营配置 —— 客服联系邮箱唯一事实源（审查 M3 收敛：品牌署名地址只在此声明）
 *
 * 可经 NEXT_PUBLIC_SUPPORT_EMAIL 构建期覆盖（非敏感值，允许内联进客户端 bundle）；
 * 忘记密码等入口统一引用本常量，避免同一地址三处散落。SMTP 发件通道凭据与本值解耦
 * （发件走服务端 MAIL_USERNAME env），本邮箱仅作对外展示的联系身份。
 *
 * TODO(password-self-service): 找回密码迁移为服务端自助流程（邮件重置链接），计划于注册功能上线后的下一迭代引入。
 */

/** 客服/找回密码联系邮箱 */
export const SUPPORT_EMAIL = process.env.NEXT_PUBLIC_SUPPORT_EMAIL || "18229923842@163.com";

/** 预拼 mailto 链接（含默认主题的请优先单独构造，避免主题污染通用场景） */
export const SUPPORT_MAILTO = `mailto:${SUPPORT_EMAIL}`;
