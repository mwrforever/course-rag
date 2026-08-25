"use client";

/**
 * 会话管理页 /sessions（设计 §1.5.5 + J6/J7/R3，全 CSR）
 *
 * 结构：H1「会话」+ [新建对话]（J7 createSession 后跳 /chat/{id}）→
 * 时间分组列表（今天/昨天/本周/更早，按 lastMessageAt ?? createdAt，设计 §1.5.5
 * 弥补无预览字段）+ 状态徽章（ACTIVE 进行中 / CLOSED 已结束）+ 相对时间。
 *
 * 交互契约：
 * - 分页 J6 page/size=20：useInfiniteQuery 逐页追加 + 「加载更多」按钮（无更多隐藏）
 * - 删除二次确认 Dialog（danger，R3）：确认后 deleteSession；活跃 run 后端 409 →
 *   toast「会话正在对话中」；成功 toast「会话已删除」并失效列表首屏
 * - 空态「还没有会话记录」+ 开始对话（→ /chat 由后端按需建会话）；
 *   四态全覆盖（设计 §1.7）：Loading 骨架 / Empty / Error 横幅+重试 / 正常态
 */
import { Plus, Trash } from "@phosphor-icons/react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChatToast } from "@/components/chat/chat-toast";
import { EmptyState } from "@/components/empty-state";
import { SectionError } from "@/components/section-error";
import { ApiError, createSession, deleteSession, getSessions } from "@/lib/api";
import { formatRelativeTime, groupSessionTime, type SessionTimeGroup } from "@/lib/time";
import type { SessionItem } from "@/lib/types";

/** 分页每页条数（J6：page/size=20） */
const PAGE_SIZE = 20;

/** toast 展示时长（毫秒，到时自动消失） */
const TOAST_DURATION_MS = 2400;

/** 分组顺序（固定语义序：今天 → 昨天 → 本周 → 更早） */
const GROUP_ORDER: ReadonlyArray<{ key: SessionTimeGroup; label: string }> = [
  { key: "today", label: "今天" },
  { key: "yesterday", label: "昨天" },
  { key: "thisWeek", label: "本周" },
  { key: "earlier", label: "更早" },
];

/** 会话列表骨架：5 行灰块脉冲（与最终布局同形，设计 §1.7 Loading） */
function SessionsSkeleton() {
  return (
    <div data-testid="sessions-skeleton" className="mt-6 space-y-3" aria-busy="true">
      {Array.from({ length: 5 }, (_, index) => (
        <div key={index} className="h-16 animate-pulse rounded-2xl bg-surface-2" />
      ))}
    </div>
  );
}

/**
 * 会话管理页内容组件（设计 §1.5.5，全 CSR）
 */
