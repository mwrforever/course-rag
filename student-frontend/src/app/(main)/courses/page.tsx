"use client";

import { MagnifyingGlass, Star, TextAa } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { motion, useReducedMotion } from "motion/react";
import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { AiBadge } from "@/components/ai-badge";
import { AuthGate } from "@/components/auth/auth-gate";
import { CourseCard } from "@/components/course-card";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { getMyCourses, getPublicCourses } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/** 本地分页每页课程数（设计 §1.5.2） */
const PAGE_SIZE = 12;

/** 卡片入场缓动（easeOutQuint 风格：快起缓停，低饱和位移不抢内容注意） */
const EASE_OUT_QUINT = [0.22, 1, 0.36, 1] as [number, number, number, number];

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
 * 门控事实（2026-08-27 拍板仅首页公开后修正，BUG-28）：本页为**受保护页**——
 * middleware PROTECTED_PREFIXES 含 /courses + AuthGate 全页拦截（2026-08-30），
 * 未登录不可浏览；仅数据源沿用公开接口 getPublicCourses（登录态下全量 ACTIVE
 * 课程），登录用户额外交叉「我的课程」标记已购徽章（2026-08-29 购买链路：
 * 已购/价格双态）。公开接口无分页：全量拉取后内存筛选
 * （category Chip 从数据聚合 + 关键词即时过滤）+ 排序（默认评分降序，可切换按名称）
 * + 本地分页（每页 12）。
 * category 与 q 以 URL query 驱动浅路由同步（入口直接读 URL，交互写回 URL）；
 * 关键词写 URL 收敛到 300ms 防抖（消除每键 replace 的交互卡顿）。
 * 四态全覆盖（设计 §1.7）：Loading 骨架 / Empty 空态 / Error 横幅+重试 / 正常态。
 */
function CoursesContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const { isAuthenticated } = useAuth();
  // 动效降级：prefers-reduced-motion 下关闭滑动指示/交错入场（只保留瞬时态切换）
  const reduceMotion = useReducedMotion() ?? true;
  const coursesQuery = useQuery({ queryKey: ["public-courses"], queryFn: getPublicCourses });
  // 已购课程集合：仅登录时查询我的课程交叉标记（已购徽章，契约 H.2.1）
  const purchasedQuery = useQuery({
    queryKey: ["my-courses"],
    queryFn: getMyCourses,
    enabled: isAuthenticated,
  });
  // 筛选状态初始来自 URL query（?category=&q=），交互更新本地状态并浅路由同步
  const [category, setCategory] = useState(searchParams.get("category") ?? "");
  const [keyword, setKeyword] = useState(searchParams.get("q") ?? "");
  // 关键词防抖值：过滤用即时值（本地内存过滤快），URL 同步收敛到防抖后
  const debouncedKeyword = useDebouncedValue(keyword, 300);
  const [sortMode, setSortMode] = useState<SortMode>("rating");
  const [page, setPage] = useState(1);

  // 课程列表：空态兜底用 useMemo 稳定引用，避免空数组字面量每次渲染新建导致依赖变化
  const courses = useMemo(() => coursesQuery.data ?? [], [coursesQuery.data]);
  // 已购课程 ID 集合（登录用户；未登录恒空集）
  const purchasedIds = useMemo(
    () => new Set((purchasedQuery.data ?? []).map((course) => course.id)),
    [purchasedQuery.data],
  );

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

  /** 浅路由同步：category 与 q 写入 URL（无参时回落纯 /courses）；关键词经防抖值驱动 */
  const syncUrl = useCallback(() => {
    const params = new URLSearchParams();
    if (category) {
      params.set("category", category);
    }
    if (debouncedKeyword) {
      params.set("q", debouncedKeyword);
    }
    const qs = params.toString();
    router.replace(qs ? `/courses?${qs}` : "/courses");
  }, [category, debouncedKeyword, router]);

  // 关键词写 URL 防抖后同步；分类切换即时同步（低频率操作）
  useEffect(() => {
    syncUrl();
  }, [syncUrl]);

  function handleCategory(cat: string) {
    setCategory(cat);
  }

  function handleKeyword(value: string) {
    setKeyword(value);
  }

  function clearFilters() {
    setCategory("");
    setKeyword("");
  }

  return (
    <div className="mx-auto w-full max-w-6xl px-6 pb-20">
      {/* 页头：H1 + 搜索框（本地即时过滤） */}
      <div className="flex flex-wrap items-end justify-between gap-4 py-10">
        <h1 className="font-display text-[30px] leading-[1.25] font-bold text-text">课程中心</h1>
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
          title="暂无上架课程，请稍后再来"
          actionLabel="先和 AI 助教聊聊"
          actionHref="/chat"
        />
      ) : (
        <>
          {/* 学科 Tab 栏（2026-08-31 改版：等宽栅格 + 选中才显示下划线，切换时下划线
              layoutId 弹簧滑动到目标 tab；关键词过滤不触发动效重放） */}
          <div
            role="tablist"
            aria-label="学科筛选"
            data-testid="category-tabs"
            className="grid border-b border-border"
            style={{ gridTemplateColumns: `repeat(${categories.length + 1}, minmax(0, 1fr))` }}
          >
            {["", ...categories].map((cat) => {
              const active = category === cat;
              const label = cat === "" ? "全部" : cat;
              return (
                <button
                  key={cat}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  data-testid="category-tab"
                  onClick={() => handleCategory(cat)}
                  className={`relative min-w-0 px-3 py-2.5 text-sm transition-colors focus-visible:ring-2 focus-visible:ring-brand ${
                    active ? "font-medium text-brand-strong" : "text-muted hover:text-text"
                  }`}
                >
                  {/* 等宽下划线指示：仅选中项渲染；layoutId 共享元素驱动跨 tab 滑动
                      （reduced-motion 下为静态 span 无过渡） */}
                  {active ? (
                    reduceMotion ? (
                      <span
                        aria-hidden
                        data-testid="category-tab-underline"
                        className="absolute inset-x-3 bottom-0 h-[2px] rounded-full bg-brand"
                      />
                    ) : (
                      <motion.span
                        aria-hidden
                        data-testid="category-tab-underline"
                        layoutId="category-tab-underline"
                        transition={{ type: "spring", stiffness: 460, damping: 42 }}
                        className="absolute inset-x-3 bottom-0 h-[2px] rounded-full bg-brand"
                      />
                    )
                  ) : null}
                  <span className="block truncate">{label}</span>
                </button>
              );
            })}
          </div>

          {/* 工具行：结果计数 + 排序分段控件（2026-08-31：激活胶囊改为 layoutId 滑动指示，
              与 tab 下划线同一动效语言；仅排序切换触发动效） */}
          <div className="mt-5 flex flex-wrap items-center justify-between gap-4">
            <p className="text-sm text-muted tabular-nums">共 {sorted.length} 门课程</p>
            <div
              role="radiogroup"
              aria-label="排序方式"
              data-testid="sort-segment"
              className="relative flex shrink-0 rounded-full border border-border bg-surface p-1 shadow-xs"
            >
              <button
                type="button"
                role="radio"
                aria-checked={sortMode === "rating"}
                data-testid="sort-option-rating"
                onClick={() => setSortMode("rating")}
                className={`relative rounded-full px-4 py-1.5 text-sm whitespace-nowrap transition-colors focus-visible:ring-2 focus-visible:ring-brand ${
                  sortMode === "rating"
                    ? "font-medium text-brand-strong"
                    : "text-muted hover:text-text"
                }`}
              >
                {sortMode === "rating" ? (
                  reduceMotion ? (
                    <span
                      aria-hidden
                      data-testid="sort-segment-pill"
                      className="absolute inset-0 rounded-full bg-brand-soft"
                    />
                  ) : (
                    <motion.span
                      aria-hidden
                      data-testid="sort-segment-pill"
                      layoutId="sort-segment-pill"
                      transition={{ type: "spring", stiffness: 460, damping: 42 }}
                      className="absolute inset-0 rounded-full bg-brand-soft"
                    />
                  )
                ) : null}
                <span className="relative inline-flex items-center gap-1.5">
                  <Star
                    size={13}
                    weight="fill"
                    aria-hidden
                    className={sortMode === "rating" ? "text-brand" : "text-subtle"}
                  />
                  评分优先
                </span>
              </button>
              <button
                type="button"
                role="radio"
                aria-checked={sortMode === "name"}
                data-testid="sort-option-name"
                onClick={() => setSortMode("name")}
                className={`relative rounded-full px-4 py-1.5 text-sm whitespace-nowrap transition-colors focus-visible:ring-2 focus-visible:ring-brand ${
                  sortMode === "name"
                    ? "font-medium text-brand-strong"
                    : "text-muted hover:text-text"
                }`}
              >
                {sortMode === "name" ? (
                  reduceMotion ? (
                    <span
                      aria-hidden
                      data-testid="sort-segment-pill"
                      className="absolute inset-0 rounded-full bg-brand-soft"
                    />
                  ) : (
                    <motion.span
                      aria-hidden
                      data-testid="sort-segment-pill"
                      layoutId="sort-segment-pill"
                      transition={{ type: "spring", stiffness: 460, damping: 42 }}
                      className="absolute inset-0 rounded-full bg-brand-soft"
                    />
                  )
                ) : null}
                <span className="relative inline-flex items-center gap-1.5">
                  <TextAa
                    size={13}
                    aria-hidden
                    className={sortMode === "name" ? "text-brand" : "text-subtle"}
                  />
                  名称排序
                </span>
              </button>
            </div>
          </div>

          {sorted.length === 0 ? (
            <FilterEmpty onClear={clearFilters} />
          ) : (
            <>
              {/* 网格 3 列（电商风格），卡片 hover 动效由 CourseCard 承载；
                  切 tab/排序时按键重挂载交错入场（关键词输入不重放——不换 key），
                  reduced-motion 下跳过入场动效 */}
              <motion.div
                key={`${category}|${sortMode}`}
                initial={reduceMotion ? false : "hidden"}
                animate="show"
                variants={{
                  hidden: {},
                  show: { transition: { staggerChildren: 0.04, delayChildren: 0.04 } },
                }}
                className="mt-6 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3"
              >
                {visible.map((course) => (
                  <motion.div
                    key={course.id}
                    variants={{
                      hidden: { opacity: 0, y: 14 },
                      show: {
                        opacity: 1,
                        y: 0,
                        transition: { duration: 0.42, ease: EASE_OUT_QUINT },
                      },
                    }}
                  >
                    <CourseCard
                      course={course}
                      purchased={isAuthenticated ? purchasedIds.has(course.id) : undefined}
                    />
                  </motion.div>
                ))}
              </motion.div>

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
 * AuthGate 客户端守卫（2026-08-30 认证刷新链路修复）：受保护路由在静默续期窗口
 * 渲染同形骨架（不闪登录页），续期失败开登录弹窗兜底。
 */
export default function CoursesPage() {
  return (
    <AuthGate
      fallback={
        <div className="mx-auto w-full max-w-6xl px-6 pb-20">
          <CoursesSkeleton />
        </div>
      }
    >
      <Suspense fallback={<CoursesSkeleton />}>
        <CoursesContent />
      </Suspense>
    </AuthGate>
  );
}
