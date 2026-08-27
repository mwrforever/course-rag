"use client";

/**
 * 注册面板 —— 邮箱验证码两段式（设计稿二 Create Account 结构改造）
 *
 * 业务替换：Full Name/Year Group/条款 → 昵称(可选) / 邮箱 + 发送验证码（60s 倒计时频控）
 * / 6 位验证码 / 密码强度计 / 条款勾选。流程契约：
 * 1. 输入邮箱 → 点击「获取验证码」（先 zod 校验邮箱）→ sendRegisterCode → toast + 倒计时；
 * 2. 填写验证码/密码/昵称 → registerFormSchema 校验 → registerAndLogin（注册即登录）
 * → onSuccess。
 * 失败语义透传后端中文 message（409 已注册/间隔、400 码错/锁定、503 邮件故障）。
 */
import { useEffect, useRef, useState } from "react";
import { PasswordField } from "@/components/auth/password-field";
import { ApiError, sendRegisterCode, registerAndLogin } from "@/lib/api";
import { registerFormSchema, sendCodeSchema, type RegisterFormValues } from "@/lib/auth-schemas";
import { scorePassword, strengthHint } from "@/lib/password-strength";

/** 发码倒计时秒数（与后端 60s 重发间隔一致，提示语动态对齐服务端频控） */
const RESEND_COUNTDOWN_SECONDS = 60;

/** 字段落错容器类型 */
type FieldErrors = Partial<Record<keyof RegisterFormValues | "terms", string>>;

/** 注册面板属性 */
interface RegisterPanelProps {
  /** 注册成功回调（自动登录已完成） */
  onSuccess: () => void;
}

/**
 * 注册面板
 */
