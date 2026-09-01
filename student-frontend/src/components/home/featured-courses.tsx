"use client";

/**
 * 精选课程区（设计稿一 Featured Programmes 还原）
 *
 * 业务替换：Lionheart 课程营 → 问渠学堂公开课程库（GET /api/v1/public/courses，
 * 未登录可浏览）；登录用户交叉标记「已购」徽章，未购卡片价签位展示价格
 * （契约 H.2.1，2026-08-29 购买链路）。
 * 四态契约保留：骨架（Loading）／空态／错误重试／正常网格；
 * 布局改为设计稿三列大卡（sepia 封面 hover 复原、时长徽章、分类 pill、衬线标题、
 * 元信息圆点行、讲师头像 + 价签位），reduced-motion 全静态。
 * PERF-06：封面改走 next/image（fill + MinIO remotePatterns 白名单同课程中心口径，
 * 原图直链经运行时优化管道转 AVIF/WebP；sepia/hover 复原动效类保留）。
 */
import { useQuery } from "@tanstack/react-query";
import Image from "next/image";
import Link from "next/link";
import { Reveal, Stagger } from "@/components/motion/reveal";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { formatCoursePrice } from "@/components/course-card";
import { getMyCourses, getPublicCourses } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import type { PublicCourse } from "@/lib/types";

/** 课程卡片（设计稿 cc-* 结构对应业务字段） */
function WenquCourseCard({
  course,
  purchased,
}: {
  course: PublicCourse;
  /** 已购标记（未登录恒为 undefined 不显示） */
  purchased?: boolean;
}) {
  const instructorInitial = course.instructorName?.charAt(0) || "教";
  return (
    <Link
      href={`/courses/${course.id}`}
      data-testid="wenqu-course-card"
      className="group flex flex-col border border-border bg-surface transition-[transform,box-shadow] duration-500 ease-out hover:-translate-y-2 hover:shadow-xl focus-visible:ring-2 focus-visible:ring-brand"
    >
      {/* 媒体区：sepia 暖调 → hover 复原微放大 */}
      <div className="relative aspect-16/10 overflow-hidden">
        {course.coverImage ? (
          <Image
            src={course.coverImage}
            alt=""
            fill
            sizes="(max-width: 768px) 100vw, (max-width: 1024px) 50vw, 33vw"
            className="object-cover transition-transform duration-1000 ease-out group-hover:scale-[1.06]"
            style={{ filter: "sepia(.12)" }}
          />
        ) : (
          <div
            aria-hidden
            className="h-full w-full transition-transform duration-1000 ease-out group-hover:scale-[1.06]"
            style={{ background: "var(--gradient-ai)", filter: "sepia(.12)" }}
          />
        )}
        <span className="absolute top-4 left-4 rounded-full bg-bg/90 px-3.5 py-[7px] text-[9.5px] tracking-[0.16em] text-ink uppercase">
          {course.duration || "随堂"}
        </span>
        {purchased ? (
          <span className="absolute top-4 right-4 rounded-full bg-brand px-3 py-1 text-[10px] font-medium tracking-wider text-bg">
            已购
          </span>
        ) : null}
      </div>

      <div className="flex flex-1 flex-col px-[30px] pt-7 pb-[26px]">
        {course.category ? (
          <span className="self-start rounded-full border border-ink/40 px-4 py-2 text-[9.5px] tracking-[0.16em] uppercase">
            {course.category}
          </span>
        ) : null}
        <h3 className="font-serif-display mt-5 line-clamp-2 text-[clamp(21px,1.8vw,26px)] leading-snug font-medium">
          {course.title}
        </h3>
        <p className="mt-3 line-clamp-3 flex-1 text-[13.5px] leading-[1.85] text-muted">
          {course.description || "课堂讲义与资料已入库，向 AI 助教提问即可溯源细读。"}
        </p>
        <div className="mt-[22px] flex items-center gap-2 text-[11px] tracking-[0.08em] text-subtle uppercase">
          <span>{course.rating != null ? `评分 ${course.rating.toFixed(1)}` : "新上架"}</span>
          <i aria-hidden className="size-[3px] rounded-full bg-subtle" />
          <span>{course.learningCount} 人学过</span>
        </div>

        <div className="mt-6 flex items-center justify-between border-t border-border pt-5">
          <div className="flex items-center gap-2.5">
            <span className="font-serif-display grid size-[34px] place-items-center rounded-full bg-surface-2 text-sm text-brand">
              {instructorInitial}
            </span>
            <span className="text-[13px] leading-tight">
              {course.instructorName || "问渠助教团"}
              <small className="mt-0.5 block text-[10px] tracking-[0.1em] text-subtle uppercase">
                主讲
              </small>
            </span>
          </div>
          {/* 价签位：未购课程展示价格（元，去尾零）/「免费」；已购课程保留学习人数（契约 H.2.1） */}
          <div className="text-right">
            {purchased ? (
              <>
                <b className="font-serif-display block text-xl font-medium">
                  {course.learningCount}
                </b>
                <small className="block text-[9.5px] tracking-[0.12em] text-subtle uppercase">
                  人已加入
                </small>
              </>
            ) : (
              <>
                <b className="font-serif-display block text-xl font-medium">
                  {formatCoursePrice(course.price) ?? "免费"}
                </b>
                <small className="block text-[9.5px] tracking-[0.12em] text-subtle uppercase">
                  课程价格
                </small>
              </>
            )}
          </div>
        </div>
      </div>
    </Link>
  );
}

