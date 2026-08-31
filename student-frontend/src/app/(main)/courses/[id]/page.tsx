"use client";

/**
 * 课程详情页（路由 /courses/[id]，全 CSR；2026-08-31 用户拍板改版）
 *
 * 结构：面包屑返回 → 课程 Hero（左封面 4:3 + 右公开信息 + 价格/购买区）→
 * 主体两栏（左「课程介绍」全文描述 + 右「开课信息」排期卡片）。
 *
 * 改版要点（2026-08-31 用户拍板）：
 * - 数据源切公开详情端点 GET /public/courses/{id}（PublicCourseDetailVO，含排期列表），
 *   不再从公开列表里 find 切片——详情页展示完整课程信息（详细描述 + 开课时间 + 课时）
 * - 移除「问 AI 助教 / 进入学习 / 浏览资料」按钮与 J2 资料分片列表（数据库切片不再
 *   直接展示在详情页）；课程资料展示职责回归管理端/B 端
 * - 购买链路保留（契约 H.2.2）：未购 = 价格 + 购买课程主按钮；已购 = 已购徽章；
 *   未登录点购买弹登录窗（afterLogin 自动续购）；失败错误横幅可重试
 *
 * 数据契约：详情 404（课程不存在/已下架）→ 空态；排期可能为空（管理端未录入）→
 * 「暂无排期信息」空态；课时来自 duration 字段（如 "30"），展示为「30 课时」。
 *
 * 动效（design-taste 增强 2026-08-31）：主体两栏 whileInView 轻量入场
 * （y 位移 + 透明度，0.5s easeOutQuint），prefers-reduced-motion 下静态；
 * 整页入场由 (main)/template 的 page-in 承担。
 */
import {
  ArrowLeft,
  CalendarBlank,
  CheckCircle,
  Clock,
  MapPin,
  ShoppingCartSimple,
  Star,
  User,
  Users,
} from "@phosphor-icons/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { motion, useReducedMotion } from "motion/react";
import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { coverFallback, formatCoursePrice } from "@/components/course-card";
import { ApiError, getMyCourses, getPublicCourseDetail, purchaseCourse } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import type { PublicSchedule } from "@/lib/types";

/** 排期类型文案映射（后端枚举 ONLINE/OFFLINE/HYBRID → 中文展示） */
const SCHEDULE_TYPE_LABELS: Record<string, string> = {
  ONLINE: "线上开课",
  OFFLINE: "线下开课",
  HYBRID: "线上线下结合",
};

/** 排期状态文案映射（UPCOMING/IN_PROGRESS/COMPLETED → 中文展示） */
const SCHEDULE_STATUS_LABELS: Record<string, string> = {
  UPCOMING: "未开课",
  IN_PROGRESS: "进行中",
  COMPLETED: "已结课",
};

/** 入场缓动（与课程中心卡片入场同款 easeOutQuint） */
const EASE_OUT_QUINT = [0.22, 1, 0.36, 1] as [number, number, number, number];

/**
 * ISO 日期（"2026-09-01"）→ 中文日期（"2026 年 9 月 1 日"）
 *
 * @param iso LocalDate 序列化字符串（可空）
 * @returns 中文日期文本；入参为空/格式异常时原样返回 null/原文兜底
 */
function formatDate(iso: string | null): string | null {
  if (!iso) {
    return null;
  }
  const [year, month, day] = iso.split("-").map(Number);
  if (!year || !month || !day) {
    return iso;
  }
  return `${year} 年 ${month} 月 ${day} 日`;
}

/**
 * 排期卡片：开课日期为主视觉（衬线大字），辅以类型/地点/状态/报名进度
 */
