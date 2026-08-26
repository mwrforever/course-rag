"use client";

import { ArrowDown, Clock, Lock, Sparkle, Star, User, Users } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { ChunkContextDrawer } from "@/components/chunk-context-drawer";
import { ChunkItem } from "@/components/chunk-item";
import { coverFallback } from "@/components/course-card";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { ApiError, getMaterials, getPublicCourses } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
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

/** 登录墙卡片：资料区未登录引导（点「去登录」打开全局登录弹窗） */
function LoginGate({ onLogin }: { onLogin: () => void }) {
  return (
    <div
      data-testid="login-gate"
      className="flex flex-col items-center gap-4 rounded-2xl border border-dashed border-border bg-surface-2/60 px-6 py-14 text-center"
    >
      <span className="bg-brand-soft grid size-12 place-items-center rounded-2xl text-brand">
        <Lock size={22} aria-hidden />
      </span>
      <div>
        <p className="text-[15px] font-medium text-text">登录后查看课程资料</p>
        <p className="mt-1 text-[13px] text-muted">学习资料仅对登录用户开放，登录后可继续提问</p>
      </div>
      <button
        type="button"
        onClick={onLogin}
        className="inline-flex items-center gap-2 rounded-full bg-brand px-6 py-2.5 text-sm font-medium text-white shadow-md shadow-brand/30 transition-[transform,opacity] hover:-translate-y-0.5 hover:bg-brand-strong active:translate-y-0 active:scale-[0.98] motion-reduce:transition-none focus-visible:ring-2 focus-visible:ring-brand"
      >
        去登录
      </button>
    </div>
  );
}

/**
 * 课程工作台（设计 §1.5.3 路由 /courses/[id]，全 CSR；公开化 2026-08-26 修订）
 *
 * 结构：课程 Hero（左封面 4:3 + 右公开信息/简介 + 问 AI 助教 CTA + 浏览资料锚点）→
 * 课程资料（J2 分片列表，登录后可见）→ 上下文抽屉（J4）。
 *
 * 登录门槛（用户拍板：点进详情页才需要登录）：
 * - 课程公开信息经公开接口（public-courses）渲染，未登录可浏览
 * - 未登录进入页面自动弹出登录窗（可关闭）；资料区渲染登录墙卡片
 * - 资料接口 J2 仅登录后请求（enabled）；未选课 403 → 专属引导「联系老师」
 * - 「问 AI 助教」未登录先登录（登录成功后继续跳转 /chat?courseId=）
 *
 * 时序修正（carry1）：公开数据源为权威课程全集，!course 即课程不存在/下架，
 * 不再依赖 materials 404 判空。四态全覆盖：Loading 骨架 / Empty / Error 横幅+重试 / 正常态。
 */
