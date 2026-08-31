"use client";

/**
 * 我的课程页（路由 /my-courses；2026-08-31 用户拍板新增）
 *
 * 结构：页头（页眉 + 已购数量）→ 已购课程网格（复用 CourseCard，已购徽章态）。
 * 数据源 J1 getMyCourses（与导航栏用户菜单「我的课程」入口对应；queryKey 与个人中心
 * 共用 ["my-courses"]，购买成功后失效即时刷新）。四态全覆盖（设计 §1.7）：
 * Loading 骨架 / Empty 空态（引导去课程中心）/ Error 横幅+重试 / 正常态。
 *
 * AuthGate 客户端守卫（与个人中心同款）：静默续期窗口渲染同形骨架，续期失败开登录弹窗兜底；
 * middleware 已把 /my-courses 纳入受保护前缀（游客直引登录页）。
 *
 * 动效（design-taste 增强 2026-08-31）：卡片网格交错入场（easeOutQuint），
 * prefers-reduced-motion 下静态呈现。
 */
import { useQuery } from "@tanstack/react-query";
import { motion, useReducedMotion } from "motion/react";
import { useMemo } from "react";
import { AuthGate } from "@/components/auth/auth-gate";
import { CourseCard } from "@/components/course-card";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { getMyCourses } from "@/lib/api";

/** 卡片入场缓动（与课程中心同款 easeOutQuint：快起缓停） */
const EASE_OUT_QUINT = [0.22, 1, 0.36, 1] as [number, number, number, number];

/** 课程网格骨架：与课程列表页同形（灰块脉冲，设计 §1.7 Loading） */
export function CoursesSkeleton() {
  return (
    <div
      data-testid="courses-skeleton"
      className="mt-6 grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3"
      aria-busy="true"
    >
      {Array.from({ length: 6 }, (_, index) => (
        <div key={index} className="aspect-video animate-pulse rounded-2xl bg-surface-2" />
      ))}
    </div>
  );
}

/** 我的课程内容组件（AuthGate 守卫内渲染，登录态恒真） */
function MyCoursesContent() {
  const reduceMotion = useReducedMotion() ?? true;
  const coursesQuery = useQuery({ queryKey: ["my-courses"], queryFn: getMyCourses });
  // 空态兜底用 useMemo 稳定引用，避免空数组字面量每次渲染新建导致依赖变化
  const courses = useMemo(() => coursesQuery.data ?? [], [coursesQuery.data]);

  return (
    <div className="mx-auto w-full max-w-6xl px-6 pb-20">
      {/* 页头：页眉 + 数量（与课程中心页头同构） */}
      <div className="flex flex-wrap items-end justify-between gap-4 py-10">
        <h1 className="font-display text-[30px] leading-[1.25] font-bold text-text">我的课程</h1>
        {coursesQuery.isSuccess ? (
          <p className="text-sm text-muted tabular-nums">已购 {courses.length} 门课程</p>
        ) : null}
      </div>

      {/* body 四态：Loading / Error / 空态 / 正常态 */}
      {coursesQuery.isPending ? (
        <CoursesSkeleton />
      ) : coursesQuery.isError ? (
        <SectionError onRetry={() => void coursesQuery.refetch()} />
      ) : courses.length === 0 ? (
        <EmptyState title="还没有购买课程" actionLabel="去课程中心看看" actionHref="/courses" />
      ) : (
        <motion.div
          initial={reduceMotion ? false : "hidden"}
          animate="show"
          variants={{
            hidden: {},
            show: { transition: { staggerChildren: 0.04, delayChildren: 0.04 } },
          }}
          className="grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3"
        >
          {courses.map((course) => (
            <motion.div
              key={course.id}
              variants={{
                hidden: { opacity: 0, y: 14 },
                show: { opacity: 1, y: 0, transition: { duration: 0.42, ease: EASE_OUT_QUINT } },
              }}
            >
              <CourseCard course={course} purchased />
            </motion.div>
          ))}
        </motion.div>
      )}
    </div>
  );
}

/**
 * 我的课程路由组件：外层 AuthGate 客户端守卫（受保护路由三态承接，见文件头注释）
 */
export default function MyCoursesPage() {
  return (
    <AuthGate
      fallback={
        <div className="mx-auto w-full max-w-6xl px-6 pb-20">
          <CoursesSkeleton />
        </div>
      }
    >
      <MyCoursesContent />
    </AuthGate>
  );
}
