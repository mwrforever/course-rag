"use client";

import { MagnifyingGlass } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { Suspense, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { AiBadge } from "@/components/ai-badge";
import { CourseCard } from "@/components/course-card";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { getMyCourses } from "@/lib/api";

/** 本地分页每页课程数（设计 §1.5.2） */
const PAGE_SIZE = 12;

/** 排序方式：rating=评分降序（默认）/ name=按名称 */
type SortMode = "rating" | "name";

/** 课程网格骨架：与最终布局同形（灰块脉冲，设计 §1.7 Loading） */
function CoursesSkeleton() {
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

/** 筛选无匹配空态：AI 徽标 + 一句话 + 清除筛选入口（设计 §1.7 Empty） */
function FilterEmpty({ onClear }: { onClear: () => void }) {
  return (
    <div className="flex flex-col items-center gap-4 py-14 text-center">
      <AiBadge />
      <p className="max-w-md text-[15px] leading-relaxed text-muted">
        没有找到相关课程，换个关键词或分类试试
      </p>
      <button
        type="button"
        onClick={onClear}
        className="mt-1 inline-flex items-center gap-2 rounded-xl border border-brand/30 bg-surface px-4 py-2 text-sm font-medium text-brand-strong transition-colors hover:bg-brand-light focus-visible:ring-2 focus-visible:ring-brand"
      >
        清除筛选
      </button>
    </div>
  );
}

/**
 * 课程列表页内容组件（设计 §1.5.2，全 CSR）
 *
 * J1 无分页接口：全量拉取后内存筛选（category Chip 从数据聚合 + 关键词即时过滤）
 * + 排序（默认评分降序，可切换按名称）+ 本地分页（每页 12）。
 * category 与 q 以 URL query 驱动浅路由同步（入口直接读 URL，交互写回 URL）。
 * 四态全覆盖（设计 §1.7）：Loading 骨架 / Empty 空态 / Error 横幅+重试 / 正常态。
 */
function CoursesContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const coursesQuery = useQuery({ queryKey: ["my-courses"], queryFn: getMyCourses });
  // 筛选状态初始来自 URL query（?category=&q=），交互更新本地状态并浅路由同步
  const [category, setCategory] = useState(searchParams.get("category") ?? "");
  const [keyword, setKeyword] = useState(searchParams.get("q") ?? "");
  const [sortMode, setSortMode] = useState<SortMode>("rating");
  const [page, setPage] = useState(1);

  // 课程列表：空态兜底用 useMemo 稳定引用，避免空数组字面量每次渲染新建导致依赖变化
  const courses = useMemo(() => coursesQuery.data ?? [], [coursesQuery.data]);

  // category Chip 集：从 J1 数据聚合去重（null 分类折叠进「全部」）
  const categories = useMemo(
    () => [
      ...new Set(courses.map((course) => course.category).filter((c): c is string => Boolean(c))),
    ],
    [courses],
  );

  // 本地过滤：分类精确匹配 + 关键词模糊匹配（标题/讲师，大小写不敏感）
  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return courses.filter((course) => {
      const inCategory = !category || course.category === category;
      const hitKeyword =
        !kw ||
        course.title.toLowerCase().includes(kw) ||
        (course.instructorName ?? "").toLowerCase().includes(kw);
      return inCategory && hitKeyword;
    });
  }, [courses, category, keyword]);

  // 排序：评分降序（无评分排最后）或标题字典序
  const sorted = useMemo(() => {
    const list = [...filtered];
    if (sortMode === "name") {
      list.sort((a, b) => a.title.localeCompare(b.title, "zh-Hans-CN"));
    } else {
      list.sort((a, b) => (b.rating ?? -1) - (a.rating ?? -1));
    }
    return list;
  }, [filtered, sortMode]);

  // 本地分页：页号越界自动钳制到最后一页
  const pageCount = Math.max(1, Math.ceil(sorted.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const visible = sorted.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);

  // 筛选/搜索/排序变更时回到第一页
  useEffect(() => {
    setPage(1);
  }, [category, keyword, sortMode]);

  /** 浅路由同步：category 与 q 写入 URL（无参时回落纯 /courses） */
  function syncUrl(cat: string, query: string) {
    const params = new URLSearchParams();
    if (cat) {
      params.set("category", cat);
    }
    if (query) {
      params.set("q", query);
    }
    const qs = params.toString();
    router.replace(qs ? `/courses?${qs}` : "/courses");
  }

  function handleCategory(cat: string) {
    setCategory(cat);
    syncUrl(cat, keyword);
  }

  function handleKeyword(value: string) {
    setKeyword(value);
    syncUrl(category, value);
  }

  function clearFilters() {
    setCategory("");
    setKeyword("");
    syncUrl("", "");
  }

  /** Chip 激活样式：选中态 teal-soft（设计 §1.5.2） */
  function chipClass(active: boolean): string {
    return `rounded-full border px-3.5 py-1.5 text-sm transition-colors focus-visible:ring-2 focus-visible:ring-brand ${
      active
        ? "border-brand/30 bg-brand-soft text-brand-strong"
        : "border-border bg-surface text-muted hover:border-brand/30 hover:text-brand-strong"
    }`;
  }

  return (
    <div className="mx-auto w-full max-w-6xl px-6 pb-20">
      {/* 页头：H1 + 搜索框（本地即时过滤） */}
      <div className="flex flex-wrap items-end justify-between gap-4 py-10">
        <h1 className="font-display text-[30px] leading-[1.25] font-bold text-text">我的课程</h1>
        <label className="relative block w-full max-w-sm">
          <MagnifyingGlass
            size={18}
            aria-hidden
            className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-subtle"
          />
          <input
            type="search"
            value={keyword}
            onChange={(event) => handleKeyword(event.target.value)}
            aria-label="搜索课程"
            placeholder="搜索课程名称或讲师"
            className="w-full rounded-full border border-border bg-surface py-2.5 pr-4 pl-10 text-sm text-text shadow-xs transition-[transform,opacity] outline-none placeholder:text-subtle focus:border-brand/50 focus-visible:ring-2 focus-visible:ring-brand"
          />
        </label>
      </div>

      {/* body 四态：Loading / Error / 空课程 / 筛选无匹配 / 正常态 */}
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
      ) : (
        <>
          {/* category Chip 组（从 J1 数据聚合，「全部」恒在首位） */}
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => handleCategory("")}
              className={chipClass(category === "")}
            >
              全部
            </button>
            {categories.map((cat) => (
              <button
                key={cat}
                type="button"
                onClick={() => handleCategory(cat)}
                className={chipClass(category === cat)}
              >
                {cat}
              </button>
            ))}
          </div>

          {/* 工具行：结果计数 + 排序切换 */}
          <div className="mt-6 flex items-center justify-between gap-4">
            <p className="text-sm text-muted tabular-nums">共 {sorted.length} 门课程</p>
            <select
              value={sortMode}
              onChange={(event) => setSortMode(event.target.value as SortMode)}
              aria-label="排序方式"
              className="rounded-xl border border-border bg-surface px-3 py-2 text-sm text-text outline-none focus-visible:ring-2 focus-visible:ring-brand"
            >
              <option value="rating">评分优先</option>
              <option value="name">名称排序</option>
            </select>
          </div>

          {sorted.length === 0 ? (
            <FilterEmpty onClear={clearFilters} />
          ) : (
            <>
              {/* 网格 3 列（电商风格），卡片 hover 动效由 CourseCard 承载 */}
              <div className="mt-6 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
                {visible.map((course) => (
                  <CourseCard key={course.id} course={course} />
                ))}
              </div>

              {/* 本地分页（≤1 页不展示） */}
              {pageCount > 1 ? (
                <div className="mt-8 flex items-center justify-center gap-4">
                  <button
                    type="button"
                    disabled={safePage === 1}
                    onClick={() => setPage(safePage - 1)}
                    className="rounded-xl border border-border bg-surface px-4 py-2 text-sm font-medium text-text transition-colors hover:border-brand/40 hover:text-brand-strong disabled:cursor-not-allowed disabled:opacity-40 focus-visible:ring-2 focus-visible:ring-brand"
                  >
                    上一页
                  </button>
                  <span className="text-sm text-muted tabular-nums">
                    第 {safePage} / {pageCount} 页
                  </span>
                  <button
                    type="button"
                    disabled={safePage === pageCount}
                    onClick={() => setPage(safePage + 1)}
                    className="rounded-xl border border-border bg-surface px-4 py-2 text-sm font-medium text-text transition-colors hover:border-brand/40 hover:text-brand-strong disabled:cursor-not-allowed disabled:opacity-40 focus-visible:ring-2 focus-visible:ring-brand"
                  >
                    下一页
                  </button>
                </div>
              ) : null}
            </>
          )}
        </>
      )}
    </div>
  );
}

/**
 * 课程列表页（设计 §1.5.2 路由 /courses，全 CSR）
 *
 * useSearchParams 依赖路由器上下文，包一层 Suspense 满足 App Router 预渲染要求；
 * 骨架即 Suspense fallback，与加载态同形。
 */
export default function CoursesPage() {
  return (
    <Suspense fallback={<CoursesSkeleton />}>
      <CoursesContent />
    </Suspense>
  );
}
