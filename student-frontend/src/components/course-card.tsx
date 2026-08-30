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
import type { PublicCourse, StudentCourse } from "@/lib/types";

/** 学科兜底映射表：category 关键词 → 学科图标 + 低饱和渐变（设计 §1.5.1 无封面兜底） */
const CATEGORY_FALLBACKS: ReadonlyArray<{
  keywords: readonly string[];
  icon: Icon;
  gradient: string;
}> = [
  {
    keywords: ["计算机", "编程", "软件", "开发"],
    icon: Code,
    gradient: "from-subject-code-start to-subject-code-end",
  },
  {
    keywords: ["数学", "统计"],
    icon: MathOperations,
    gradient: "from-subject-math-start to-subject-math-end",
  },
  {
    keywords: ["英语", "外语", "语言"],
    icon: GlobeHemisphereWest,
    gradient: "from-subject-lang-start to-subject-lang-end",
  },
  {
    keywords: ["物理", "化学", "生物", "科学"],
    icon: Flask,
    gradient: "from-subject-science-start to-subject-science-end",
  },
  {
    keywords: ["经济", "管理", "商"],
    icon: Briefcase,
    gradient: "from-subject-business-start to-subject-business-end",
  },
  {
    keywords: ["艺术", "音乐", "设计"],
    icon: Palette,
    gradient: "from-subject-art-start to-subject-art-end",
  },
];

/** 默认兜底：未知/空 category（brand-light 低饱和渐变 + 书本图标，末端收 semantic 层 surface-2） */
const DEFAULT_FALLBACK: { icon: Icon; gradient: string } = {
  icon: BookOpen,
  gradient: "from-brand-light to-surface-2",
};

/**
 * 解析课程学科兜底（关键词包含匹配，防御后端分类枚举变化）
 *
 * 导出供课程工作台 Hero 封面复用（无封面时同款学科渐变兜底，避免重复映射表）。
 * @param category 课程分类（可空，null 走默认兜底）
 * @returns 兜底学科图标与渐变类名
 */
export function coverFallback(category: string | null): { icon: Icon; gradient: string } {
  const cat = category ?? "";
  for (const entry of CATEGORY_FALLBACKS) {
    if (entry.keywords.some((keyword) => cat.includes(keyword))) {
      return { icon: entry.icon, gradient: entry.gradient };
    }
  }
  return DEFAULT_FALLBACK;
}

/**
 * 课程价格格式化（契约 H.2.1：单位元、直接展示、≤2 位小数去尾零）
 *
 * 导出供课程工作台 Hero 价格区复用（与 coverFallback 同一导出复用惯例）。
 * @param price 课程价格（元，BigDecimal→number；0/null 为免费）
 * @returns 「¥299」/「¥299.5」/「¥299.55」形态文本；免费（0/null）返回 null 由调用方渲染「免费」
 */
export function formatCoursePrice(price: number | null): string | null {
  // 免费：0 或后端未下发（null）——契约 H.2.1 免费判定口径
  if (price == null || price === 0) {
    return null;
  }
  // 两位小数定精度后去尾零：299.00→299 / 299.50→299.5 / 299.55→299.55
  const fixed = price.toFixed(2).replace(/\.?0+$/, "");
  return `¥${fixed}`;
}

/** 课程卡 props */
export interface CourseCardProps {
  /** 课程数据：公开课程（首页/课堂页）/ 我的课程（个人中心），字段子集兼容 */
  course: PublicCourse | StudentCourse;
  /** 首屏 LCP 优化：首卡封面高优先级加载 */
  priority?: boolean;
  /** 已购标记（登录用户经我的课程交叉判定；未登录不传不显示——契约 H.2.1） */
  purchased?: boolean;
}

/**
 * 课程卡（设计 §1.5.1）
 *
 * 结构：16:9 封面（next/image，MinIO remote loader；无封面或加载失败时按 category
 * 映射低饱和渐变 + 学科图标兜底）+ 标题 2 行截断 + meta 行（讲师/课时/星级/学习人数，
 * 数字 tabular-nums，可空字段按需省略）。
 *
 * 动效（设计 §1.6）：hover 封面 scale(1.02) + 卡片阴影抬升 + 「进入课程」CTA 滑入（200ms）；
 * 铁律只动画 transform/opacity，卡片 Link 过渡收窄为 transform/opacity，
 * border/阴影等装饰变化瞬时生效（简单处理，不做伪元素）；CSS 侧 hover 均由
 * motion-reduce: 变体覆盖（reduced-motion 下无过渡、CTA 常显）；
 * prefers-reduced-motion 或检测不可用时 motion whileHover 缩放静态。
 *
 * 封面错误兜底实现：next/image 不支持 onError，error 事件不冒泡但走捕获阶段，
 * 由封面容器 onErrorCapture 接住底层 img 的 error 后切换兜底渐变。
 */
export function CourseCard({ course, priority = false, purchased = false }: CourseCardProps) {
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
      className="group flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-surface shadow-xs transition-[transform,opacity] duration-200 motion-reduce:transition-none hover:-translate-y-1 hover:border-brand/30 hover:shadow-lg hover:shadow-brand/10 focus-visible:ring-2 focus-visible:ring-brand"
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
            <FallbackIcon size={44} aria-hidden className="text-subtle" />
          </div>
        )}
        {/* 电商语义：封面左上角分类徽章（overlay 遮罩色 + 毛玻璃，任何封面均可读） */}
        {course.category ? (
          <span
            aria-hidden
            className="absolute top-2.5 left-2.5 rounded-full bg-overlay px-2.5 py-0.5 text-[11px] font-medium text-white backdrop-blur-sm"
          >
            {course.category}
          </span>
        ) : null}
        {/* 已购徽章：登录用户已购课程标记（与分类徽章对角呼应，契约 H.2.1 替代原「已加入」语义） */}
        {purchased ? (
          <span
            aria-hidden
            className="absolute top-2.5 right-2.5 rounded-full bg-brand/90 px-2.5 py-0.5 text-[11px] font-medium text-white backdrop-blur-sm"
          >
            已购
          </span>
        ) : null}
        {/* hover CTA 滑入（纯装饰提示，整卡即链接，aria-hidden 避免重复读屏）；
            只动画 transform/opacity；reduced-motion 下常显且无过渡（motion-reduce: 变体） */}
        <span
          aria-hidden
          className="pointer-events-none absolute inset-x-0 bottom-0 flex translate-y-full items-center justify-center bg-linear-to-t from-overlay to-transparent px-3 py-2.5 text-sm font-medium text-white opacity-0 transition-[transform,opacity] duration-200 motion-reduce:translate-y-0 motion-reduce:opacity-100 motion-reduce:transition-none group-hover:translate-y-0 group-hover:opacity-100"
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
        {/* 价格行：未购课程展示价格（元，去尾零），免费显示「免费」；已购课程由封面徽章标记（契约 H.2.1） */}
        {!purchased ? (
          <p className="text-[15px] font-semibold text-brand-strong tabular-nums">
            {formatCoursePrice(course.price) ?? "免费"}
          </p>
        ) : null}
      </div>
    </Link>
  );
}
