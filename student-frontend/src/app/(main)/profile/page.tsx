"use client";

/**
 * 个人中心 /profile（设计 §1.5.6，全 CSR）
 *
 * 结构：用户卡（AI 徽标头像 [displayName 首字母] + displayName + 账号 [登录响应缓存
 * 的 userId，登录响应无 username 字段] + role 徽章）→ 我的课程（复用 CourseCard，
 * J1 getMyCourses，四态全覆盖）→ 退出登录（danger 文字按钮）。
 *
 * 退出登录契约：POST /auth/logout（尽力而为）→ 清本地凭据（AuthProvider.logout）
 * → 清 react-query 缓存（防下一账号读到上一账号缓存）→ 跳 /login。
 */
import { SignOut } from "@phosphor-icons/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { CourseCard } from "@/components/course-card";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { useAuth } from "@/lib/auth-context";
import { getMyCourses } from "@/lib/api";

/** 课程网格骨架：与课程列表页同形（灰块脉冲，设计 §1.7 Loading） */
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

/**
 * 个人中心内容组件（设计 §1.5.6）
 */
export default function ProfilePage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { user, logout } = useAuth();
  const coursesQuery = useQuery({ queryKey: ["my-courses"], queryFn: getMyCourses });
  // 空态兜底用 useMemo 稳定引用（与课程列表页同款防御）
  const courses = useMemo(() => coursesQuery.data ?? [], [coursesQuery.data]);
  const [loggingOut, setLoggingOut] = useState(false);

  /** 退出登录：登出清凭据 → 清查询缓存（防账号间串数据）→ 跳登录页 */
  async function handleLogout() {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await logout();
      queryClient.clear();
      router.push("/login");
    } finally {
      setLoggingOut(false);
    }
  }

  // 认证加载期（挂载静默续期窗口）不渲染用户卡内容，避免空白闪烁
  if (!user) {
    return (
      <div className="mx-auto w-full max-w-6xl px-6 pb-20">
        <CoursesSkeleton />
      </div>
    );
  }

  const roleLabel = user.role === "STUDENT" ? "学生" : user.role;

  return (
    <div className="mx-auto w-full max-w-6xl px-6 pb-20">
      {/* 用户卡：AI 徽标头像（displayName 首字母）+ 身份信息 + 退出登录 */}
      <section className="flex flex-wrap items-center gap-5 rounded-2xl border border-border bg-surface p-6">
        {/* AI 徽标同款 teal 渐变圆角方块 + displayName 首字母（设计 §1.5.6 括号语义） */}
        <span
          data-testid="profile-avatar"
          aria-hidden
          className="grid size-16 shrink-0 place-items-center rounded-2xl bg-linear-to-br from-brand to-brand-strong text-2xl font-bold text-white shadow-md shadow-teal-900/5"
        >
          {user.displayName.charAt(0) || "学"}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-display text-xl leading-snug font-semibold text-text">
              {user.displayName}
            </h2>
            {/* role 徽章：STUDENT 中文化，其它角色原样（登录响应缓存） */}
            <span className="rounded-full bg-brand-soft px-2.5 py-0.5 text-xs font-medium text-brand-strong">
              {roleLabel}
            </span>
          </div>
          {/* 账号：登录响应未含 username 字段，以 userId（Long→string）承担账号标识 */}
          <p className="mt-1 text-sm text-muted tabular-nums">账号 {user.userId}</p>
        </div>
        <button
          type="button"
          onClick={() => void handleLogout()}
          disabled={loggingOut}
          className="flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium text-danger transition-colors hover:bg-danger/10 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-danger"
        >
          <SignOut size={15} aria-hidden />
          {loggingOut ? "退出中…" : "退出登录"}
        </button>
      </section>

      {/* 我的课程：复用 CourseCard（J1），四态全覆盖 */}
      <section className="mt-10">
        <h2 className="font-display text-[22px] leading-[1.3] font-semibold text-text">我的课程</h2>
        {coursesQuery.isPending ? (
          <CoursesSkeleton />
        ) : coursesQuery.isError ? (
          <div className="mt-6">
            <SectionError onRetry={() => void coursesQuery.refetch()} />
          </div>
        ) : courses.length === 0 ? (
          <EmptyState title="还没有加入课程，请联系老师开通" />
        ) : (
          <div className="mt-6 grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3">
            {courses.map((course) => (
              <CourseCard key={course.id} course={course} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
