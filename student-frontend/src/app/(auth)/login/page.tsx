"use client";

import { Eye, EyeSlash, Sparkle } from "@phosphor-icons/react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { ApiError, NetworkError } from "@/lib/api";
import { z } from "zod";

/**
 * 登录表单校验（设计 §1.5.7：username 非空 + 密码 ≥6 位；zod 前置校验优先于后端 400）
 */
const loginSchema = z.object({
  username: z.string().trim().min(1, "请输入用户名"),
  password: z.string().min(6, "密码至少 6 位"),
});

/** 失败原因 → Alert 文案：按 code 分级（设计 §3.2），不透出后端原始 message */
function toAlertMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 401) {
      return "用户名或密码错误";
    }
    if (error.code === 403) {
      return "账号已被禁用";
    }
  }
  if (error instanceof NetworkError) {
    return "网络连接失败，请检查网络";
  }
  return "登录失败，请稍后重试";
}

/**
 * 读取回跳地址：仅允许站内路径（防开放重定向，`//` 协议相对地址一并拒绝），非法值回退首页
 */
function resolveRedirect(): string {
  const redirect = new URLSearchParams(window.location.search).get("redirect");
  return redirect && redirect.startsWith("/") && !redirect.startsWith("//") ? redirect : "/";
}

/**
 * 登录页（(auth) 路由组，无顶导壳，设计 §1.5.7）
 *
 * 全屏居中卡片：品牌徽标 + username/密码（眼睛切换可见性）+ teal 实底登录按钮；
 * 校验错误就地显示字段下方；接口失败统一卡片顶部 Alert；无记住我、无注册入口。
 */
export default function LoginPage() {
  const router = useRouter();
  const { login } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<{ username?: string; password?: string }>({});
  const [alertMessage, setAlertMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // zod 前置校验：不合法就地显示字段错误，不发起请求
    const parsed = loginSchema.safeParse({ username, password });
    if (!parsed.success) {
      const errors: { username?: string; password?: string } = {};
      for (const issue of parsed.error.issues) {
        const key = issue.path[0];
        if ((key === "username" || key === "password") && !errors[key]) {
          errors[key] = issue.message;
        }
      }
      setFieldErrors(errors);
      return;
    }
    setFieldErrors({});
    setAlertMessage(null);
    setSubmitting(true);
    try {
      await login(parsed.data.username, parsed.data.password);
      // 成功跳转：优先 ?redirect= 站内回跳，否则首页
      router.push(resolveRedirect());
    } catch (error) {
      setAlertMessage(toAlertMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center px-6 py-12">
      <div className="w-full max-w-[420px] rounded-2xl border border-border bg-surface p-10 shadow-lg shadow-brand/5">
        {/* 品牌区：kimi 渐变徽标 + 品牌名 */}
        <div className="mb-8 flex flex-col items-center gap-3">
          <span
            aria-hidden
            className="bg-gradient-ai flex h-12 w-12 items-center justify-center rounded-xl text-white shadow-lg shadow-brand/30"
          >
            <Sparkle size={24} weight="fill" />
          </span>
          <span className="font-display text-lg font-bold tracking-tight text-text">课程助手</span>
          <h2 className="font-display text-[22px] font-semibold leading-[1.3] text-text">
            欢迎回来
          </h2>
        </div>

        {/* 接口失败统一 Alert（401 凭证错 / 403 已禁用 / 网络错误） */}
        {alertMessage ? (
          <div
            role="alert"
            className="mb-6 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3 text-sm text-danger"
          >
            {alertMessage}
          </div>
        ) : null}

        <form onSubmit={handleSubmit} noValidate>
          <div className="mb-5">
            <label htmlFor="username" className="mb-2 block text-sm font-medium text-text">
              用户名
            </label>
            <input
              id="username"
              name="username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              aria-invalid={fieldErrors.username ? true : undefined}
              className="w-full rounded-full border border-border bg-surface px-4 py-2.5 text-[15px] text-text outline-none transition-colors placeholder:text-subtle focus-visible:ring-2 focus-visible:ring-brand"
              placeholder="请输入用户名"
            />
            {fieldErrors.username ? (
              <p className="mt-1.5 text-[13px] text-danger">{fieldErrors.username}</p>
            ) : null}
          </div>

          <div className="mb-6">
            <label htmlFor="password" className="mb-2 block text-sm font-medium text-text">
              密码
            </label>
            <div className="relative">
              <input
                id="password"
                name="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-invalid={fieldErrors.password ? true : undefined}
                className="w-full rounded-full border border-border bg-surface px-4 py-2.5 pr-11 text-[15px] text-text outline-none transition-colors placeholder:text-subtle focus-visible:ring-2 focus-visible:ring-brand"
                placeholder="请输入密码（至少 6 位）"
              />
              <button
                type="button"
                aria-label={showPassword ? "隐藏密码" : "显示密码"}
                onClick={() => setShowPassword((visible) => !visible)}
                className="absolute right-3 top-1/2 -translate-y-1/2 rounded-lg p-1 text-muted transition-colors hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
              >
                {showPassword ? (
                  <EyeSlash size={20} weight="bold" />
                ) : (
                  <Eye size={20} weight="bold" />
                )}
              </button>
            </div>
            {fieldErrors.password ? (
              <p className="mt-1.5 text-[13px] text-danger">{fieldErrors.password}</p>
            ) : null}
          </div>

          <button
            type="submit"
            disabled={submitting}
            className="flex w-full items-center justify-center gap-2 rounded-full bg-brand px-4 py-2.5 text-[15px] font-medium text-white transition-all hover:bg-brand-strong active:scale-[0.98] active:-translate-y-px disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
          >
            {submitting ? (
              // 工作指示：登录中的旋转反馈（常驻循环动画仅限工作指示，设计 §0.2）
              <span
                aria-hidden
                className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white"
              />
            ) : null}
            {submitting ? "登录中…" : "登录"}
          </button>
        </form>

        <p className="mt-8 text-center text-[13px] text-muted">没有账号？请联系课程老师</p>
      </div>
    </main>
  );
}
