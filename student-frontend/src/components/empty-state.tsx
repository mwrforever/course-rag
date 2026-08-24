"use client";

import Link from "next/link";
import { AiBadge } from "./ai-badge";

/** 空态组件 props */
export interface EmptyStateProps {
  /** 主文案（一句话说明，设计 §1.7 Empty 规范） */
  title: string;
  /** 行动入口文案（提供 actionHref 时渲染按钮） */
  actionLabel?: string;
  /** 行动入口跳转目标 */
  actionHref?: string;
  /** 是否展示 AI 徽标（默认展示，设计 §1.7 空态 = 徽标 + 一句话 + 一个行动入口） */
  showAiBadge?: boolean;
  /** 布局容器附加类名 */
  className?: string;
}

/**
 * 通用空态组件（设计 §1.7 Empty）
 *
 * 徽标 + 一句话 + 一个行动入口；禁止裸「暂无数据」。
 * 首页「还没有加入课程，请联系老师开通」与后续页空态（会话/课程）共用。
 */
export function EmptyState({
  title,
  actionLabel,
  actionHref,
  showAiBadge = true,
  className = "",
}: EmptyStateProps) {
  return (
    <div className={`flex flex-col items-center gap-4 py-14 text-center ${className}`}>
      {showAiBadge ? <AiBadge /> : null}
      <p className="max-w-md text-[15px] leading-relaxed text-muted">{title}</p>
      {actionLabel && actionHref ? (
        <Link
          href={actionHref}
          className="mt-1 inline-flex items-center gap-2 rounded-xl border border-brand/30 bg-surface px-4 py-2 text-sm font-medium text-brand-strong transition-colors hover:bg-brand-light focus-visible:ring-2 focus-visible:ring-brand"
        >
          {actionLabel}
        </Link>
      ) : null}
    </div>
  );
}
