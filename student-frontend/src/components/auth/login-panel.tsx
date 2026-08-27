"use client";

/**
 * 登录面板 —— 用户名或邮箱 + 密码（设计稿二 paneIn 结构还原）
 *
 * 契约：
 * - 提交经 zod 边界校验后调用 useAuth().submitLogin（复用全局弹窗同一登录链路，
 *   凭据落存/用户态建立完全一致），成功回调 onSuccess；
 * - 失败按 ApiError.code 分级提示：401 凭证错误 / 403 账号被禁用 / 其余网络提示；
 * - 「忘记密码」走 mailto 找回路径（站内暂无自助重置流程）；
 * - 第三方登录按任务要求移除（设计稿 Google/Microsoft 区不再保留）。
 */
import { useState } from "react";
import { PasswordField } from "@/components/auth/password-field";
import { loginFormSchema } from "@/lib/auth-schemas";
import { useAuth } from "@/lib/auth-context";
import { ApiError } from "@/lib/api";

/** 登录面板属性 */
interface LoginPanelProps {
  /** 登录成功后的后续动作（如跳转 next 参数目标） */
  onSuccess: () => void;
}

/**
 * 登录面板
 */
export function LoginPanel({ onSuccess }: LoginPanelProps) {
  const { submitLogin } = useAuth();
  const [account, setAccount] = useState("");
  const [password, setPassword] = useState("");
  // 分字段校验失败态（zod 平面化读取）
  const [errors, setErrors] = useState<{ account?: string; password?: string }>({});
  const [submitting, setSubmitting] = useState(false);
  // 服务端分级错误（401/403 等业务语义）
  const [serverError, setServerError] = useState<string | null>(null);

  /**
   * 提交登录：边界校验 → submitLogin → onSuccess；未通过即设置分字段落错
   */
  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setServerError(null);

    const parsed = loginFormSchema.safeParse({ account, password });
    if (!parsed.success) {
      const flat = Object.fromEntries(
        parsed.error.issues.map((issue) => [String(issue.path[0]), issue.message]),
      );
      setErrors(flat);
      return;
    }
    setErrors({});
    if (submitting) {
      return;
    }
    setSubmitting(true);
    try {
      await submitLogin(parsed.data.account, parsed.data.password);
      onSuccess();
    } catch (error) {
      if (error instanceof ApiError && error.code === 403) {
        setServerError("账号已被禁用，如有疑问请联系管理员");
      } else if (error instanceof ApiError && error.code === 401) {
        setServerError("用户名或密码错误");
      } else {
        setServerError("网络异常，请稍后再试");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      noValidate
      data-testid="login-panel"
      role="tabpanel"
      aria-label="登录面板"
    >
      {/* 服务端错误横条（auth-dialog 同款分级契约） */}
      {serverError ? (
        <div
          role="alert"
          data-testid="login-server-error"
          className="mb-4 rounded-md border border-danger/30 bg-danger/10 px-4 py-2.5 text-[13px] text-danger"
        >
          {serverError}
        </div>
      ) : null}

      <div className={errors.account ? "" : undefined}>
        <label
          htmlFor="login-account"
          className="mb-2.5 block text-[10.5px] tracking-[0.14em] text-muted uppercase"
        >
          用户名或邮箱 <b className="font-medium text-danger">*</b>
        </label>
        <input
          id="login-account"
          type="text"
          value={account}
          onChange={(event) => setAccount(event.target.value)}
          placeholder="you@example.com 或用户名"
          autoComplete="username"
          data-testid="login-account-input"
          className={`w-full rounded-none border px-4 py-[15px] text-sm transition-colors outline-none placeholder:text-faint focus:border-ink ${errors.account ? "border-danger" : "border-border"}`}
        />
        {errors.account ? (
          <span role="alert" className="mt-1.5 block text-[11px] text-danger">
            {errors.account}
          </span>
        ) : null}
      </div>

      <div className="mt-5">
        <PasswordField
          id="login-password"
          label="密码"
          value={password}
          onChange={(value) => {
            setPassword(value);
          }}
          hasError={Boolean(errors.password)}
          errorMessage={errors.password ?? ""}
        />
      </div>

      <div className="mt-2 mb-6 flex items-center justify-between">
        <a
          href="mailto:18229923842@163.com?subject=找回密码"
          className="border-b border-transparent pb-0.5 text-[11px] tracking-[0.1em] text-muted uppercase transition-colors hover:border-ink hover:text-ink"
        >
          忘记密码？
        </a>
      </div>

      <button
        type="submit"
        disabled={submitting}
        data-testid="login-submit"
        className="btn-pill btn-solid w-full disabled:cursor-not-allowed disabled:opacity-70"
      >
        {submitting ? "登录中…" : "登录"}
      </button>
    </form>
  );
}
