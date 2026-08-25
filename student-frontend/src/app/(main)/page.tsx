"use client";

/**
 * 首页（UI 重构 2026-08-25：电商风 + kimi 蓝系，全 CSR）
 *
 * 结构（用户拍板：首页为电商网页形态，kimi 布局仅课程助手）：
 * - Hero：问候 + 主 CTA + AI 徽标呼吸浮标，品牌径向光晕 + 同心环装饰
 * - 分类筛选条：横向 chip（全部 + 各分类计数），选中过滤下方推荐课程
 * - 推荐课程：电商卡片网格（封面分类徽章 + hover 上浮 + 滚动 stagger 进场）
 * - 通用资料库入口横幅 → 最近会话（滚动逐条进场）→ Footer
 *
 * 滚动动效：motion whileInView（once 进入即定格、-40px 视口外触发），
 * 仅动画 transform/opacity；prefers-reduced-motion / 检测不可用全降级静态。
 * 四态全覆盖：Loading 骨架 / Empty 空态 / Error 横幅+重试 / 正常态。
 */
import { ArrowRight, Books, ChatCircleText } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { motion, useReducedMotion, type Variants } from "motion/react";
import Link from "next/link";
import { useMemo, useState } from "react";
import { AiBadge } from "@/components/ai-badge";
import { CourseCard } from "@/components/course-card";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { getMyCourses, getSessions } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { formatRelativeTime } from "@/lib/time";

/** 课程卡滚动 stagger 步进（毫秒，逐卡错峰进场） */
const CELL_STAGGER_MS = 70;
/** 区块滚动进场动画（仅 transform/opacity，-40px 视差预触发） */
const revealVariants: Variants = {
  hidden: { opacity: 0, y: 18 },
  visible: { opacity: 1, y: 0 },
};
/** 滚动进场 transition（含 stagger 容器编排） */
const sectionTransition = { duration: 0.45, ease: "easeOut" } as const;

/**
 * Hero 品牌径向光晕背景（brand 蓝系：右上光晕 + 同心圆环 + 暖白对角渐变）
 */
const HERO_BACKGROUND = [
  "radial-gradient(circle at 74% 28%, transparent 0, var(--color-brand-light) 60px, transparent 61px)",
  "radial-gradient(circle at 74% 28%, transparent 0, var(--color-brand-light) 120px, transparent 121px)",
  "radial-gradient(circle at 74% 28%, transparent 0, var(--color-brand-light) 180px, transparent 181px)",
  "radial-gradient(600px 400px at 8% 90%, var(--color-brand-light) 0%, transparent 60%)",
  "linear-gradient(135deg, var(--color-brand-light) 0%, var(--color-surface) 50%, var(--color-bg) 100%)",
].join(",");

/** 课程网格骨架：与最终布局同形（6 张 16:9 灰块，脉冲） */
function CoursesSkeleton() {
  return (
    <div
      data-testid="courses-skeleton"
      className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
      aria-busy="true"
    >
      {Array.from({ length: 6 }, (_, index) => (
        <div key={index} className="aspect-video animate-pulse rounded-2xl bg-surface-2" />
      ))}
    </div>
  );
}

/** 会话列表骨架：5 行灰条脉冲（与最终列表同形） */
function SessionsSkeleton() {
  return (
    <ul data-testid="sessions-skeleton" className="space-y-3" aria-busy="true">
      {Array.from({ length: 5 }, (_, index) => (
        <li key={index} className="h-12 animate-pulse rounded-xl bg-surface-2" />
      ))}
    </ul>
  );
}

/** 区块标题：品牌渐变竖条 + 标题文字（电商 section 语义） */
function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="flex items-center gap-2.5 font-display text-[22px] font-semibold leading-[1.3] text-text">
      <span aria-hidden className="bg-gradient-ai h-5 w-1 rounded-full" />
      {children}
    </h2>
  );
}

/**
 * 首页（电商风 + 分类筛选 + 滚动动效）
 */
