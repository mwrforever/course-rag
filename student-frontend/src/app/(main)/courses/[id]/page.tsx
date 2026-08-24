"use client";

import { ArrowDown, Clock, Sparkle, Star, User, Users } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { ChunkContextDrawer } from "@/components/chunk-context-drawer";
import { ChunkItem } from "@/components/chunk-item";
import { coverFallback } from "@/components/course-card";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { ApiError, getMaterials, getMyCourses } from "@/lib/api";
import type { MaterialChunk } from "@/lib/types";

/** 分批渲染批次大小：首屏 50 条 + 「加载更多」逐批揭示（设计补记 G10） */
const BATCH_SIZE = 50;

/** 工作台骨架：Hero 块 + 5 行资料列表灰条脉冲（与最终布局同形，设计 §1.7） */
function WorkbenchSkeleton() {
  return (
    <div
      data-testid="workbench-skeleton"
      className="mx-auto w-full max-w-6xl px-6 py-10"
      aria-busy="true"
    >
      <div className="grid items-start gap-8 md:grid-cols-[360px_1fr]">
        <div className="aspect-[4/3] animate-pulse rounded-2xl bg-surface-2" />
        <div className="space-y-3">
          <div className="h-8 w-2/3 animate-pulse rounded-xl bg-surface-2" />
          <div className="h-4 w-1/2 animate-pulse rounded-lg bg-surface-2" />
          <div className="h-9 w-64 animate-pulse rounded-xl bg-surface-2" />
        </div>
      </div>
      <div data-testid="materials-skeleton" className="mt-10 space-y-3">
        {Array.from({ length: 5 }, (_, index) => (
          <div key={index} className="h-20 animate-pulse rounded-2xl bg-surface-2" />
        ))}
      </div>
    </div>
  );
}

/**
 * 课程工作台（设计 §1.5.3 路由 /courses/[id]，全 CSR）
 *
 * 结构：课程 Hero（左封面 4:3 + 右信息 + 问 AI 助教 CTA + 浏览资料锚点）→
 * 课程资料（J2 分片列表，分批渲染首屏 50 + 加载更多）→ 上下文抽屉（J4）。
 *
 * 403 专属态（设计 §1.7）：materials 接口 403（未选课）时渲染专属引导页
 * 「联系老师加入这门课程」+ 返回按钮，替代全部页面内容。
 * 时序修正（carry1）：未加入的课程不在我的课程列表（course 判空会命中），
 * 故「是否 403」先于「course 缺失」判定，避免误报「课程不存在」；
 * course 真不存在（materials 404）才落「课程不存在或已下架」。
 * 四态全覆盖：Loading 骨架 / Empty / Error 横幅+重试 / 正常态。
 */