function ScheduleCard({ schedule }: { schedule: PublicSchedule }) {
  const statusText = schedule.status
    ? (SCHEDULE_STATUS_LABELS[schedule.status] ?? schedule.status)
    : null;
  const typeText = schedule.scheduleType
    ? (SCHEDULE_TYPE_LABELS[schedule.scheduleType] ?? schedule.scheduleType)
    : null;
  return (
    <li className="rounded-2xl border border-border bg-surface p-4 shadow-xs">
      <div className="flex items-start justify-between gap-3">
        <p className="font-display text-[17px] leading-snug font-bold text-text">
          {formatDate(schedule.startDate) ?? "开课时间待定"}
        </p>
        {statusText ? (
          <span
            className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium ${
              schedule.status === "COMPLETED"
                ? "bg-surface-2 text-subtle"
                : schedule.status === "IN_PROGRESS"
                  ? "bg-brand-soft text-brand-strong"
                  : "bg-success/10 text-success"
            }`}
          >
            {statusText}
          </span>
        ) : null}
      </div>
      {schedule.endDate ? (
        <p className="mt-0.5 text-xs text-subtle tabular-nums">至 {formatDate(schedule.endDate)}</p>
      ) : null}
      {(typeText || schedule.location) && (
        <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-[13px] text-muted">
          {typeText ? <span>{typeText}</span> : null}
          {schedule.location ? (
            <span className="inline-flex items-center gap-1">
              <MapPin size={13} aria-hidden className="text-subtle" />
              {schedule.location}
            </span>
          ) : null}
        </div>
      )}
      {schedule.capacity != null ? (
        <p className="mt-3 border-t border-border pt-2.5 text-xs text-subtle tabular-nums">
          已报名 {schedule.enrolled ?? 0} 人 · 容量 {schedule.capacity} 人
        </p>
      ) : null}
    </li>
  );
}

/** 详情页骨架：Hero 块 + 主体两栏灰条（与最终布局同形，设计 §1.7） */
function CourseDetailSkeleton() {
  return (
    <div
      data-testid="course-detail-skeleton"
      className="mx-auto w-full max-w-6xl px-6 py-10"
      aria-busy="true"
    >
      <div className="grid items-start gap-8 md:grid-cols-[360px_1fr]">
        <div className="aspect-[4/3] animate-pulse rounded-2xl bg-surface-2" />
        <div className="space-y-3">
          <div className="h-6 w-28 animate-pulse rounded-full bg-surface-2" />
          <div className="h-8 w-2/3 animate-pulse rounded-xl bg-surface-2" />
          <div className="h-4 w-full animate-pulse rounded-lg bg-surface-2" />
          <div className="h-4 w-1/2 animate-pulse rounded-lg bg-surface-2" />
          <div className="h-9 w-64 animate-pulse rounded-xl bg-surface-2" />
        </div>
      </div>
      <div className="mt-10 grid gap-8 md:grid-cols-[1fr_320px]">
        <div className="space-y-3">
          <div className="h-6 w-28 animate-pulse rounded-lg bg-surface-2" />
          <div className="h-24 animate-pulse rounded-2xl bg-surface-2" />
        </div>
        <div className="space-y-3">
          <div className="h-6 w-28 animate-pulse rounded-lg bg-surface-2" />
          <div className="h-28 animate-pulse rounded-2xl bg-surface-2" />
        </div>
      </div>
    </div>
  );
}

/**
 * 购买失败错误横幅（契约 H.2.2 失败态：错误横幅展示 + 购买按钮恢复可点）
 */
function PurchaseErrorBanner({ text }: { text: string | null }) {
  if (text == null) {
    return null;
  }
  return (
    <div
      role="alert"
      className="mb-6 flex items-center gap-2.5 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
    >
      <p className="text-sm text-danger">{text}</p>
    </div>
  );
}

/**
 * 课程详情内容组件（改版 2026-08-31）
 *
 * 路由 /courses/[id] 受 middleware 门控（游客直引登录页），本页不再自带登录弹窗；
 * 认证失效场景（静默续期失败）由购买按钮点击时弹登录窗兜底。
 */
function CourseDetailContent() {
  const params = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const courseId = params.id;
  const { isAuthenticated, openLoginDialog } = useAuth();
  const reduceMotion = useReducedMotion() ?? true;
  // 详情数据源：公开详情端点（含排期列表；404 = 课程不存在/已下架）
  const detailQuery = useQuery({
    queryKey: ["public-course-detail", courseId],
    queryFn: () => getPublicCourseDetail(courseId),
    retry: false,
  });
  const course = detailQuery.data;
  const isNotFound = detailQuery.error instanceof ApiError && detailQuery.error.code === 404;

  // 已购判定：登录用户交叉「我的课程」（契约 H.2.2 状态机数据源；购买成功失效刷新）
  const purchasedQuery = useQuery({
    queryKey: ["my-courses"],
    queryFn: getMyCourses,
    enabled: isAuthenticated,
  });
  const isPurchased = purchasedQuery.data?.some((item) => item.id === courseId) ?? false;

  // 购买成功 toast：3 秒自动消失（与登录失效 toast 同款自制轻提示，无新依赖）
  const [purchaseToastOpen, setPurchaseToastOpen] = useState(false);
  useEffect(() => {
    if (!purchaseToastOpen) {
      return;
    }
    const timer = window.setTimeout(() => setPurchaseToastOpen(false), 3000);
    return () => window.clearTimeout(timer);
  }, [purchaseToastOpen]);

  // 购买 mutation（契约 B/H.2.2）：幂等（重复购买后端返回成功）；dev 直通无支付校验。
  // onSuccess 返回 Promise 保证写后读一致（宪法 C.1.4）：失效 my-courses 后已购徽章即时呈现
  const purchaseMutation = useMutation({
    mutationFn: () => purchaseCourse(courseId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["my-courses"] });
      setPurchaseToastOpen(true);
    },
  });

  // 购买失败文案分级（契约 H.2.2）：404 课程已下架；其余业务错误透传后端消息；
  // 网络错误提示可重试（按钮恢复可点，再次点击即重试）
  const purchaseErrorText =
    purchaseMutation.error == null
      ? null
      : purchaseMutation.error instanceof ApiError
        ? purchaseMutation.error.code === 404
          ? "课程已下架或不存在，请刷新页面"
          : purchaseMutation.error.message
        : "网络连接失败，请检查网络后重试";

  /** 购买入口（H.2.2 状态机统一入口）：未登录先弹登录窗（afterLogin 自动续购） */
  function handlePurchase() {
    if (!isAuthenticated) {
      openLoginDialog({ afterLogin: () => purchaseMutation.mutate() });
      return;
    }
    purchaseMutation.mutate();
  }

  // ===== 四态：加载骨架 / 404 空态 / 通用错误重试 / 正常态 =====
  if (detailQuery.isPending) {
    return <CourseDetailSkeleton />;
  }
  if (detailQuery.isError) {
    // 404 = 课程不存在/已下架（不泄露存在性）；其余错误走通用错误横幅 + 重试
    if (isNotFound) {
      return (
        <div className="mx-auto w-full max-w-6xl px-6 py-16">
          <EmptyState title="课程不存在或已下架" actionLabel="返回课程中心" actionHref="/courses" />
        </div>
      );
    }
    return (
      <div className="mx-auto w-full max-w-6xl px-6 py-16">
        <SectionError onRetry={() => void detailQuery.refetch()} />
      </div>
    );
  }
  // 成功路径必有数据（公开详情端点契约）；防御性兜底防类型收窄遗漏
  if (!course) {
    return (
      <div className="mx-auto w-full max-w-6xl px-6 py-16">
        <EmptyState title="课程不存在或已下架" actionLabel="返回课程中心" actionHref="/courses" />
      </div>
    );
  }

  // Hero 无封面兜底：与 CourseCard 同款学科渐变（分类关键词映射）
  const { icon: FallbackIcon, gradient } = coverFallback(course.category);
  // 入场动效开关：reduced-motion 下两栏静态呈现
  const revealProps = reduceMotion
    ? {}
    : {
        initial: { opacity: 0, y: 16 },
        whileInView: { opacity: 1, y: 0 },
        viewport: { once: true, amount: 0.15 },
        transition: { duration: 0.5, ease: EASE_OUT_QUINT },
      };

  return (
    <div className="mx-auto w-full max-w-6xl px-6 pb-20">
      {/* 面包屑返回：课程中心 / 当前课程（替代被移除按钮后的回退锚点） */}
      <nav aria-label="面包屑" className="flex items-center gap-1.5 pt-8 text-sm text-muted">
        <Link
          href="/courses"
          className="inline-flex items-center gap-1 rounded-md px-1.5 py-1 transition-colors hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
        >
          <ArrowLeft size={14} aria-hidden />
          课程中心
        </Link>
        <span aria-hidden className="text-faint">
          /
        </span>
        <span className="max-w-56 truncate text-subtle">{course.title}</span>
      </nav>

      {/* ===== 课程 Hero：左封面（4:3 rounded-2xl）右信息 + 价格购买区 ===== */}
      <section className="grid items-start gap-8 py-8 md:grid-cols-[360px_1fr]">
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
          {course.category ? (
            <span
              aria-hidden
              className="absolute top-3 left-3 rounded-full bg-overlay px-2.5 py-0.5 text-[11px] font-medium text-white backdrop-blur-sm"
            >
              {course.category}
            </span>
          ) : null}
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
          {/* 价格与购买区（契约 H.2.2 状态机）：未购=价格 + 购买课程主按钮；已购=已购徽章 */}
          <div className="mt-6 flex flex-wrap items-center gap-x-5 gap-y-3">
            <p
              data-testid="course-price"
              className="font-display text-[26px] leading-none font-bold text-brand-strong tabular-nums"
            >
              {formatCoursePrice(course.price) ?? "免费"}
            </p>
            {isPurchased ? (
              <span
                data-testid="purchased-badge"
                className="inline-flex items-center gap-1 rounded-full bg-brand px-3 py-1 text-xs font-medium text-white"
              >
                <CheckCircle size={13} weight="fill" aria-hidden />
                已购
              </span>
            ) : (
              <button
                type="button"
                onClick={handlePurchase}
                disabled={purchaseMutation.isPending}
                className="inline-flex items-center gap-2 rounded-full bg-brand px-5 py-2.5 text-[15px] font-medium text-white shadow-md shadow-brand/30 transition-[transform,opacity] hover:-translate-y-0.5 hover:bg-brand-strong active:translate-y-0 active:scale-[0.98] motion-reduce:transition-none focus-visible:ring-2 focus-visible:ring-brand disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:translate-y-0"
              >
                <ShoppingCartSimple size={16} aria-hidden />
                {purchaseMutation.isPending ? "购买中…" : "购买课程"}
              </button>
            )}
          </div>
        </div>
      </section>

      {/* 购买失败错误横幅（契约 H.2.2：内联于 Hero 下方；购买按钮恢复可点，再次点击即重试） */}
      <PurchaseErrorBanner text={purchaseErrorText} />

      {/* ===== 主体两栏：左「课程介绍」右「开课信息」（改版 2026-08-31） ===== */}
      <div className="grid items-start gap-8 border-t border-border pt-10 md:grid-cols-[minmax(0,1fr)_320px]">
        <motion.section {...revealProps} aria-label="课程介绍">
          <h2 className="font-display text-[22px] leading-[1.3] font-semibold text-text">
            课程介绍
          </h2>
          {course.description ? (
            <div className="mt-4 max-w-2xl space-y-3 text-[15px] leading-[1.9] text-text/90 whitespace-pre-line">
              {course.description.split(/\n{2,}/).map((paragraph, index) => (
                <p key={index}>{paragraph}</p>
              ))}
            </div>
          ) : (
            <p className="mt-4 max-w-xl text-sm leading-relaxed text-muted">
              讲师正在完善这门课程的详细介绍，敬请期待。
            </p>
          )}
        </motion.section>

        <motion.aside {...revealProps} aria-label="开课信息" className="md:sticky md:top-24">
          <h2 className="font-display text-[22px] leading-[1.3] font-semibold text-text">
            开课信息
          </h2>
          {course.schedules.length > 0 ? (
            <ul className="mt-4 space-y-3">
              {course.schedules.map((schedule) => (
                <ScheduleCard key={schedule.id} schedule={schedule} />
              ))}
            </ul>
          ) : (
            <div
              data-testid="schedule-empty"
              className="mt-4 rounded-2xl border border-dashed border-border bg-surface-2/60 px-5 py-7 text-center"
            >
              <CalendarBlank size={22} aria-hidden className="mx-auto text-subtle" />
              <p className="mt-2 text-sm text-muted">暂无排期信息，敬请期待</p>
            </div>
          )}
        </motion.aside>
      </div>

      {/* 购买成功 toast（自制轻提示与登录失效 toast 同范式；role=status 供读屏播报） */}
      {purchaseToastOpen ? (
        <div
          role="status"
          className="fixed bottom-6 left-1/2 z-50 -translate-x-1/2 rounded-xl bg-text px-4 py-2.5 text-sm text-surface shadow-lg"
        >
          购买成功
        </div>
      ) : null}
    </div>
  );
}

/**
 * 课程详情页路由组件
 *
 * useParams 依赖路由器上下文；本页数据为公开详情端点，无 AuthGate 全页拦截
 * （middleware 已门控 /courses/*，游客直引登录页；登录态由购买入口兜底）。
 */
export default function CourseDetailPage() {
  return <CourseDetailContent />;
}