export default function HomePage() {
  const { user } = useAuth();
  // reduced-motion 或检测不可用 → 滚动动效全静态（可访问性优先）
  const reduceMotion = useReducedMotion() ?? true;

  const coursesQuery = useQuery({ queryKey: ["my-courses"], queryFn: getMyCourses });
  // 最近会话：J6 首页取第一页前 5 条
  const sessionsQuery = useQuery({
    queryKey: ["recent-sessions"],
    queryFn: () => getSessions(1, 5),
  });

  // 空态兜底用 useMemo 稳定引用（避免空数组字面量每次渲染新建导致依赖变化）
  const courses = useMemo(() => coursesQuery.data ?? [], [coursesQuery.data]);
  const sessions = sessionsQuery.data?.records ?? [];

  // 分类筛选项：由课程数据聚合（全部 + 各分类计数），电商「分类筛选」语义
  const [selected, setSelected] = useState("全部");
  const { categories, filtered } = useMemo(() => {
    const counts = new Map<string, number>();
    for (const course of courses) {
      const key = course.category?.trim() || "未分类";
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const list = Array.from(counts.entries()).map(([name, count]) => ({ name, count }));
    return {
      categories: list,
      filtered:
        selected === "全部"
          ? courses
          : courses.filter((c) => (c.category?.trim() || "未分类") === selected),
    };
  }, [courses, selected]);

  return (
    <div>
      {/* ===== Hero：问候 + 主 CTA + AI 徽标，品牌光晕背景（电商首屏语义） ===== */}
      <section className="relative overflow-hidden">
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0"
          style={{ backgroundImage: HERO_BACKGROUND }}
        />
        <motion.div
          className="relative mx-auto grid w-full max-w-6xl grid-cols-1 items-center gap-10 px-6 py-20 md:min-h-[380px] md:grid-cols-2 md:py-0"
          initial={reduceMotion ? false : { opacity: 0, y: 14 }}
          animate={{ opacity: 1, y: 0 }}
          transition={reduceMotion ? undefined : sectionTransition}
        >
          <div>
            <h1 className="font-display text-[42px] font-bold leading-[1.15] text-text">
              <span>你好，{user?.displayName || "同学"}</span>
              <span className="text-gradient-ai">，继续探索</span>
            </h1>
            <p className="mt-4 text-[17px] leading-relaxed text-muted">
              课堂资料、AI 助教、对话溯源，都在一个地方
            </p>
            <div className="mt-9 flex flex-wrap items-center gap-3">
              {/* 主 CTA：开始提问 → 对话页（品牌渐变主按钮） */}
              <Link
                href="/chat"
                className="inline-flex items-center gap-2 rounded-full bg-gradient-ai px-6 py-3 text-[15px] font-medium text-white shadow-lg shadow-brand/30 transition-[transform,opacity] hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.98] motion-reduce:transition-none focus-visible:ring-2 focus-visible:ring-brand"
              >
                开始提问
                <ArrowRight size={16} aria-hidden />
              </Link>
              {/* 次级 CTA：浏览课堂 */}
              <Link
                href="/courses"
                className="inline-flex items-center gap-2 rounded-full border border-border bg-surface px-6 py-3 text-[15px] font-medium text-muted transition-colors hover:border-brand/40 hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
              >
                浏览课堂
              </Link>
            </div>
          </div>
          {/* 右栏：AI 助教人格化徽标（呼吸浮标，reduced-motion 静态） */}
          <div className="flex flex-col items-center justify-self-center gap-3 md:justify-self-end">
            <AiBadge />
            <span className="text-sm text-muted">AI 助教，随时可问</span>
          </div>
        </motion.div>
      </section>

      {/* ===== 推荐课程：分类筛选 + 电商卡片网格 ===== */}
      <section className="mx-auto w-full max-w-6xl px-6 pb-16">
        <SectionTitle>推荐课程</SectionTitle>

        {coursesQuery.isPending ? (
          <>
            <div className="mt-5 h-9 animate-pulse rounded-full bg-surface-2" />
            <div className="mt-5">
              <CoursesSkeleton />
            </div>
          </>
        ) : coursesQuery.isError ? (
          <div className="mt-6">
            <SectionError onRetry={() => void coursesQuery.refetch()} />
          </div>
        ) : courses.length === 0 ? (
          <EmptyState
            className="mt-8"
            title="还没有加入课程，请联系老师开通"
            actionLabel="先和 AI 助教聊聊"
            actionHref="/chat"
          />
        ) : (
          <>
            {/* 分类筛选条：横向可滚动 chip（全部 + 各分类计数），选中过滤；
                过滤器语义用按钮组 + aria-pressed（非页签，无 tabpanel 不滥用 tab 角色） */}
            <div
              role="group"
              aria-label="课程分类筛选"
              data-testid="category-filter"
              className="scrollbar-none -mx-1 mt-5 flex gap-2 overflow-x-auto px-1 pb-1"
            >
              {[{ name: "全部", count: courses.length }, ...categories].map((item) => {
                const active = item.name === selected;
                return (
                  <button
                    key={item.name}
                    type="button"
                    aria-pressed={active}
                    data-testid="category-chip"
                    onClick={() => setSelected(item.name)}
                    className={`shrink-0 rounded-full px-4 py-1.5 text-sm transition-all ${
                      active
                        ? "bg-brand text-white shadow-md shadow-brand/30"
                        : "border border-border bg-surface text-muted hover:border-brand/40 hover:text-brand-strong"
                    }`}
                  >
                    {item.name}
                    <span
                      className={`ml-1.5 text-xs tabular-nums ${active ? "text-white/80" : "text-subtle"}`}
                    >
                      {item.count}
                    </span>
                  </button>
                );
              })}
            </div>

            {/* 课程网格：滚动 stagger 进场 + hover 上浮（CourseCard 自带） */}
            <motion.div
              key={selected}
              variants={
                reduceMotion
                  ? undefined
                  : {
                      hidden: {},
                      visible: { transition: { staggerChildren: CELL_STAGGER_MS / 1000 } },
                    }
              }
              initial={reduceMotion ? false : "hidden"}
              whileInView={reduceMotion ? undefined : "visible"}
              viewport={{ once: true, margin: "-40px" }}
              className="mt-5 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
            >
              {filtered.map((course) => (
                <motion.div
                  key={course.id}
                  variants={reduceMotion ? undefined : revealVariants}
                  className="h-full"
                >
                  <CourseCard course={course} />
                </motion.div>
              ))}
            </motion.div>

            {/* 通用资料库入口横幅：全宽功能卡（电商 banner 语义） */}
            <motion.div
              initial={reduceMotion ? false : { opacity: 0, y: 14 }}
              whileInView={reduceMotion ? undefined : { opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-40px" }}
              transition={reduceMotion ? undefined : sectionTransition}
              className="mt-6"
            >
              <Link
                href="/courses"
                className="hover:border-brand/40 flex items-center gap-4 rounded-2xl border border-border bg-surface px-6 py-5 transition-all hover:-translate-y-0.5 hover:shadow-md focus-visible:ring-2 focus-visible:ring-brand"
              >
                <span className="bg-gradient-ai grid size-10 shrink-0 place-items-center rounded-xl text-white shadow-md shadow-brand/30">
                  <Books size={20} weight="fill" aria-hidden />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-[15px] font-medium text-text">通用资料库</span>
                  <span className="block text-xs text-muted">公共学习资料，随时检索</span>
                </span>
                <ArrowRight size={18} aria-hidden className="shrink-0 text-muted" />
              </Link>
            </motion.div>
          </>
        )}
      </section>

      {/* ===== 最近会话：横向条目列表（最多 5 条，相对时间 + 继续跳转） ===== */}
      <section className="mx-auto w-full max-w-6xl px-6 pb-20">
        <SectionTitle>最近会话</SectionTitle>

        {sessionsQuery.isPending ? (
          <div className="mt-5">
            <SessionsSkeleton />
          </div>
        ) : sessionsQuery.isError ? (
          <div className="mt-6">
            <SectionError onRetry={() => void sessionsQuery.refetch()} />
          </div>
        ) : sessions.length === 0 ? (
          <p className="mt-5 text-sm text-muted">
            还没有会话记录，
            <Link href="/chat" className="font-medium text-brand-strong">
              开始对话
            </Link>
          </p>
        ) : (
          <motion.ul
            variants={
              reduceMotion
                ? undefined
                : { hidden: {}, visible: { transition: { staggerChildren: 0.06 } } }
            }
            initial={reduceMotion ? false : "hidden"}
            whileInView={reduceMotion ? undefined : "visible"}
            viewport={{ once: true, margin: "-40px" }}
            className="mt-5 space-y-3"
          >
            {sessions.map((session) => (
              <motion.li key={session.id} variants={reduceMotion ? undefined : revealVariants}>
                <Link
                  href={`/chat/${session.id}`}
                  className="flex items-center gap-3 rounded-xl border border-border bg-surface px-4 py-3 shadow-xs transition-[transform,opacity] duration-200 motion-reduce:transition-none hover:-translate-y-0.5 hover:border-brand/30 hover:shadow-md focus-visible:ring-2 focus-visible:ring-brand"
                >
                  <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-brand-soft text-brand">
                    <ChatCircleText size={17} aria-hidden />
                  </span>
                  <span className="min-w-0 flex-1 truncate text-[15px] text-text">
                    {session.title}
                  </span>
                  {/* 相对时间：无 lastMessageAt 时回退 createdAt */}
                  <time className="shrink-0 text-xs text-subtle tabular-nums">
                    {formatRelativeTime(session.lastMessageAt ?? session.createdAt)}
                  </time>
                  <span className="inline-flex shrink-0 items-center gap-1 text-sm font-medium text-brand-strong">
                    继续
                    <ArrowRight size={14} aria-hidden />
                  </span>
                </Link>
              </motion.li>
            ))}
          </motion.ul>
        )}
      </section>

      {/* ===== Footer：一行版权 ===== */}
      <footer className="border-t border-border">
        <div className="mx-auto w-full max-w-6xl px-6 py-6 text-xs text-subtle">
          © 2026 课程助手
        </div>
      </footer>
    </div>
  );
}
