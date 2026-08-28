"use client";

/**
 * 会话搜索浮层面板（Task 13 弹窗化：搜索框 → 聚焦弹出的浮层结果列表）
 *
 * 交互契约：
 * - 聚焦搜索框（或键入）→ 浮层展开：独立查询 /sessions?keyword（与侧栏主列表解耦，
 *   主列表恒全量分页，搜索结果只活在浮层内）
 * - keyword 经 useDebouncedValue 300ms 防抖后驱动查询（空 keyword = 全量第一页）
 * - 结果列表 max-h-72 滚动；空结果 → 空态文案（携带关键词）
 * - 点击结果 → 跳转 /chat/{id} 并收起浮层；Esc / 点击面板外 → 收起（输入保留）
 *
 * 渲染：浮层 absolute 挂搜索框正下方（z-30 盖住会话列表）；外点关闭经
 * document mousedown + 容器 containment 判定（子元素点击不误关）。
 */
import { ChatCircleText, MagnifyingGlass, X } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { getSessions } from "@/lib/api";

/** 搜索防抖窗口（毫秒）：输入静默后才发起 keyword 查询 */
const SEARCH_DEBOUNCE_MS = 300;
/** 浮层结果页容量（首屏全量/关键词命中均取第一页） */
const SEARCH_PAGE_SIZE = 20;
/** 搜索浮层独立查询键前缀（与侧栏主列表缓存隔离，互不失效） */
const SESSION_SEARCH_QUERY_KEY = "chat-sidebar-session-search" as const;

/**
 * 会话搜索浮层面板（搜索框 + 浮层结果列表）
 */
export function SessionSearchPanel() {
  const router = useRouter();
  // 搜索词与浮层展开态（聚焦打开，Esc/外点/选中关闭）
  const [keyword, setKeyword] = useState("");
  const [open, setOpen] = useState(false);
  const debouncedKeyword = useDebouncedValue(keyword, SEARCH_DEBOUNCE_MS);
  // 浮层根容器引用：外点 containment 判定（点击面板内不关闭）
  const rootRef = useRef<HTMLDivElement | null>(null);

  // 浮层打开时才查询（侧栏挂载不空跑请求）；keyword 变化经防抖驱动新查询
  const resultsQuery = useQuery({
    queryKey: [SESSION_SEARCH_QUERY_KEY, debouncedKeyword],
    queryFn: () => getSessions(1, SEARCH_PAGE_SIZE, debouncedKeyword || undefined),
    enabled: open,
  });
  const sessions = resultsQuery.data?.records ?? [];

  // Esc 关闭 + 外点关闭（浮层展开期间挂载监听，收起即清理）
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    const onMouseDown = (event: MouseEvent) => {
      // 点击浮层/输入框外部才收起（容器内部交互不误关）
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    document.addEventListener("mousedown", onMouseDown);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("mousedown", onMouseDown);
    };
  }, [open]);

  /** 选中结果：跳转会话并收起浮层（路由跳转由 /chat/[id] 承载会话定位） */
  function selectSession(sessionId: string) {
    setOpen(false);
    router.push(`/chat/${sessionId}`);
  }

  return (
    <div ref={rootRef} className="relative" data-testid="session-search-panel">
      <label className="relative block">
        <MagnifyingGlass
          size={15}
          aria-hidden
          className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-subtle"
        />
        <input
          type="text"
          value={keyword}
          onFocus={() => setOpen(true)}
          onChange={(event) => {
            setKeyword(event.target.value);
            setOpen(true);
          }}
          aria-label="搜索会话"
          data-testid="sidebar-session-search"
          placeholder="搜索会话标题"
          // 注：不用 type="search"——Chromium 原生 Esc 清空会触发 onChange 重开浮层，
          // 与「Esc 收起浮层（输入保留）」契约冲突；清除走自定义 X 钮
          className="w-full rounded-lg border border-border bg-surface py-2 pr-8 pl-8.5 text-[13px] text-text outline-none transition-colors placeholder:text-subtle focus:border-brand/50 focus-visible:ring-2 focus-visible:ring-brand"
        />
        {keyword ? (
          <button
            type="button"
            aria-label="清除搜索"
            onClick={() => setKeyword("")}
            className="absolute top-1/2 right-2 -translate-y-1/2 rounded-md p-0.5 text-subtle transition-colors hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
          >
            <X size={14} aria-hidden />
          </button>
        ) : null}
      </label>
      {/* 结果浮层：搜索框正下方，max-h-72 滚动（z-30 盖住会话列表区） */}
      {open ? (
        <div
          data-testid="session-search-dropdown"
          className="absolute inset-x-0 top-full z-30 mt-1.5 max-h-72 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-lg"
        >
          {resultsQuery.isPending ? (
            <p className="px-2.5 py-2 text-xs text-subtle">搜索中…</p>
          ) : sessions.length === 0 ? (
            <p data-testid="session-search-empty" className="px-2.5 py-2 text-xs text-subtle">
              {debouncedKeyword
                ? `没有找到「${debouncedKeyword}」相关会话`
                : "还没有会话，开始一段对话吧"}
            </p>
          ) : (
            sessions.map((session) => (
              <button
                key={session.id}
                type="button"
                data-testid="session-search-item"
                onClick={() => selectSession(session.id)}
                className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-sm text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
              >
                <ChatCircleText size={15} aria-hidden className="shrink-0" />
                <span className="min-w-0 flex-1 truncate">{session.title}</span>
              </button>
            ))
          )}
        </div>
      ) : null}
    </div>
  );
}
