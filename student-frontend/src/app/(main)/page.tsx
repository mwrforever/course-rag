"use client";

import { ArrowRight, Books, ChatCircleText } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { motion, useReducedMotion } from "motion/react";
import Link from "next/link";
import { AiBadge } from "@/components/ai-badge";
import { CourseCard } from "@/components/course-card";
import { EmptyState } from "@/components/empty-state";
import { getMyCourses, getSessions } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { formatRelativeTime } from "@/lib/time";

/** 资料库入口条跨度 → Tailwind 列类（类名必须字面量，禁止动态拼接） */
const LIBRARY_SPAN_CLASS: Record<1 | 2 | 3 | 4, string> = {
  1: "col-span-1",
  2: "col-span-2",
  3: "col-span-3",
  4: "col-span-4",
};

/**
 * 计算资料库入口条应占列数（补齐当前行剩余列，cell 数=课程数+1 且不产生空 cell，设计 §1.5.1）
 *
 * 摆放规则：首卡 2x2 占行 1-2 的列 1-2；其余课程卡按行主序 1x1 依次摆放；
 * 入口条紧随其后，span 等于其落点行的剩余列数（1/2/3/4），保证网格无空洞。
 *
 * @param courseCount 课程数（仅在 ≥2 的网格模式调用）
 * @returns 入口条应占列数
 */
export function librarySpan(courseCount: number): 1 | 2 | 3 | 4 {
  let free = 2; // 行 1 剩余列（c3、c4）
  let row = 1;
  for (let i = 0; i < courseCount - 1; i += 1) {
    free -= 1;
    if (free === 0) {
      row += 1;
      // 行 2 仍被首卡占用列 1-2，故剩余 2 列；行 3 起整行 4 列
      free = row === 2 ? 2 : 4;
    }
  }
  // 循环内归零后立即重置为 ≥2，末次迭代后必 ≥1，落在 [1,4] 区间
  return free as 1 | 2 | 3 | 4;
}

/** 课程卡入场动效参数（设计 §1.6：300ms、stagger 60ms、ease-out） */
const CELL_TRANSITION = { duration: 0.3, ease: "easeOut" } as const;

/** hero 背景：teal-50 → white → stone-50 对角渐变 + 细等高线纹理（低对比度装饰） */
const HERO_BACKGROUND = [
  "radial-gradient(circle at 72% 30%, transparent 0, var(--color-brand-light) 56px, transparent 57px)",
  "radial-gradient(circle at 72% 30%, transparent 0, var(--color-brand-light) 112px, transparent 113px)",
  "radial-gradient(circle at 72% 30%, transparent 0, var(--color-brand-light) 168px, transparent 169px)",
  "linear-gradient(135deg, var(--color-brand-light) 0%, var(--color-surface) 55%, var(--color-bg) 100%)",
].join(",");

/**
 * 区块错误横幅（设计 §1.7 Error：danger-soft 底 + 文案 + 重试）
 * @param onRetry 重试回调（对应查询 refetch）
 */
function SectionError({ onRetry }: { onRetry: () => void }) {
  return (
    <div
      role="alert"
      className="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
    >
      <p className="text-sm text-danger">服务暂时不可用，请稍后重试</p>
      <button
        type="button"
        onClick={onRetry}
        className="shrink-0 rounded-xl border border-danger/30 bg-surface px-3 py-1.5 text-sm font-medium text-danger transition-colors hover:bg-danger/10 focus-visible:ring-2 focus-visible:ring-danger"
      >
        重试
      </button>
    </div>
  );
}

/** 课程网格骨架：与最终布局同形（首卡 2x2 + 4 个 1x1 + 资料库条），灰块脉冲（设计 §1.7 Loading） */
function CoursesSkeleton() {
  return (
    <div
      data-testid="courses-skeleton"
      className="grid grid-cols-1 items-start gap-5 md:grid-cols-4"
      aria-busy="true"
    >
      <div className="col-span-1 aspect-[4/3] animate-pulse rounded-2xl bg-surface-2 md:col-span-2 md:row-span-2" />
      <div className="aspect-video animate-pulse rounded-2xl bg-surface-2" />
      <div className="aspect-video animate-pulse rounded-2xl bg-surface-2" />
      <div className="aspect-video animate-pulse rounded-2xl bg-surface-2" />
      <div className="aspect-video animate-pulse rounded-2xl bg-surface-2" />
      <div className="h-16 animate-pulse rounded-2xl bg-surface-2" />
    </div>
  );
}