export default function SessionsPage() {
  const router = useRouter();
  const queryClient = useQueryClient();

  // ── 轻量 toast（与对话页同构：页面级状态 + 定时自动消失，卸载清理定时器）──
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const notify = useCallback((message: string) => {
    setToast(message);
    if (toastTimer.current !== null) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), TOAST_DURATION_MS);
  }, []);
  useEffect(
    () => () => {
      if (toastTimer.current !== null) clearTimeout(toastTimer.current);
    },
    [],
  );

  // ── 分页列表（J6）：逐页追加，getNextPageParam 由「已加载 < 总数且本页非空」判定 ──
  const sessionsQuery = useInfiniteQuery({
    queryKey: ["sessions"],
    queryFn: ({ pageParam }) => getSessions(pageParam, PAGE_SIZE),
    initialPageParam: 1,
    getNextPageParam: (lastPage, allPages) => {
      const loaded = allPages.reduce((sum, page) => sum + page.records.length, 0);
      const total = Number(lastPage.total);
      // 还有下一页：已加载条数不足总数且本页非空（空页视为到底，防死循环）
      return loaded < total && lastPage.records.length > 0 ? allPages.length + 1 : undefined;
    },
  });

  const sessions = useMemo(
    () => sessionsQuery.data?.pages.flatMap((page) => page.records) ?? [],
    [sessionsQuery.data],
  );

  // ── 时间分组：now 取挂载时刻一次（今天/昨天/本周/更早，设计 §1.5.5）──
  const now = useMemo(() => new Date(), []);
  const grouped = useMemo(() => {
    const map = new Map<SessionTimeGroup, SessionItem[]>();
    for (const session of sessions) {
      const key = groupSessionTime(session.lastMessageAt ?? session.createdAt, now);
      const list = map.get(key);
      if (list) {
        list.push(session);
      } else {
        map.set(key, [session]);
      }
    }
    return map;
  }, [sessions, now]);

  // ── 新建对话（J7）：createSession 后跳 /chat/{id}，失败 toast 不跳转 ──
  const [creating, setCreating] = useState(false);
  const startNewSession = useCallback(async () => {
    if (creating) return;
    setCreating(true);
    try {
      const session = await createSession();
      router.push(`/chat/${session.id}`);
    } catch {
      // 创建失败：页内 toast，不跳转（用户可重试）
      notify("创建会话失败，请稍后重试");
    } finally {
      setCreating(false);
    }
  }, [creating, router, notify]);

  // ── 删除二次确认（R3）：Dialog 确认后删除；409 冲突分级 toast ──
  const [pendingDelete, setPendingDelete] = useState<SessionItem | null>(null);
  const [deleting, setDeleting] = useState(false);
  const confirmDelete = useCallback(async () => {
    if (!pendingDelete || deleting) return;
    setDeleting(true);
    try {
      await deleteSession(pendingDelete.id);
      setPendingDelete(null);
      notify("会话已删除");
      // 删除成功：失效列表查询，首屏重新拉取（页序随数据收缩重置，行为确定）
      void queryClient.invalidateQueries({ queryKey: ["sessions"] });
    } catch (error) {
      setPendingDelete(null);
      // 409：会话存在活跃 run 正在对话（R3 后端 CONFLICT），提示稍后删除
      notify(
        error instanceof ApiError && error.code === 409 ? "会话正在对话中" : "删除失败，请稍后重试",
      );
    } finally {
      setDeleting(false);
    }
  }, [pendingDelete, deleting, queryClient, notify]);

  // Esc 关闭确认框（可访问性：与上下文抽屉同款键盘语义）
  useEffect(() => {
    if (!pendingDelete) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setPendingDelete(null);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [pendingDelete]);

  return (
    <div className="mx-auto w-full max-w-6xl px-6 pb-20">
      {/* 页头：H1 + 新建对话（J7） */}
      <div className="flex flex-wrap items-end justify-between gap-4 py-10">
        <h1 className="font-display text-[30px] leading-[1.25] font-bold text-text">会话</h1>
        <button
          type="button"
          onClick={() => void startNewSession()}
          disabled={creating}
          className="inline-flex items-center gap-1.5 rounded-xl bg-brand px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-brand-strong active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
        >
          <Plus size={15} aria-hidden />
          {creating ? "创建中…" : "新建对话"}
        </button>
      </div>

      {/* 四态：Loading / Error / 空态 / 正常态 */}
      {sessionsQuery.isPending ? (
        <SessionsSkeleton />
      ) : sessionsQuery.isError ? (
        <SectionError onRetry={() => void sessionsQuery.refetch()} />
      ) : sessions.length === 0 ? (
        <EmptyState title="还没有会话记录" actionLabel="开始对话" actionHref="/chat" />
      ) : (
        <div className="space-y-8">
          {/* 时间分组：固定顺序渲染，空组跳过 */}
          {GROUP_ORDER.map(({ key, label }) => {
            const items = grouped.get(key);
            if (!items || items.length === 0) return null;
            return (
              <section key={key}>
                <h2 className="mb-3 text-sm font-medium text-muted">{label}</h2>
                <ul className="space-y-3">
                  {items.map((session) => {
                    const timeIso = session.lastMessageAt ?? session.createdAt;
                    return (
                      <li key={session.id} data-testid="session-row">
                        <div className="flex items-center gap-3 rounded-2xl border border-border bg-surface p-4 transition-[transform,opacity] hover:border-brand/30 motion-reduce:transition-none">
                          {/* 列表主体：会话标题 + 相对时间 + 状态徽章 → /chat/{id} */}
                          <Link
                            href={`/chat/${session.id}`}
                            className="flex min-w-0 flex-1 items-center gap-3"
                          >
                            <span className="min-w-0 flex-1">
                              <span className="block truncate font-medium text-text">
                                {session.title}
                              </span>
                              <time
                                dateTime={timeIso}
                                className="mt-0.5 block text-xs text-subtle tabular-nums"
                              >
                                {formatRelativeTime(timeIso, now)}
                              </time>
                            </span>
                            {/* 状态徽章：ACTIVE 进行中（teal-soft）/ CLOSED 已结束（中性） */}
                            <span
                              className={`shrink-0 rounded-full px-2.5 py-0.5 text-xs font-medium ${
                                session.status === "ACTIVE"
                                  ? "bg-brand-soft text-brand-strong"
                                  : "bg-surface-2 text-subtle"
                              }`}
                            >
                              {session.status === "ACTIVE" ? "进行中" : "已结束"}
                            </span>
                          </Link>
                          {/* 删除入口（danger 文字按钮，二次确认） */}
                          <button
                            type="button"
                            aria-label="删除会话"
                            onClick={() => setPendingDelete(session)}
                            className="flex shrink-0 items-center gap-1 rounded-lg px-2 py-1 text-xs text-danger transition-colors hover:bg-danger/10 focus-visible:ring-2 focus-visible:ring-danger"
                          >
                            <Trash size={13} aria-hidden />
                            删除
                          </button>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              </section>
            );
          })}

          {/* 分页「加载更多」：无下一页不渲染 */}
          {sessionsQuery.hasNextPage ? (
            <div className="pt-2 text-center">
              <button
                type="button"
                onClick={() => void sessionsQuery.fetchNextPage()}
                disabled={sessionsQuery.isFetchingNextPage}
                className="rounded-xl border border-brand/30 bg-surface px-6 py-2.5 text-sm font-medium text-brand-strong transition-colors hover:bg-brand-light disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
              >
                {sessionsQuery.isFetchingNextPage ? "加载中…" : "加载更多"}
              </button>
            </div>
          ) : null}
        </div>
      )}

      {/* 删除二次确认 Dialog（danger）：遮罩点击/Esc/取消均可关闭 */}
      {pendingDelete ? (
        <div className="fixed inset-0 z-50">
          <div
            data-testid="confirm-overlay"
            aria-hidden
            onClick={() => setPendingDelete(null)}
            className="absolute inset-0 animate-overlay-in bg-overlay motion-reduce:animate-none"
          />
          <div
            role="dialog"
            aria-modal="true"
            aria-label="删除会话"
            className="absolute top-1/2 left-1/2 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 animate-drawer-in rounded-2xl border border-border bg-surface p-6 shadow-xl motion-reduce:animate-none"
          >
            <h3 className="font-display text-lg font-semibold text-text">删除会话</h3>
            <p className="mt-2 text-sm leading-relaxed text-muted">
              确定删除「{pendingDelete.title}」吗？删除后不可恢复。
            </p>
            <div className="mt-6 flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setPendingDelete(null)}
                className="rounded-xl border border-border bg-surface px-4 py-2 text-sm font-medium text-muted transition-colors hover:border-brand/40 hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
              >
                取消
              </button>
              <button
                type="button"
                onClick={() => void confirmDelete()}
                disabled={deleting}
                className="rounded-xl bg-danger px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-danger/90 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-danger"
              >
                {deleting ? "删除中…" : "确认删除"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      <ChatToast message={toast} />
    </div>
  );
}
