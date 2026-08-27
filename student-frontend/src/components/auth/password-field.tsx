"use client";

import { useState } from "react";

/**
 * 密码输入框（登录/注册共用）：明暗文切换眼睛按钮 + 错误描边
 *
 * 设计稿 field/iwrap/eye 三层结构对应实现；
 * 无业务逻辑，错误显示由父级传入 hasError 控制。
 */

/** 密码输入框属性 */
interface PasswordFieldProps {
  /** 唯一 id（label htmlFor 关联与 aria） */
  id: string;
  /** 字段标签 */
  label: string;
  /** 占位提示 */
  placeholder?: string;
  /** 当前值 */
  value: string;
  /** 变更回调 */
  onChange: (value: string) => void;
  /** 是否处于校验失败态（描红边并展示错误行） */
  hasError?: boolean;
  /** 错误提示文案（hasError 时展示） */
  errorMessage?: string;
  /** 自动补全语义（current-password / new-password） */
  autoComplete?: string;
}

/**
 * 带可见性切换的密码输入框
 */
export function PasswordField({
  id,
  label,
  placeholder,
  value,
  onChange,
  hasError,
  errorMessage = "输入有误，请检查后重试",
  autoComplete = "current-password",
}: PasswordFieldProps) {
  // 明暗文切换（设计稿 eye 按钮）
  const [visible, setVisible] = useState(false);

  return (
    <div className={`mb-5 flex flex-col ${hasError ? "field-bad" : ""}`}>
      <label htmlFor={id} className="mb-2.5 text-[10.5px] tracking-[0.14em] text-muted uppercase">
        {label} <b className="font-medium text-danger">*</b>
      </label>
      <div className="relative">
        <input
          id={id}
          type={visible ? "text" : "password"}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder ?? "••••••••"}
          autoComplete={autoComplete}
          data-testid={`${id}-input`}
          className={`w-full rounded-none border px-4 py-[15px] text-sm transition-colors outline-none placeholder:text-faint focus:border-ink ${
            hasError ? "border-danger" : "border-border"
          }`}
        />
        <button
          type="button"
          aria-label={visible ? "隐藏密码" : "显示密码"}
          onClick={() => setVisible((state) => !state)}
          className="absolute top-1/2 right-1.5 grid size-9 -translate-y-1/2 place-items-center text-muted transition-colors hover:text-ink"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            aria-hidden
            className="size-[18px]"
          >
            <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z" />
            <circle cx="12" cy="12" r="3" />
          </svg>
        </button>
      </div>
      {hasError ? (
        <span
          role="alert"
          data-testid={`${id}-error`}
          className="mt-1.5 text-[11px] tracking-wide text-danger"
        >
          {errorMessage}
        </span>
      ) : null}
    </div>
  );
}