/** 会话列表骨架：5 行灰条脉冲（与正常列表行同形，设计 §1.7） */
function SessionsSkeleton() {
  return (
    <ul data-testid="sessions-skeleton" className="space-y-3" aria-busy="true">
      {Array.from({ length: 5 }, (_, index) => (
        <li key={index} className="h-12 animate-pulse rounded-xl bg-surface-2" />
      ))}
    </ul>
  );
}

/** 通用资料库入口条：补齐 Bento 行尾的资料入口（cell 数=课程数+1） */
function LibraryEntry() {
  return (
    <Link
      href="/courses"
      className="flex h-full items-center gap-4 rounded-2xl border border-dashed border-border bg-surface-2/60 px-5 py-5 transition-colors hover:border-brand/40 hover:bg-brand-light focus-visible:ring-2 focus-visible:ring-brand"
    >
      <Books size={22} aria-hidden className="shrink-0 text-brand" />
      <span className="min-w-0 flex-1">
        <span className="block text-[15px] font-medium text-text">通用资料库</span>
        <span className="block text-xs text-muted">公共学习资料，随时检索</span>
      </span>
      <ArrowRight size={18} aria-hidden className="shrink-0 text-muted" />
    </Link>
  );
}

/**
 * 首页（设计 §1.5.1，全 CSR）
 *
 * 结构：Hero 不对称分栏（问候 + 主 CTA + AI 徽标呼吸浮标）→ 我的课程 Bento 橱窗
 * （首卡 2x2 + 1x1 卡 + 资料库入口条，课程 ≤1 退化单卡）→ 最近会话（最多 5 条，
 * 相对时间 + 继续跳转）→ Footer。
 *
 * 四态全覆盖（设计 §1.7）：Loading 骨架 / Empty 空态 / Error 横幅+重试 / 正常态，
 * 课程与会话两区块各自独立状态机互不影响。
 * 数据来自 react-query：getMyCourses 全量 + getSessions(1,5) 最近会话。
 */
