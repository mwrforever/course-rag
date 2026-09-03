"use client";

/**
 * 会话搜索弹窗（M1：替代内嵌搜索面板，DOM portal 挂 body 避免侧栏 overflow 裁剪）
 *
 * 交互契约（spec M1.2）：
 * - 输入 300ms 防抖（useDebouncedValue）后发 getSessions(page, 20, keyword) 查询
 * - 结果列表 useInfiniteQuery 滚动分页：滚动接近底部 200ms 节流触发 fetchNextPage
 * - 关键字变化重置分页；空态/加载态/加载更多失败重试
 * - 点击结果 router.push('/chat/{id}') 并关闭；Esc/遮罩关闭
 *
 * 打开入口：展开态侧栏「搜索会话」按钮 + 收起态历史浮层内搜索入口（挂侧栏组件级，
 * 路由跳转不变；点击结果经 onClose 关闭）。非受控焦点：打开即聚焦输入框。
 */
import { ChatCircleText, MagnifyingGlass, X } from "@phosphor-icons/react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { getSessions } from "@/lib/api";

/** 搜索防抖窗口（毫秒）——沿用原内嵌面板 300ms 契约 */
const SEARCH_DEBOUNCE_MS = 300;
/** 弹窗每页容量 */
const SEARCH_PAGE_SIZE = 20;
/** 滚动加载触发节流（毫秒）：滚动事件高频，接近底部判定去抖 */
const SCROLL_THROTTLE_MS = 200;
/** 弹窗查询键（独立于侧栏主列表缓存） */
const SESSION_SEARCH_DIALOG_QUERY_KEY = "session-search-dialog" as const;

/**
 * 相对时间格式化（会话最后活跃时间 →「刚刚/x 分钟前/x 小时前/x 天前」，超 7 天落日期）
 *
 * @param iso 会话时间 ISO 串（lastMessageAt 优先，缺省回退 createdAt）
 * @returns 中文相对时间文案（收起态浮层与搜索弹窗条目共用）
 */
export function relativeTime(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(diffMs / 60_000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days <= 7) return `${days} 天前`;
  return new Date(iso).toLocaleDateString();
}

/**
 * 会话搜索弹窗（portal 挂 body，脱离侧栏 overflow/宽度约束）
 *
 * @param open    弹窗是否打开（打开才渲染并启用查询）
 * @param onClose 关闭回调（Esc/遮罩/选中结果时调用）
 */
