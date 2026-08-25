"use client";

import { Sparkle } from "@phosphor-icons/react";
import { motion, useReducedMotion } from "motion/react";

/** AI 助教人格化徽标 props */
export interface AiBadgeProps {
  /** 附加类名（尺寸等布局微调） */
  className?: string;
}

/**
 * AI 助教人格化徽标（UI 重构 2026-08-25：kimi 蓝系）
 *
 * 几何渐变徽章（品牌蓝紫渐变底 + 顶部高光点 + 内圈圆环）+ Sparkle 图标；
 * 缓慢呼吸浮动（3.2s ease-in-out 循环、幅度 6px，kimi 首页 Doodle 语义）。
 *
 * 动效降级：prefers-reduced-motion 命中或检测不可用（useReducedMotion 返回 null）
 * 时完全静态，不挂任何动画 props（可访问性优先，未知态按静态处理）。
 */
export function AiBadge({ className = "" }: AiBadgeProps) {
  // 检测不可用（null）按静态处理：宁可无动效，不可违背 reduced-motion 偏好
  const reduceMotion = useReducedMotion() ?? true;

  return (
    <motion.span
      aria-hidden
      data-testid="ai-badge"
      className={`bg-gradient-ai relative grid size-16 shrink-0 place-items-center rounded-2xl text-white shadow-lg shadow-brand/30 ${className}`}
      animate={reduceMotion ? undefined : { y: [0, -6, 0] }}
      transition={reduceMotion ? undefined : { duration: 3.2, ease: "easeInOut", repeat: Infinity }}
    >
      {/* 几何装饰：顶部高光点 + 内圈圆环，叠加 Sparkle 居中（kimi AI 徽标语义） */}
      <span
        aria-hidden
        className="absolute -top-1 -right-1 size-3 rounded-full bg-white/80 blur-[1px]"
      />
      <span aria-hidden className="absolute size-9 rounded-full border border-white/25" />
      <Sparkle size={26} weight="fill" aria-hidden className="relative drop-shadow-sm" />
    </motion.span>
  );
}