export function RegisterPanel({ onSuccess }: RegisterPanelProps) {
  const [nickname, setNickname] = useState("");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [termsAccepted, setTermsAccepted] = useState(false);

  // 验证码发送状态：sending（请求中）/ countdown（剩余秒）；interval 引用供清理
  const [countdown, setCountdown] = useState(0);
  const intervalRef = useRef<number | null>(null);
  const [sendBusy, setSendBusy] = useState(false);

  const [errors, setErrors] = useState<FieldErrors>({});
  const [serverMessage, setServerMessage] = useState<{ kind: "error" | "ok"; text: string } | null>(
    null,
  );
  const [submitting, setSubmitting] = useState(false);

  // 倒计时归零自动清 interval
  useEffect(() => {
    if (countdown <= 0 && intervalRef.current != null) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, [countdown]);
  useEffect(
    () => () => {
      if (intervalRef.current != null) {
        clearInterval(intervalRef.current);
      }
    },
    [],
  );

  /** 点击「获取验证码」：邮箱边界校验 → 调用发码接口 → 启动 60s 倒计时 */
  async function handleSendCode() {
    setServerMessage(null);
    const parsed = sendCodeSchema.safeParse({ email });
    if (!parsed.success) {
      setErrors({ email: parsed.error.issues[0].message });
      return;
    }
    setErrors({});
    if (sendBusy || countdown > 0) {
      return;
    }
    setSendBusy(true);
    try {
      await sendRegisterCode(parsed.data.email);
      setCountdown(RESEND_COUNTDOWN_SECONDS);
      intervalRef.current = window.setInterval(() => setCountdown((sec) => sec - 1), 1000);
      setServerMessage({ kind: "ok", text: "验证码已发送至你的邮箱，15 分钟内有效" });
    } catch (error) {
      const message = error instanceof Error ? error.message : "发送失败，请稍后再试";
      setServerMessage({ kind: "error", text: message });
    } finally {
      setSendBusy(false);
    }
  }

  /** 提交注册：全表单校验 → registerAndLogin 自动建立会话 → 回调成功 */
  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setServerMessage(null);

    const candidate = {
      nickname: nickname.trim() || undefined,
      email,
      code,
      password,
      ...(termsAccepted ? { terms: true as const } : {}),
    };
    const parsed = registerFormSchema.safeParse(candidate);
    if (!parsed.success) {
      const flat: FieldErrors = {};
      for (const issue of parsed.error.issues) {
        const key = String(issue.path[0]) as keyof FieldErrors;
        flat[key] ??= issue.message;
      }
      setErrors(flat);
      return;
    }
    if (!parsed.data.terms) {
      return; // schema literal(false) 会走 success 分支外的 issues；此行兜底可读性
    }
    setErrors({});
    if (submitting) {
      return;
    }
    setSubmitting(true);
    try {
      await registerAndLogin({
        email: parsed.data.email,
        code: parsed.data.code,
        password: parsed.data.password,
        nickname: parsed.data.nickname,
      });
      onSuccess();
    } catch (error) {
      const message = error instanceof ApiError ? error.message : "网络异常，请稍后再试";
      setServerMessage({ kind: "error", text: message });
    } finally {
      setSubmitting(false);
    }
  }

  const strength = scorePassword(password);

  return (
    <form
      onSubmit={handleSubmit}
      noValidate
      data-testid="register-panel"
      role="tabpanel"
      aria-label="注册面板"
    >
      {/* 服务端/流程反馈条 */}
      {serverMessage ? (
        <div
          role={serverMessage.kind === "error" ? "alert" : undefined}
          data-testid={
            serverMessage.kind === "error" ? "register-server-error" : "register-server-ok"
          }
          className={`mb-4 rounded-md px-4 py-2.5 text-[13px] ${
            serverMessage.kind === "error"
              ? "border border-danger/30 bg-danger/10 text-danger"
              : "border border-success/30 bg-success/10 text-success"
          }`}
        >
          {serverMessage.text}
        </div>
      ) : null}

      <label
        htmlFor="reg-nickname"
        className="mb-2.5 block text-[10.5px] tracking-[0.14em] text-muted uppercase"
      >
        昵称 <span className="normal-case opacity-70">（可选，留空使用邮箱前缀）</span>
      </label>
      <input
        id="reg-nickname"
        type="text"
        value={nickname}
        onChange={(event) => setNickname(event.target.value)}
        placeholder="同学们怎么称呼你"
        data-testid="reg-nickname-input"
        className="w-full rounded-none border border-border px-4 py-[15px] text-sm transition-colors outline-none placeholder:text-faint focus:border-ink"
      />
      {errors.nickname ? (
        <span role="alert" className="mt-1.5 block text-[11px] text-danger">
          {errors.nickname}
        </span>
      ) : null}

      {/* 邮箱 + 发送验证码按钮行 */}
      <div className="mt-5 mb-5">
        <label
          htmlFor="reg-email"
          className="mb-2.5 block text-[10.5px] tracking-[0.14em] text-muted uppercase"
        >
          邮箱地址 <b className="font-medium text-danger">*</b>
        </label>
        <div className="flex gap-2.5">
          <input
            id="reg-email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="you@example.com"
            autoComplete="email"
            data-testid="reg-email-input"
            className={`min-w-0 flex-1 rounded-none border px-4 py-[15px] text-sm transition-colors outline-none placeholder:text-faint focus:border-ink ${errors.email ? "border-danger" : "border-border"}`}
          />
          <button
            type="button"
            onClick={() => void handleSendCode()}
            disabled={sendBusy || countdown > 0}
            data-testid="send-code-button"
            className="shrink-0 rounded-full border border-ink px-6 py-2 text-[11px] tracking-[0.12em] whitespace-nowrap uppercase transition-colors duration-300 hover:bg-ink hover:text-bg disabled:cursor-not-allowed disabled:border-border disabled:bg-transparent disabled:text-subtle"
          >
            {sendBusy ? "发送中…" : countdown > 0 ? `${countdown}s 后重发` : "获取验证码"}
          </button>
        </div>
        {errors.email ? (
          <span role="alert" className="mt-1.5 block text-[11px] text-danger">
            {errors.email}
          </span>
        ) : null}
      </div>

      <div className="mb-5 flex flex-col">
        <label
          htmlFor="reg-code"
          className="mb-2.5 block text-[10.5px] tracking-[0.14em] text-muted uppercase"
        >
          邮箱验证码 <b className="font-medium text-danger">*</b>
        </label>
        <input
          id="reg-code"
          type="text"
          inputMode="numeric"
          maxLength={6}
          value={code}
          onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))}
          placeholder="123456"
          autoComplete="one-time-code"
          data-testid="reg-code-input"
          className={`rounded-none border px-4 py-[15px] text-sm tracking-[0.5em] outline-none placeholder:tracking-normal placeholder:text-faint focus:border-ink ${errors.code ? "border-danger" : "border-border"}`}
        />
        {errors.code ? (
          <span role="alert" className="mt-1.5 block text-[11px] text-danger">
            {errors.code}
          </span>
        ) : null}
      </div>

      <PasswordField
        id="reg-password"
        label="密码"
        placeholder="至少 8 位"
        value={password}
        onChange={setPassword}
        hasError={Boolean(errors.password)}
        errorMessage={errors.password ?? ""}
        autoComplete="new-password"
      />

      {/* 强度计四格 */}
      <div className="-mt-2 mb-5" aria-live="polite">
        <div className="flex gap-1.5">
          {[0, 1, 2, 3].map((level) => (
            <i
              key={level}
              aria-hidden
              className={`h-[3px] flex-1 rounded-sm transition-colors duration-300 ${
                strength > level
                  ? ["bg-danger", "bg-warning", "bg-warning", "bg-success"][strength - 1]
                  : "bg-border"
              }`}
            />
          ))}
        </div>
        <p className="mt-1.5 text-[10px] tracking-wide text-muted">{strengthHint(strength)}</p>
      </div>

      {/* 条款勾选 */}
      <label
        className="mt-2 mb-6 flex cursor-pointer items-center gap-2.5 text-[12.5px] text-muted select-none"
        data-testid="terms-row"
      >
        <input
          type="checkbox"
          checked={termsAccepted}
          onChange={(event) => setTermsAccepted(event.target.checked)}
          data-testid="terms-checkbox"
          className="peer sr-only"
        />
        <span
          aria-hidden
          className="grid size-[18px] shrink-0 place-items-center rounded-full border border-border-strong bg-transparent transition-colors peer-checked:border-ink peer-checked:bg-ink"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="3"
            aria-hidden
            className="size-2.5 text-bg opacity-0 transition-opacity peer-checked:opacity-100"
            style={{ opacity: termsAccepted ? 1 : 0 }}
          >
            <path d="M4 12l6 6L20 6" />
          </svg>
        </span>
        我已阅读并同意<span className="border-b border-border text-text">《服务条款》</span>与
        <span className="border-b border-border text-text">《隐私政策》</span>
      </label>
      {errors.terms ? (
        <p role="alert" className="-mt-3 mb-4 text-[11px] text-danger">
          {errors.terms}
        </p>
      ) : null}

      <button
        type="submit"
        disabled={submitting}
        data-testid="register-submit"
        className="btn-pill btn-solid w-full disabled:cursor-not-allowed disabled:opacity-70"
      >
        {submitting ? "注册中…" : "创建账户"}
      </button>
      <p className="mt-5 text-[11px] leading-[1.8] text-muted">
        创建账户即表示同意接收与课程相关的必要通知（例如安全提醒）。你随时可以联系管理员注销账号。
      </p>
    </form>
  );
}