export function SessionSearchDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const router = useRouter();
  const [keyword, setKeyword] = useState("");
  const debouncedKeyword = useDebouncedValue(keyword, SEARCH_DEBOUNCE_MS);
  const listRef = useRef<HTMLDivElement | null>(null);
  const scrollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 无限查询：keyword 变化（防抖后）queryKey 变更自动重置分页
  const resultsQuery = useInfiniteQuery({
    queryKey: [SESSION_SEARCH_DIALOG_QUERY_KEY, debouncedKeyword],
    queryFn: ({ pageParam }) =>
      getSessions(pageParam, SEARCH_PAGE_SIZE, debouncedKeyword || undefined),
    initialPageParam: 1,
    getNextPageParam: (lastPage, allPages) => {
      const loaded = allPages.reduce((sum, page) => sum + page.records.length, 0);
      const total = Number(lastPage.total);
      return loaded < total && lastPage.records.length > 0 ? allPages.length + 1 : undefined;
    },
    enabled: open,
  });
  const sessions = resultsQuery.data?.pages.flatMap((page) => page.records) ?? [];

  /** 选中结果：跳转会话并关闭弹窗（路由跳转由 /chat/[id] 承载会话定位） */
  const selectSession = useCallback(
    (sessionId: string) => {
      onClose();
      router.push(`/chat/${sessionId}`);
    },
    [onClose, router],
  );

  // Esc 关闭（弹窗打开期间挂载）
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, onClose]);

  /** 滚动接近底部（200ms 节流）触发下一页 */
  function handleScroll() {
    if (scrollTimer.current !== null) return;
    scrollTimer.current = setTimeout(() => {
      scrollTimer.current = null;
      const el = listRef.current;
      if (!el || !resultsQuery.hasNextPage || resultsQuery.isFetchingNextPage) return;
      // 距底 ≤ 80px 触发加载（与消息流吸底阈值同款）
      if (el.scrollHeight - el.scrollTop - el.clientHeight <= 80) {
        void resultsQuery.fetchNextPage();
      }
    }, SCROLL_THROTTLE_MS);
  }
  // 卸载清理节流定时器（防泄漏：挂起中的节流回调不再触发已卸载查询）
  useEffect(
    () => () => {
      if (scrollTimer.current !== null) clearTimeout(scrollTimer.current);
    },
    [],
  );

  if (!open) return null;
  // portal 挂 body：脱离侧栏 overflow/宽度约束（spec M1.2「DOM portal 避免侧栏 overflow 裁剪」）
  return createPortal(
    <div
      className="fixed inset-0 z-50 grid place-items-start justify-center pt-[15vh]"
      data-testid="session-search-dialog"
    >
      {/* 遮罩：点击关闭 */}
      <button
        type="button"
        aria-label="关闭搜索"
        className="absolute inset-0 bg-black/30"
        onClick={onClose}
      />
      <div
        role="dialog"
        aria-label="搜索会话"
        className="relative w-[min(560px,90vw)] rounded-2xl border border-border bg-surface shadow-xl"
      >
        {/* 搜索框：打开即聚焦 */}
        <div className="flex items-center gap-2 border-b border-border px-4 py-3">
          <MagnifyingGlass size={16} aria-hidden className="text-subtle" />
          <input
            autoFocus
            type="text"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            aria-label="搜索会话"
            data-testid="session-search-input"
            placeholder="搜索会话标题"
            className="flex-1 bg-transparent text-sm text-text outline-none placeholder:text-subtle"
          />
          {keyword ? (
            <button
              type="button"
              aria-label="清除搜索"
              onClick={() => setKeyword("")}
              className="rounded-md p-0.5 text-subtle hover:text-text"
            >
              <X size={14} aria-hidden />
            </button>
          ) : null}
        </div>
        {/* 结果列表：max-h 限高滚动分页 */}
        <div
          ref={listRef}
          onScroll={handleScroll}
          className="max-h-[50vh] overflow-y-auto p-2"
          data-testid="session-search-results"
        >
          {resultsQuery.isPending ? (
            <p className="px-3 py-6 text-center text-xs text-subtle">搜索中…</p>
          ) : sessions.length === 0 ? (
            <p
              data-testid="session-search-empty"
              className="px-3 py-6 text-center text-xs text-subtle"
            >
              {debouncedKeyword
                ? `没有找到「${debouncedKeyword}」相关会话`
                : "还没有会话，开始一段对话吧"}
            </p>
          ) : (
            sessions.map((session) => (
              <button
                key={session.id}
                type="button"
                data-testid="search-result-item"
                onClick={() => selectSession(session.id)}
                className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2.5 text-left transition-colors hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-brand"
              >
                <ChatCircleText size={15} aria-hidden className="shrink-0 text-subtle" />
                <span className="min-w-0 flex-1 truncate text-sm text-text">{session.title}</span>
                <span className="shrink-0 text-xs text-subtle">
                  {relativeTime(String(session.lastMessageAt ?? session.createdAt))}
                </span>
              </button>
            ))
          )}
          {/* 加载更多状态行：拉取中/失败重试/到底 */}
          {resultsQuery.isFetchingNextPage ? (
            <p className="px-3 py-2 text-center text-xs text-subtle">加载中…</p>
          ) : resultsQuery.hasNextPage && sessions.length > 0 && resultsQuery.isError ? (
            <button
              type="button"
              data-testid="search-retry-more"
              onClick={() => void resultsQuery.fetchNextPage()}
              className="w-full px-3 py-2 text-center text-xs text-brand-strong hover:underline"
            >
              加载失败，点击重试
            </button>
          ) : null}
        </div>
      </div>
    </div>,
    document.body,
  );
}
