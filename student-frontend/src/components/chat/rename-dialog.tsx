"use client";

/**
 * 会话重命名弹窗（Task 13 弹窗化：行内编辑 → 表单弹窗）
 *
 * 交互契约：
 * - 打开：预填当前标题（initialTitle）并聚焦输入框（键盘直达）
 * - 提交：zod 边界校验（trim 后非空且 ≤50 字）→ onConfirm(title)；
 *   Enter 键等价保存按钮（表单 submit 拦截）
 * - 校验失败：中文错误文案就地展示，不发请求、弹窗保留
 * - 保存中：按钮禁用防重复提交（失败由调用方 toast，弹窗保留可重试）
 * - 关闭：Esc / 遮罩点击 / 取消按钮 → onCancel（关闭由调用方状态驱动）
 *
 * 渲染：createPortal 挂 document.body（对齐 ConfirmDialog portal 范式，
 * 避免调用方处 sticky/backdrop-blur/transform 容器收窄 fixed 包含块）。
 */
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { z } from "zod";

/** 会话标题上限（Task 13 弹窗化拍板：非空 ≤50 字；与后端 @Size 上限取更严的前端口径） */
export const RENAME_TITLE_MAX_LENGTH = 50;

/** 标题 zod 校验 schema（trim 后非空 + 长度上限，错误文案中文就地展示） */
const renameTitleSchema = z
  .string()
  .trim()
  .min(1, "标题不能为空")
  .max(RENAME_TITLE_MAX_LENGTH, `标题不能超过 ${RENAME_TITLE_MAX_LENGTH} 个字`);

/** 重命名弹窗 props */
export interface RenameDialogProps {
  /** 弹窗展开态（调用方状态驱动关闭） */
  open: boolean;
  /** 预填标题（当前会话标题，打开时落入输入框） */
  initialTitle: string;
  /** 保存回调（zod 校验通过后调用；Promise 进行中禁用表单，成功由调用方关窗） */
  onConfirm(title: string): void | Promise<void>;
  /** 取消回调（Esc/遮罩/取消按钮共同出口） */
  onCancel(): void;
}

/**
 * 会话重命名弹窗（portal 表单弹窗）
 *
 * @param props 见 RenameDialogProps
 */
export function RenameDialog({ open, initialTitle, onConfirm, onCancel }: RenameDialogProps) {
  // 表单状态：标题输入 + 校验错误 + 保存中态
  const [title, setTitle] = useState(initialTitle);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // 输入框聚焦入口（打开时聚焦，键盘直达编辑）
  const inputRef = useRef<HTMLInputElement | null>(null);
  // 客户端挂载标记：SSR/hydration 首帧不渲染（portal 依赖 document.body）
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  // 打开时重置表单（预填 + 清错误/保存态）并聚焦输入框；Esc 关闭
  useEffect(() => {
    if (!open) return;
    setTitle(initialTitle);
    setError(null);
    setSaving(false);
    inputRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onCancel();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, initialTitle, onCancel]);

  if (!open || !mounted) {
    return null;
  }

  /**
   * 提交保存：zod 校验 → onConfirm；失败中文错误就地展示
   * （保存中重复提交守卫；调用方失败不关窗，用户修正后可重试）
   */
  async function submit() {
    if (saving) return;
    const parsed = renameTitleSchema.safeParse(title);
    if (!parsed.success) {
      // 首个校验错误中文文案（非空/超长二选一，不会同时命中）
      setError(parsed.error.issues[0]?.message ?? "标题不能为空");
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await onConfirm(parsed.data);
    } finally {
      setSaving(false);
    }
  }

  return createPortal(
    <div className="fixed inset-0 z-50">
      <div
        data-testid="rename-overlay"
        aria-hidden
        onClick={onCancel}
        className="absolute inset-0 animate-overlay-in bg-overlay motion-reduce:animate-none"
      />
      <form
        role="dialog"
        aria-modal="true"
        aria-label="重命名会话"
        onSubmit={(event) => {
          // 表单默认提交拦截（Enter 触发保存而非页面刷新）
          event.preventDefault();
          void submit();
        }}
        className="absolute top-1/2 left-1/2 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 animate-drawer-in rounded-2xl border border-border bg-surface p-6 shadow-xl motion-reduce:animate-none"
      >
        <h3 className="font-display text-lg font-semibold text-text">重命名会话</h3>
        <input
          ref={inputRef}
          type="text"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          onKeyDown={(event) => {
            // Enter 显式提交（jsdom 无浏览器隐式 submit；IME 候选确认不触发）
            if (event.key !== "Enter") return;
            if (event.nativeEvent.isComposing) return;
            event.preventDefault();
            void submit();
          }}
          maxLength={RENAME_TITLE_MAX_LENGTH + 10}
          aria-label="会话标题"
          data-testid="rename-input"
          placeholder="输入新的会话标题"
          className={`mt-4 w-full rounded-xl border bg-surface-2 px-3 py-2.5 text-sm text-text outline-none transition-colors placeholder:text-subtle focus-visible:ring-2 focus-visible:ring-brand ${
            error ? "border-danger" : "border-border"
          }`}
        />
        {/* 校验错误中文文案（aria-live 播报，就地展示不弹 toast） */}
        {error ? (
          <p role="alert" data-testid="rename-error" className="mt-1.5 text-xs text-danger">
            {error}
          </p>
        ) : null}
        <div className="mt-6 flex justify-end gap-3">
          <button
            type="button"
            onClick={onCancel}
            disabled={saving}
            className="rounded-xl border border-border bg-surface px-4 py-2 text-sm font-medium text-muted transition-colors hover:border-brand/40 hover:text-brand-strong disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
          >
            取消
          </button>
          <button
            type="submit"
            disabled={saving}
            className="rounded-xl bg-brand px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brand-strong disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
          >
            {saving ? "保存中…" : "保存"}
          </button>
        </div>
      </form>
    </div>,
    document.body,
  );
}
