import { z } from "zod";

/**
 * 认证表单 zod schema —— 登录页双面板输入边界校验（宪法 C.1.8：
 * 不可信输入在边界处 parse 校验，校验逻辑不散落组件内部）
 *
 * 口径与后端 DTO 对齐：登录用户名非空、密码 ≥6；
 * 注册邮箱格式（trim 后校验）、验证码固定 6 位数字、密码 8–64 位、昵称 ≤50。
 * 注：zod v4 顶层格式校验先于管道，故邮箱采用「trim/小写 → refine 正则」确定性顺序。
 */

/** 邮箱正则（HTML5 同源宽松口径：本地段@域名段） */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** 登录表单 schema */
export const loginFormSchema = z.object({
  /** 用户名或邮箱（双轨识别，后端按是否含 @ 回退查询） */
  account: z.string().trim().min(1, "请输入用户名或邮箱"),
  /** 登录密码 */
  password: z.string().min(6, "密码至少 6 位"),
});

/** 发送验证码表单 schema（独立于完整注册：点击发码时仅校验邮箱） */
export const sendCodeSchema = z.object({
  email: z
    .string()
    .trim()
    .toLowerCase()
    .max(255, "邮箱最长 255 字符")
    .refine((value) => EMAIL_PATTERN.test(value), { message: "请输入有效的邮箱地址" }),
});

/** 注册表单 schema */
export const registerFormSchema = z.object({
  /** 昵称（可选；空串归一化为 undefined，服务端回退邮箱前缀作为显示名） */
  nickname: z
    .string()
    .trim()
    .max(50, "昵称最长 50 字符")
    .transform((value) => (value === "" ? undefined : value))
    .optional(),
  /** 注册邮箱（发送侧归一化为小写） */
  email: z
    .string()
    .trim()
    .toLowerCase()
    .max(255, "邮箱最长 255 字符")
    .refine((value) => EMAIL_PATTERN.test(value), { message: "请输入有效的邮箱地址" }),
  /** 邮箱验证码（6 位数字，从邮件转抄） */
  code: z.string().regex(/^\d{6}$/, "验证码为 6 位数字"),
  /** 登录密码（8–64 位，复杂度提示交由强度计承担） */
  password: z.string().min(8, "密码至少 8 位").max(64, "密码最长 64 位"),
  /** 条款勾选（必须为 true 才能提交） */
  terms: z.boolean().refine((value) => value === true, {
    message: "请先阅读并同意《服务条款》与《隐私政策》",
  }),
});

/** 登录表单值类型（schema 同源推导） */
export type LoginFormValues = z.infer<typeof loginFormSchema>;

/** 注册表单值类型 */
export type RegisterFormValues = z.infer<typeof registerFormSchema>;
