"use client";

import type { Icon } from "@phosphor-icons/react";
import {
  BookOpen,
  Briefcase,
  Clock,
  Code,
  Flask,
  GlobeHemisphereWest,
  MathOperations,
  Palette,
  Star,
  User,
  Users,
} from "@phosphor-icons/react";
import { motion, useReducedMotion } from "motion/react";
import Image from "next/image";
import Link from "next/link";
import { useState } from "react";
import type { StudentCourse } from "@/lib/types";

/** 学科兜底映射表：category 关键词 → 学科图标 + 低饱和渐变（设计 §1.5.1 无封面兜底） */
const CATEGORY_FALLBACKS: ReadonlyArray<{
  keywords: readonly string[];
  icon: Icon;
  gradient: string;
}> = [
  {
    keywords: ["计算机", "编程", "软件", "开发"],
    icon: Code,
    gradient: "from-sky-100 to-indigo-100",
  },
  { keywords: ["数学", "统计"], icon: MathOperations, gradient: "from-violet-100 to-fuchsia-100" },
  {
    keywords: ["英语", "外语", "语言"],
    icon: GlobeHemisphereWest,
    gradient: "from-teal-100 to-emerald-100",
  },
  {
    keywords: ["物理", "化学", "生物", "科学"],
    icon: Flask,
    gradient: "from-amber-100 to-orange-100",
  },
  { keywords: ["经济", "管理", "商"], icon: Briefcase, gradient: "from-rose-100 to-pink-100" },
  { keywords: ["艺术", "音乐", "设计"], icon: Palette, gradient: "from-orange-100 to-yellow-100" },
];

/** 默认兜底：未知/空 category（teal 低饱和渐变 + 书本图标） */
const DEFAULT_FALLBACK: { icon: Icon; gradient: string } = {
  icon: BookOpen,
  gradient: "from-brand-light to-stone-100",
};

/**
 * 解析课程学科兜底（关键词包含匹配，防御后端分类枚举变化）
 * @param category 课程分类（可空，null 走默认兜底）
 * @returns 兜底学科图标与渐变类名
 */
function coverFallback(category: string | null): { icon: Icon; gradient: string } {
  const cat = category ?? "";
  for (const entry of CATEGORY_FALLBACKS) {
    if (entry.keywords.some((keyword) => cat.includes(keyword))) {
      return { icon: entry.icon, gradient: entry.gradient };
    }
  }
  return DEFAULT_FALLBACK;
}

/** 课程卡 props */
export interface CourseCardProps {
  /** 学生课程数据（J1） */
  course: StudentCourse;
  /** 首屏 LCP 优化：首卡封面高优先级加载 */
  priority?: boolean;
}

/**
 * 课程卡（设计 §1.5.1）
 *
 * 结构：16:9 封面（next/image，MinIO remote loader；无封面或加载失败时按 category
 * 映射低饱和渐变 + 学科图标兜底）+ 标题 2 行截断 + meta 行（讲师/课时/星级/学习人数，
 * 数字 tabular-nums，可空字段按需省略）。
 *
 * 动效（设计 §1.6）：hover 封面 scale(1.02) + 卡片阴影抬升 + 「进入课程」CTA 滑入（200ms）；
 * prefers-reduced-motion 或检测不可用时 hover 缩放静态。
 *
 * 封面错误兜底实现：next/image 不支持 onError，error 事件不冒泡但走捕获阶段，
 * 由封面容器 onErrorCapture 接住底层 img 的 error 后切换兜底渐变。
 */
export function CourseCard({ course, priority = false }: CourseCardProps) {
  // 检测不可用（null）按静态处理，与 AiBadge 一致的可访问性优先策略
  const reduceMotion = useReducedMotion() ?? true;
  // 封面加载失败标记：置真后渲染学科渐变兜底
  const [coverBroken, setCoverBroken] = useState(false);
  const coverUrl = course.coverImage;
  const showCover = Boolean(coverUrl) && !coverBroken;
  const fallback = coverFallback(course.category);
  const FallbackIcon = fallback.icon;

  return (
    <Link
      href={`/courses/${course.id}`}
      className="group flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-surface shadow-sm transition-all duration-200 hover:border-brand/30 hover:shadow-md hover:shadow-teal-900/5 focus-visible:ring-2 focus-visible:ring-brand"
    >
      {/* 封面区：16:9；hover 微缩放（reduced-motion 静态）+ 「进入课程」CTA 从底部滑入 */}
      <motion.div
        onErrorCapture={() => setCoverBroken(true)}
        className="relative aspect-video overflow-hidden bg-surface-2"
        whileHover={reduceMotion ? undefined : { scale: 1.02 }}
        transition={reduceMotion ? undefined : { duration: 0.2 }}
      >
        {showCover ? (
          <Image
            src={coverUrl as string}
            alt={course.title}
            fill
            sizes="(max-width: 768px) 100vw, 25vw"
            priority={priority}
            className="object-cover"
          />
        ) : (
          <div
            data-testid="cover-fallback"
            className={`grid h-full w-full place-items-center bg-linear-to-br ${fallback.gradient}`}
          >
            <FallbackIcon size={44} aria-hidden className="text-stone-400" />
          </div>
        )}
        {/* hover CTA 滑入（纯装饰提示，整卡即链接，aria-hidden 避免重复读屏） */}
        <span
          aria-hidden
          className="pointer-events-none absolute inset-x-0 bottom-0 flex translate-y-full items-center justify-center bg-linear-to-t from-stone-900/60 to-transparent px-3 py-2.5 text-sm font-medium text-white opacity-0 transition-all duration-200 group-hover:translate-y-0 group-hover:opacity-100"
        >
          进入课程
        </span>
      </motion.div>

      {/* 信息区：标题 2 行截断 + meta 行（可空字段缺失时省略对应项） */}
      <div className="flex flex-1 flex-col gap-2 p-4">
        <h3 className="line-clamp-2 font-display text-[15px] font-semibold leading-snug text-text">
          {course.title}
        </h3>
        <div className="mt-auto flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted tabular-nums">
          {course.instructorName ? (
            <span className="inline-flex items-center gap-1">
              <User size={13} aria-hidden className="text-subtle" />
              {course.instructorName}
            </span>
          ) : null}
          {course.duration ? (
            <span className="inline-flex items-center gap-1">
              <Clock size={13} aria-hidden className="text-subtle" />
              {course.duration}
            </span>
          ) : null}
          {course.rating != null ? (
            <span className="inline-flex items-center gap-1 text-text">
              <Star size={13} weight="fill" aria-hidden className="text-brand" />
              {course.rating.toFixed(1)}
            </span>
          ) : null}
          <span className="inline-flex items-center gap-1">
            <Users size={13} aria-hidden className="text-subtle" />
            {course.learningCount} 人学习
          </span>
        </div>
      </div>
    </Link>
  );
}