export default function HomePage() {
  const { user } = useAuth();
  // reduced-motion 命中或检测不可用 → 入场 stagger 直接渲染终态（不挂 initial）
  const reduceMotion = useReducedMotion() ?? true;

  const coursesQuery = useQuery({ queryKey: ["my-courses"], queryFn: getMyCourses });
  // 最近会话：J6 首页取第一页前 5 条
  const sessionsQuery = useQuery({
    queryKey: ["recent-sessions"],
    queryFn: () => getSessions(1, 5),
  });

  const courses = coursesQuery.data ?? [];
  const sessions = sessionsQuery.data?.records ?? [];

  return (
    <div>
      {/* ===== Hero：问候 + 主 CTA + AI 助教徽标，不对称分栏（高约 420px） ===== */}
      <section className="relative overflow-hidden">
        {/* 背景：teal-50 → white → stone-50 对角渐变 + 细等高线纹理 */}
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0"
          style={{ backgroundImage: HERO_BACKGROUND }}
        />
        <div className="relative mx-auto grid w-full max-w-6xl grid-cols-1 items-center gap-10 px-6 py-20 md:grid-cols-2 md:min-h-[420px] md:py-0">
          <div>
            <h1 className="font-display text-[44px] font-bold leading-[1.15] text-text">
              你好，{user?.displayName || "同学"}
            </h1>
            <p className="mt-4 text-[17px] leading-relaxed text-muted">继续探索你的课程</p>
            <div className="mt-9 flex flex-wrap items-center gap-3">
              {/* 主 CTA：开始提问 → 对话页 */}
              <Link
                href="/chat"
                className="inline-flex items-center gap-2 rounded-xl bg-brand px-5 py-2.5 text-[15px] font-medium text-white transition-all hover:bg-brand-strong active:scale-[0.98] active:-translate-y-px focus-visible:ring-2 focus-visible:ring-brand"
              >
                开始提问
                <ArrowRight size={16} aria-hidden />
              </Link>
              {/* 次级 CTA：浏览课程 → 课程列表页 */}
              <Link
                href="/courses"
                className="inline-flex items-center gap-2 rounded-xl border border-border bg-surface px-5 py-2.5 text-[15px] font-medium text-muted transition-colors hover:border-brand/40 hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
              >
                浏览课程
              </Link>
            </div>
          </div>
          {/* 右栏：AI 助教人格化徽标（缓慢呼吸，reduced-motion 静态） */}
          <div className="flex flex-col items-center justify-self-center gap-3 md:justify-self-end">
            <AiBadge />
            <span className="text-sm text-muted">AI 助教</span>
          </div>
        </div>
      </section>

      {/* ===== 我的课程：Bento 橱窗（首卡 2x2 + 资料库入口条，cell 数=课程数+1） ===== */}
      <section className="mx-auto w-full max-w-6xl px-6 pb-20">
        <h2 className="mb-5 font-display text-[22px] font-semibold leading-[1.3] text-text">
          我的课程
        </h2>

        {coursesQuery.isPending ? (
          <CoursesSkeleton />
        ) : coursesQuery.isError ? (
          <SectionError onRetry={() => void coursesQuery.refetch()} />
        ) : courses.length === 0 ? (
          <EmptyState
            title="还没有加入课程，请联系老师开通"
            actionLabel="先和 AI 助教聊聊"
            actionHref="/chat"
          />
        ) : courses.length === 1 ? (
          // 课程 ≤1 退化单卡居中：窄容器 + 居中网格，资料库入口条紧跟其下
          <div className="mx-auto grid max-w-md grid-cols-1 gap-5">
            <motion.div
              initial={reduceMotion ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={CELL_TRANSITION}
            >
              <CourseCard course={courses[0]} priority />
            </motion.div>
            <motion.div
              initial={reduceMotion ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ ...CELL_TRANSITION, delay: 0.06 }}
            >
              <LibraryEntry />
            </motion.div>
          </div>
        ) : (
          // 网格模式：首卡 2x2，其余 1x1，入口条按 librarySpan 补齐行尾
          <div className="grid grid-cols-1 items-start gap-5 md:grid-cols-4">
            {courses.map((course, index) => (
              <motion.div
                key={course.id}
                initial={reduceMotion ? false : { opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ ...CELL_TRANSITION, delay: index * 0.06 }}
                className={index === 0 ? "col-span-1 md:col-span-2 md:row-span-2" : "col-span-1"}
              >
                <CourseCard course={course} priority={index === 0} />
              </motion.div>
            ))}
            <motion.div
              key="library-entry"
              initial={reduceMotion ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ ...CELL_TRANSITION, delay: courses.length * 0.06 }}
              className={LIBRARY_SPAN_CLASS[librarySpan(courses.length)]}
            >
              <LibraryEntry />
            </motion.div>
          </div>
        )}
      </section>

      {/* ===== 最近会话：横向条目列表（最多 5 条，相对时间 + 继续跳转） ===== */}
      <section className="mx-auto w-full max-w-6xl px-6 pb-20">
        <h2 className="mb-5 font-display text-[22px] font-semibold leading-[1.3] text-text">
          最近会话
        </h2>

        {sessionsQuery.isPending ? (
          <SessionsSkeleton />
        ) : sessionsQuery.isError ? (
          <SectionError onRetry={() => void sessionsQuery.refetch()} />
        ) : sessions.length === 0 ? (
          <p className="text-sm text-muted">
            还没有会话记录，
            <Link href="/chat" className="text-brand-strong">
              开始对话
            </Link>
          </p>
        ) : (
          <ul className="space-y-3">
            {sessions.map((session) => (
              <li key={session.id}>
                <Link
                  href={`/chat/${session.id}`}
                  className="flex items-center gap-3 rounded-xl border border-border bg-surface px-4 py-3 shadow-sm transition-all duration-200 hover:border-brand/30 hover:shadow-md hover:shadow-teal-900/5 focus-visible:ring-2 focus-visible:ring-brand"
                >
                  <ChatCircleText size={18} aria-hidden className="shrink-0 text-brand" />
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
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ===== Footer：一行版权（stone-400 弱化） ===== */}
      <footer className="border-t border-border">
        <div className="mx-auto w-full max-w-6xl px-6 py-6 text-xs text-subtle">
          © 2026 课程助手
        </div>
      </footer>
    </div>
  );
}