export default function CourseWorkbenchPage() {
  const params = useParams<{ id: string }>();
  const courseId = params.id;
  // J1 全量课程中定位当前课程（复用首页同名缓存键，react-query 同 key 共享缓存）
  const coursesQuery = useQuery({ queryKey: ["my-courses"], queryFn: getMyCourses });
  const course = coursesQuery.data?.find((item) => item.id === courseId) ?? null;

  // J2 课程专属资料：未选课后端 403（code=403）→ 渲染专属引导态；
  // 不自动重试（403 重试无意义，瞬态错误由 Error 横幅的 [重试] 手动恢复，行为确定）
  const materialsQuery = useQuery({
    queryKey: ["course-materials", courseId],
    queryFn: () => getMaterials(courseId),
    retry: false,
  });
  const materials = materialsQuery.data ?? [];
  const isForbidden = materialsQuery.error instanceof ApiError && materialsQuery.error.code === 403;
  // 课程真不存在判据（carry1）：资料接口 404（或课程不在我的列表）→ 课程不存在/下架
  const isNotFound = materialsQuery.error instanceof ApiError && materialsQuery.error.code === 404;

  // J4 上下文抽屉选中分片（null = 抽屉关闭）+ 分批渲染可见条数
  const [selectedChunk, setSelectedChunk] = useState<MaterialChunk | null>(null);
  const [visibleCount, setVisibleCount] = useState(BATCH_SIZE);
  // 切换课程时重置分批进度
  useEffect(() => {
    setVisibleCount(BATCH_SIZE);
  }, [courseId]);

  // 课程与资料任一加载中 → 整页骨架（资料接口通常更慢，统一到资料就绪再出内容）
  if (coursesQuery.isPending || materialsQuery.isPending) {
    return <WorkbenchSkeleton />;
  }
  if (coursesQuery.isError) {
    return (
      <div className="mx-auto w-full max-w-6xl px-6 py-16">
        <SectionError onRetry={() => void coursesQuery.refetch()} />
      </div>
    );
  }
  if (isForbidden) {
    // 未选课 403 专属引导页（设计 §1.7：联系老师加入这门课程 + 返回按钮）。
    // carry1 时序修正：本判定先于 !course。未加入的课程不在我的课程列表，
    // 但 materials 403 证明课程存在只是未选，应引导联系老师而非报「课程不存在」
    return (
      <div className="mx-auto w-full max-w-6xl px-6 py-16">
        <EmptyState
          title="还没有加入这门课程，请联系老师开通"
          actionLabel="返回我的课程"
          actionHref="/courses"
        />
      </div>
    );
  }
  if (!course || isNotFound) {
    // J1 无此课程（直接输入 URL 或课程下架）；或资料接口 404（carry1：课程真不存在）
    return (
      <div className="mx-auto w-full max-w-6xl px-6 py-16">
        <EmptyState title="课程不存在或已下架" actionLabel="返回我的课程" actionHref="/courses" />
      </div>
    );
  }
  if (materialsQuery.isError) {
    return (
      <div className="mx-auto w-full max-w-6xl px-6 py-16">
        <SectionError onRetry={() => void materialsQuery.refetch()} />
      </div>
    );
  }

  const visibleMaterials = materials.slice(0, visibleCount);
  const remaining = materials.length - visibleCount;

  // Hero 无封面兜底：与 CourseCard 同款学科渐变（分类关键词映射）
  const { icon: FallbackIcon, gradient } = coverFallback(course.category);

  return (
    <div className="mx-auto w-full max-w-6xl px-6 pb-20">
      {/* ===== 课程 Hero：左封面（4:3 rounded-2xl）右信息 ===== */}
      <section className="grid items-start gap-8 py-10 md:grid-cols-[360px_1fr]">
        <div className="relative aspect-[4/3] overflow-hidden rounded-2xl bg-surface-2">
          {course.coverImage ? (
            <Image
              src={course.coverImage}
              alt={course.title}
              fill
              sizes="(max-width: 768px) 100vw, 360px"
              className="object-cover"
            />
          ) : (
            <div
              data-testid="hero-cover-fallback"
              className={`grid h-full w-full place-items-center bg-linear-to-br ${gradient}`}
            >
              <FallbackIcon size={52} aria-hidden className="text-stone-400" />
            </div>
          )}
        </div>
        <div>
          {course.category ? (
            <span className="inline-block rounded-full bg-brand-soft px-2.5 py-0.5 text-xs font-medium text-brand-strong">
              {course.category}
            </span>
          ) : null}
          <h1 className="mt-3 font-display text-[30px] leading-[1.25] font-bold text-text">
            {course.title}
          </h1>
          <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted tabular-nums">
            {course.instructorName ? (
              <span className="inline-flex items-center gap-1.5">
                <User size={15} aria-hidden className="text-subtle" />
                {course.instructorName}
              </span>
            ) : null}
            {course.duration ? (
              <span className="inline-flex items-center gap-1.5">
                <Clock size={15} aria-hidden className="text-subtle" />
                {course.duration} 课时
              </span>
            ) : null}
            {course.rating != null ? (
              <span className="inline-flex items-center gap-1.5 text-text">
                <Star size={15} weight="fill" aria-hidden className="text-brand" />
                {course.rating.toFixed(1)}
              </span>
            ) : null}
            <span className="inline-flex items-center gap-1.5">
              <Users size={15} aria-hidden className="text-subtle" />
              {course.learningCount} 人学习
            </span>
          </div>
          {/* 双 CTA：问 AI 助教（纯前端入口 → /chat?courseId={id}&course=课程名，D7
              上下文条面包屑 + carry3 返回按钮直达本课程）+ 浏览资料（锚点滚动） */}
          <div className="mt-6 flex flex-wrap gap-3">
            <Link
              href={`/chat?courseId=${course.id}&course=${encodeURIComponent(course.title)}`}
              className="inline-flex items-center gap-2 rounded-xl bg-brand px-5 py-2.5 text-[15px] font-medium text-white transition-colors hover:bg-brand-strong active:scale-[0.98] focus-visible:ring-2 focus-visible:ring-brand"
            >
              <Sparkle size={16} aria-hidden />问 AI 助教
            </Link>
            <Link
              href="#materials"
              className="inline-flex items-center gap-2 rounded-xl border border-border bg-surface px-5 py-2.5 text-[15px] font-medium text-muted transition-colors hover:border-brand/40 hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
            >
              浏览资料
              <ArrowDown size={15} aria-hidden />
            </Link>
          </div>
        </div>
      </section>

      {/* ===== 课程资料：J2 分片列表（分批渲染首屏 50 + 加载更多，G10） ===== */}
      <section id="materials" className="scroll-mt-24">
        <div className="mb-5 flex items-baseline justify-between gap-4 border-b border-border pb-4">
          <h2 className="font-display text-[22px] leading-[1.3] font-semibold text-text">
            课程资料
          </h2>
          <span className="text-xs text-subtle tabular-nums">共 {materials.length} 条</span>
        </div>

        {materials.length === 0 ? (
          <EmptyState title="这门课程还没有资料，稍后再来看看" />
        ) : (
          <>
            <ul className="space-y-3">
              {visibleMaterials.map((chunk) => (
                <li key={chunk.id}>
                  <ChunkItem chunk={chunk} onViewContext={setSelectedChunk} />
                </li>
              ))}
            </ul>
            {remaining > 0 ? (
              <div className="mt-6 text-center">
                <button
                  type="button"
                  onClick={() => setVisibleCount((count) => count + BATCH_SIZE)}
                  className="rounded-xl border border-brand/30 bg-surface px-6 py-2.5 text-sm font-medium text-brand-strong transition-colors hover:bg-brand-light focus-visible:ring-2 focus-visible:ring-brand"
                >
                  加载更多（剩余 {remaining} 条）
                </button>
              </div>
            ) : null}
          </>
        )}
      </section>

      {/* ===== J4 上下文抽屉（选中分片非空时滑入） ===== */}
      <ChunkContextDrawer chunk={selectedChunk} onClose={() => setSelectedChunk(null)} />
    </div>
  );
}
