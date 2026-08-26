"use client";

/**
 * 通用确认弹窗（会话删除/登出等破坏性操作二次确认）
 *
 * 结构：遮罩 + 居中卡片（role=dialog + aria-modal）。语义契约：
 * - Esc / 遮罩点击 / 取消按钮 → onCancel（关闭由调用方状态驱动）
 * - 确认按钮 danger 变体（默认品牌色），loading 时禁用防重复提交
 * - 打开时焦点初始落在确认按钮（键盘可达；不实现全量 focus trap，
 *   与既有 Dialog（/sessions 页）交互语义保持一致）
 *
 * 渲染：createPortal 挂 document.body——调用方常处 sticky/backdrop-blur/transform
 * 容器（顶导 backdrop-blur 会把 fixed 子元素包含块收窄到 64px 导航栏，弹窗飘在顶部；
 * 修复 2026-08-26），portal 到 body 保证 fixed inset-0 恒相对视口；
 * mounted 挂载态兜底 SSR 无 document（hydration 首帧不渲染，调用方均交互触发打开）。
 *
 * 动效：overlay-in / drawer-in（reduced-motion 全静态，与全站一致）。
 */
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

/** 确认弹窗属性 */
export interface ConfirmDialogProps {
  /** 弹窗展开态（调用方状态驱动关闭） */
  open: boolean;
  /** 标题（dialog aria-label 来源） */
  title: string;
  /** 说明文案（被确认对象的描述渲染，如「确定删除「xxx」吗？」） */
  description: React.ReactNode;
  /** 确认按钮文案（缺省「确认」） */
  confirmText?: string;
  /** 取消按钮文案（缺省「取消」） */
  cancelText?: string;
  /** danger 变体：确认按钮红色（删除类场景） */
  danger?: boolean;
  /** 确认中态：按钮禁用并显示「…中」 */
  loading?: boolean;
  /** 确认回调（返回 Promise 时自动接入 loading 键） */
  onConfirm: () => void | Promise<void>;
  /** 取消回调（Esc/遮罩/取消按钮共同出口） */
  onCancel: () => void;
}

/**
 * 通用确认弹窗
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmText = "确认",
  cancelText = "取消",
  danger = false,
  loading = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  // 确认按钮聚焦入口（打开时聚焦；加载中禁用时转移焦点由浏览器处理）
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  // 客户端挂载标记：SSR/hydration 首帧不渲染（portal 依赖 document.body），挂载后统一渲染
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  // Esc 关闭 + 打开时聚焦确认按钮（可访问性：键盘直达操作出口）
  useEffect(() => {
    if (!open) {
      return;
    }
    confirmRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onCancel();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, onCancel]);

  if (!open || !mounted) {
    return null;
  }

  return createPortal(
    <div className="fixed inset-0 z-50">
      <div
        data-testid="confirm-overlay"
        aria-hidden
        onClick={onCancel}
        className="absolute inset-0 animate-overlay-in bg-overlay motion-reduce:animate-none"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="absolute top-1/2 left-1/2 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 animate-drawer-in rounded-2xl border border-border bg-surface p-6 shadow-xl motion-reduce:animate-none"
      >
        <h3 className="font-display text-lg font-semibold text-text">{title}</h3>
        <div className="mt-2 text-sm leading-relaxed text-muted">{description}</div>
        <div className="mt-6 flex justify-end gap-3">
          <button
            type="button"
            onClick={onCancel}
            disabled={loading}
            className="rounded-xl border border-border bg-surface px-4 py-2 text-sm font-medium text-muted transition-colors hover:border-brand/40 hover:text-brand-strong disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
          >
            {cancelText}
          </button>
          <button
            ref={confirmRef}
            type="button"
            onClick={() => void onConfirm()}
            disabled={loading}
            className={`rounded-xl px-4 py-2 text-sm font-medium text-white transition-colors disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 ${
              danger
                ? "bg-danger hover:bg-danger/90 focus-visible:ring-danger"
                : "bg-brand hover:bg-brand-strong focus-visible:ring-brand"
            }`}
          >
            {loading ? `${confirmText}中…` : confirmText}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
