"use client";

import { Sparkle } from "@phosphor-icons/react";
import { motion, useReducedMotion } from "motion/react";

/** AI 助教人格化徽标 props */
export interface AiBadgeProps {
  /** 附加类名（尺寸等布局微调） */
  className?: string;
}

/**
 * AI 助教人格化徽标（设计 §1.5.1 首页 Hero 右栏 / 空态）
 *
 * 几何渐变徽章（teal 渐变底 + 右上旋转小方块 + 内圈圆环）+ Sparkle 图标；
 * 缓慢呼吸浮动（6s ease-in-out 循环、幅度 4px，设计 §1.6 品牌生命感）。
 *
 * 动效降级：prefers-reduced-motion 命中或检测不可用（useReducedMotion 返回 null）
 * 时完全静态，不挂任何动画 props（taste-skill 硬性要求，未知态按可访问性优先处理）。
 */
export function AiBadge({ className = "" }: AiBadgeProps) {
  // 检测不可用（null）按静态处理：宁可无动效，不可违背 reduced-motion 偏好
  const reduceMotion = useReducedMotion() ?? true;

  return (
    <motion.span
      aria-hidden
      data-testid="ai-badge"
      className={`relative grid size-16 shrink-0 place-items-center rounded-2xl bg-linear-to-br from-brand to-brand-strong text-white shadow-md shadow-teal-900/5 ${className}`}
      animate={reduceMotion ? undefined : { y: [0, -4, 0] }}
      transition={reduceMotion ? undefined : { duration: 6, ease: "easeInOut", repeat: Infinity }}
    >
      {/* 几何装饰：右上角旋转小方块 + 内圈圆环，叠加 Sparkle 居中 */}
      <span
        aria-hidden
        className="absolute -top-1 -right-1 size-3 rotate-12 rounded-[4px] bg-brand-light"
      />
      <span aria-hidden className="absolute size-9 rounded-full border border-white/30" />
      <Sparkle size={26} weight="fill" aria-hidden className="relative" />
    </motion.span>
  );
}