/**
 * 精选课程区
 */
export function FeaturedCourses() {
  const { isAuthenticated } = useAuth();
  const coursesQuery = useQuery({ queryKey: ["public-courses"], queryFn: getPublicCourses });
  // 已购集合：仅登录时查询我的课程交叉标记（已购徽章，契约 H.2.1）
  const purchasedQuery = useQuery({
    queryKey: ["my-courses"],
    queryFn: getMyCourses,
    enabled: isAuthenticated,
  });

  if (coursesQuery.isPending) {
    return (
      <section className="mx-auto w-full max-w-[1360px] px-6 py-[120px]" aria-busy="true">
        <div className="h-12 animate-pulse rounded-lg bg-surface-2" />
        <div className="mt-14 grid grid-cols-1 gap-7 md:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 3 }, (_, index) => (
            <div
              key={index}
              className="animate-pulse rounded-sm bg-surface-2 [aspect-ratio:3/4.2]"
            />
          ))}
        </div>
      </section>
    );
  }

  if (coursesQuery.isError) {
    return (
      <section className="mx-auto w-full max-w-[1360px] px-6 py-[120px]">
        <SectionError onRetry={() => void coursesQuery.refetch()} />
      </section>
    );
  }

  const courses = coursesQuery.data ?? [];
  const featured = courses.slice(0, 6);
  const purchasedIds = new Set((purchasedQuery.data ?? []).map((course) => course.id));

  return (
    <section id="featured-courses" className="py-[130px]">
      <div className="mx-auto w-full max-w-[1360px] px-6">
        <div className="mb-16 flex flex-wrap items-end justify-between gap-10">
          <div>
            <Reveal>
              <p className="text-accent-italic text-[clamp(22px,2vw,30px)]">精选课程</p>
            </Reveal>
            <Reveal delay={0.08}>
              <h2 className="font-serif-display mt-3 max-w-[640px] text-[clamp(30px,3.4vw,50px)] leading-[1.18] font-medium">
                同学们正在学的课
              </h2>
            </Reveal>
          </div>
          <Reveal variant="right" delay={0.16}>
            <Link href="/courses" className="btn-pill btn-dark text-[11px] uppercase">
              查看全部课程
            </Link>
          </Reveal>
        </div>

        {featured.length === 0 ? (
          <EmptyState
            title="暂无上架课程，请稍后再来"
            actionLabel="先和 AI 助教聊聊"
            actionHref="/chat"
          />
        ) : (
          <Stagger className="grid grid-cols-1 gap-7 md:grid-cols-2 lg:grid-cols-3">
            {featured.map((course) => (
              <Reveal key={course.id} once className="reveal !transition-shadow duration-500">
                <WenquCourseCard
                  course={course}
                  purchased={isAuthenticated ? purchasedIds.has(course.id) : undefined}
                />
              </Reveal>
            ))}
          </Stagger>
        )}
      </div>
    </section>
  );
}