export default function CourseWorkbenchPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const courseId = params.id;
  const { isAuthenticated, isLoading, openLoginDialog } = useAuth();
  // 公开课程全集定位当前课程（与首页/课堂页同键共享缓存，未登录可访问）
  const coursesQuery = useQuery({ queryKey: ["public-courses"], queryFn: getPublicCourses });
  const course = coursesQuery.data?.find((item) => item.id === courseId) ?? null;

  // J2 课程专属资料：仅登录后请求；未选课后端 403（code=403）→ 专属引导态；
  // 不自动重试（403 重试无意义，瞬态错误由 Error 横幅的 [重试] 手动恢复，行为确定）
  const materialsQuery = useQuery({
    queryKey: ["course-materials", courseId],
    queryFn: () => getMaterials(courseId),
    enabled: isAuthenticated,
    retry: false,
  });
  const materials = materialsQuery.data ?? [];
  const isForbidden = materialsQuery.error instanceof ApiError && materialsQuery.error.code === 403;

  // J4 上下文抽屉选中分片（null = 抽屉关闭）+ 分批渲染可见条数
  const [selectedChunk, setSelectedChunk] = useState<MaterialChunk | null>(null);
  const [visibleCount, setVisibleCount] = useState(BATCH_SIZE);
  // 切换课程时重置分批进度
  useEffect(() => {
    setVisibleCount(BATCH_SIZE);
  }, [courseId]);

  // 登录门槛：未登录进入详情页自动弹出登录窗（可关闭继续浏览公开信息）。
  // once 语义 + 静默续期窗口（isLoading）内不弹——修复实证缺陷：登录用户 refresh
  // 完成前 isAuthenticated=false 会误弹窗挡住资料加载（E2E 工作台用例抓出）
  const loginGateShownRef = useRef(false);
  useEffect(() => {
    if (loginGateShownRef.current) {
      return;
    }
    if (!isAuthenticated && !isLoading) {
      loginGateShownRef.current = true;
      openLoginDialog();
    }
  }, [isAuthenticated, isLoading, openLoginDialog]);

  // 课程公开信息加载中 → 整页骨架（资料区等待时长已与控制解耦，见下）
  if (coursesQuery.isPending) {
    return <WorkbenchSkeleton />;
  }
  if (coursesQuery.isError) {
    return (
      <div className="mx-auto w-full max-w-6xl px-6 py-16">
        <SectionError onRetry={() => void coursesQuery.refetch()} />
      </div>
    );
  }
  if (!course) {
    // 公开数据源为权威（ACTIVE 课程全集）：不在列表即不存在/已下架
    return (
      <div className="mx-auto w-full max-w-6xl px-6 py-16">
        <EmptyState title="课程不存在或已下架" actionLabel="返回课程中心" actionHref="/courses" />
      </div>
    );
  }
  if (isForbidden) {
    // 未选课 403 专属引导页（设计 §1.7：联系老师加入这门课程 + 返回按钮）
    return (
      <div className="mx-auto w-full max-w-6xl px-6 py-16">
        <EmptyState
          title="还没有加入这门课程，请联系老师开通"
          actionLabel="返回课程中心"
          actionHref="/courses"
        />
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
  // 闭包安全取值：askAi 为组件内函数，TS 不保留外层 course 的 null 收窄，解构出非空值
  const courseIdNow = course.id;
  const courseTitleNow = course.title;

  /** 问 AI 助教入口：未登录先弹登录窗（成功后续跳），已登录直达对话页 */
  function askAi() {
    if (isAuthenticated) {
      router.push(`/chat?courseId=${courseIdNow}&course=${encodeURIComponent(courseTitleNow)}`);
    } else {
      openLoginDialog({
        afterLogin: () =>
          router.push(`/chat?courseId=${courseIdNow}&course=${encodeURIComponent(courseTitleNow)}`),
      });
    }
  }

  return (
    <div className="mx-auto w-full max-w-6xl px-6 pb-20">
      {/* ===== 课程 Hero：左封面（4:3 rounded-2xl）右信息 ===== */}
      <section className="grid items-start gap-8 py-10 md:grid-cols-[360px_1fr]">
        <div className="relative aspect-[4/3] overflow-hidden rounded-2xl bg-surface-2 shadow-lg shadow-brand/10 ring-1 ring-border">
          {course.coverImage ? (
            <Image
              src={course.coverImage}
              alt={course.title}
              fill
              priority
              sizes="(max-width: 768px) 100vw, 360px"
              className="object-cover"
            />
          ) : (
            <div
              data-testid="hero-cover-fallback"
              className={`grid h-full w-full place-items-center bg-linear-to-br ${gradient}`}
            >
              <FallbackIcon size={52} aria-hidden className="text-subtle" />
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
          {/* 课程简介：公开字段（description），未登录亦可浏览 */}
          {course.description ? (
            <p className="mt-3 max-w-xl text-[15px] leading-relaxed text-muted">
              {course.description}
            </p>
          ) : null}
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
          {/* 双 CTA：问 AI 助教（未登录先弹登录窗，D7 上下文条面包屑 + carry3 返回按钮直达本课程）
              + 浏览资料（锚点滚动） */}
          <div className="mt-6 flex flex-wrap gap-3">
            <button
              type="button"
              onClick={askAi}
              className="inline-flex items-center gap-2 rounded-full bg-brand px-5 py-2.5 text-[15px] font-medium text-white shadow-md shadow-brand/30 transition-[transform,opacity] hover:-translate-y-0.5 hover:bg-brand-strong active:translate-y-0 active:scale-[0.98] motion-reduce:transition-none focus-visible:ring-2 focus-visible:ring-brand"
            >
              <Sparkle size={16} aria-hidden />问 AI 助教
            </button>
            <Link
              href="#materials"
              className="inline-flex items-center gap-2 rounded-full border border-border bg-surface px-5 py-2.5 text-[15px] font-medium text-muted transition-colors hover:border-brand/40 hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
            >
              浏览资料
              <ArrowDown size={15} aria-hidden />
            </Link>
          </div>
        </div>
      </section>

      {/* ===== 课程资料：J2 分片列表（登录后可见；未登录渲染登录墙） ===== */}
      <section id="materials" className="scroll-mt-24">
        <div className="mb-5 flex items-baseline justify-between gap-4 border-b border-border pb-4">
          <h2 className="font-display text-[22px] leading-[1.3] font-semibold text-text">
            课程资料
          </h2>
          {isAuthenticated ? (
            <span className="text-xs text-subtle tabular-nums">共 {materials.length} 条</span>
          ) : null}
        </div>

        {!isAuthenticated ? (
          <LoginGate onLogin={() => openLoginDialog()} />
        ) : materialsQuery.isPending ? (
          <div data-testid="materials-skeleton" className="space-y-3" aria-busy="true">
            {Array.from({ length: 5 }, (_, index) => (
              <div key={index} className="h-20 animate-pulse rounded-2xl bg-surface-2" />
            ))}
          </div>
        ) : materials.length === 0 ? (
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
