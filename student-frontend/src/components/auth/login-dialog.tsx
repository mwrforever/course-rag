"use client";

/**
 * 全局登录弹窗（登录弹窗化 2026-08-26：独立 /login 页下线，登录态变化一律弹窗承载）
 *
 * 结构：遮罩 + 居中卡片（role=dialog + aria-modal）。交互契约：
 * - 打开/关闭由 AuthProvider 的 loginDialogOpen 状态驱动（openLoginDialog/closeLoginDialog）
 * - Esc / 遮罩点击 / 右上关闭按钮 → closeLoginDialog（登录中不可关闭，防状态错乱）
 * - 提交：zod 前置校验（username 非空 + 密码 ≥6 位）→ submitLogin →
 *   成功后由 AuthProvider 关闭弹窗并执行 afterLogin（调用方登记的后续动作）
 * - 接口失败分级 Alert（401 凭证错 / 403 已禁用 / 网络错误），不关闭弹窗
 *
 * 挂载位置：根布局 AuthProvider 内，全路由组可触发（含公开页登录墙场景）。
 */
import { Eye, EyeSlash, Sparkle, X } from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { ApiError, NetworkError } from "@/lib/api";
import { z } from "zod";

/** 登录表单校验（设计 §1.5.7：username 非空 + 密码 ≥6 位；zod 前置校验优先于后端 400） */
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
 * 全局登录弹窗
 */
export function LoginDialog() {
  const { loginDialogOpen, submitLogin, closeLoginDialog } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<{ username?: string; password?: string }>({});
  const [alertMessage, setAlertMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // 用户名输入聚焦入口（弹窗打开时直达表单首字段）
  const usernameRef = useRef<HTMLInputElement | null>(null);

  // 打开时聚焦用户名 + Esc 关闭（登录中不响应 Esc，防提交中断）
  useEffect(() => {
    if (!loginDialogOpen) {
      return;
    }
    usernameRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !submitting) {
        closeLoginDialog();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [loginDialogOpen, submitting, closeLoginDialog]);

  // 弹窗关闭时清空输入与错误态（下次打开干净表单）
  useEffect(() => {
    if (!loginDialogOpen) {
      setUsername("");
      setPassword("");
      setFieldErrors({});
      setAlertMessage(null);
    }
  }, [loginDialogOpen]);

  if (!loginDialogOpen) {
    return null;
  }

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
      // 成功后 AuthProvider 内部关闭弹窗并执行 afterLogin（本组件不负责跳转）
      await submitLogin(parsed.data.username, parsed.data.password);
    } catch (error) {
      setAlertMessage(toAlertMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50">
      {/* 遮罩：点击关闭（登录中禁用） */}
      <div
        data-testid="login-overlay"
        aria-hidden
        onClick={() => {
          if (!submitting) closeLoginDialog();
        }}
        className="absolute inset-0 animate-overlay-in bg-overlay motion-reduce:animate-none"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="登录课程助手"
        className="absolute top-1/2 left-1/2 w-full max-w-[400px] -translate-x-1/2 -translate-y-1/2 animate-drawer-in rounded-2xl border border-border bg-surface p-8 shadow-xl motion-reduce:animate-none"
      >
        <button
          type="button"
          aria-label="关闭登录"
          onClick={closeLoginDialog}
          disabled={submitting}
          className="absolute top-4 right-4 grid size-8 place-items-center rounded-lg text-muted transition-colors hover:bg-surface-2 hover:text-text disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
        >
          <X size={16} aria-hidden />
        </button>

        {/* 品牌区：kimi 渐变徽标 + 欢迎语 */}
        <div className="mb-6 flex flex-col items-center gap-2.5">
          <span
            aria-hidden
            className="bg-gradient-ai grid size-11 place-items-center rounded-xl text-white shadow-md shadow-brand/30"
          >
            <Sparkle size={22} weight="fill" />
          </span>
          <h2 className="font-display text-xl font-bold tracking-tight text-text">登录课程助手</h2>
          <p className="text-[13px] text-muted">登录后可继续课程提问与资料学习</p>
        </div>

        {/* 接口失败统一 Alert（401 凭证错 / 403 已禁用 / 网络错误） */}
        {alertMessage ? (
          <div
            role="alert"
            className="mb-5 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3 text-sm text-danger"
          >
            {alertMessage}
          </div>
        ) : null}

        <form onSubmit={handleSubmit} noValidate>
          <div className="mb-4">
            <label htmlFor="login-username" className="mb-2 block text-sm font-medium text-text">
              用户名
            </label>
            <input
              ref={usernameRef}
              id="login-username"
              name="username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              aria-invalid={fieldErrors.username ? true : undefined}
              className="w-full rounded-xl border border-border bg-surface px-4 py-2.5 text-[15px] text-text outline-none transition-colors placeholder:text-subtle focus-visible:ring-2 focus-visible:ring-brand"
              placeholder="请输入用户名"
            />
            {fieldErrors.username ? (
              <p className="mt-1.5 text-[13px] text-danger">{fieldErrors.username}</p>
            ) : null}
          </div>

          <div className="mb-6">
            <label htmlFor="login-password" className="mb-2 block text-sm font-medium text-text">
              密码
            </label>
            <div className="relative">
              <input
                id="login-password"
                name="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-invalid={fieldErrors.password ? true : undefined}
                className="w-full rounded-xl border border-border bg-surface px-4 py-2.5 pr-11 text-[15px] text-text outline-none transition-colors placeholder:text-subtle focus-visible:ring-2 focus-visible:ring-brand"
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
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-brand px-4 py-2.5 text-[15px] font-medium text-white transition-all hover:bg-brand-strong active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
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

        <p className="mt-6 text-center text-[13px] text-muted">没有账号？请联系课程老师</p>
      </div>
    </div>
  );
}
