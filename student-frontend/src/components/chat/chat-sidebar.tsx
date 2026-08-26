"use client";

/**
 * 课程助手左侧栏（UI 重构 2026-08-25：kimi 式对话应用壳；会话管理化 2026-08-26）
 *
 * 结构（对齐 kimi 设计稿 assets/kimi.css 侧边栏）：品牌区 → 新建对话按钮（Ctrl+K 快捷键）
 * → 搜索框（标题模糊搜索，300ms 防抖）→ 会话历史列表（分页加载、当前会话激活态、
 * hover 行内操作：编辑标题 / 删除）→ 底部用户区（渐变头像 + 显示名 + 退出）。
 * 折叠态 260px↔64px 宽度过渡（200ms），偏好经 localStorage 持久化（kimi 语义）。
 *
 * 会话管理（用户拍板：会话在侧边栏单一管理，二次确认契约）：
 * - 增：新建对话无确认（无破坏性、可撤销；流式进行中禁用）
 * - 改：行内编辑标题（点编辑 → 输入 → 保存/回车，取消/Esc 放弃）
 * - 删：ConfirmDialog 二次确认；后端 409（活跃 run）→ toast「会话正在对话中」；
 *   删除当前激活会话后回 /chat 新对话
 * - 查：标题模糊搜索（后端 keyword 参数），清除按钮恢复全量列表
 * - 登出：ConfirmDialog 二次确认（用户拍板）
 *
 * 职责：纯导航壳，不承载业务状态；会话定位能力由 /chat/[id] 承担。
 */
import {
  CaretLeft,
  CaretRight,
  ChatCircleText,
  MagnifyingGlass,
  PencilSimple,
  Plus,
  SignOut,
  Sparkle,
  Trash,
  X,
} from "@phosphor-icons/react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChatToast } from "@/components/chat/chat-toast";
import { useChatStreaming } from "@/components/chat/chat-streaming-context";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { ApiError, deleteSession, getSessions, updateSessionTitle } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import type { SessionItem } from "@/lib/types";

/** 侧栏折叠偏好 localStorage 键（布局状态持久化，kimi 语义） */
const COLLAPSE_STORAGE_KEY = "cc.chat-sidebar.collapsed";
/** 会话历史查询键（导出共享：工作区发送消息后按此失效，保证新会话即时进侧栏） */
export const SIDEBAR_SESSIONS_QUERY_KEY = ["chat-sidebar-sessions"] as const;
/** 会话列表每页容量（分页加载，加载更多逐页追加） */
const SIDEBAR_SESSION_PAGE_SIZE = 20;
/** 搜索防抖窗口（毫秒）：输入静默后才发起 keyword 查询 */
const SEARCH_DEBOUNCE_MS = 300;
/** toast 展示时长（毫秒，到时自动消失） */
const TOAST_DURATION_MS = 2400;
/** 会话标题最大长度（与后端 @Size(max=300) 对齐，前端先行限制） */
const TITLE_MAX_LENGTH = 300;

/**
 * 课程助手左侧栏（可折叠）
 */
export function ChatSidebar() {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { user, logout } = useAuth();
  // 流式守卫：工作区正在生成时禁用新建对话跳转（导航重挂载会丢进行中的流视图）
  const isStreaming = useChatStreaming();
  const [collapsed, setCollapsed] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  // 登出二次确认（用户拍板：登出必须确认）
  const [logoutConfirmOpen, setLogoutConfirmOpen] = useState(false);

  // 折叠态初始化：读取本地偏好（SSR / 隐私模式降级展开态），切换时写回
  useEffect(() => {
    try {
      setCollapsed(window.localStorage.getItem(COLLAPSE_STORAGE_KEY) === "1");
    } catch {
      // localStorage 不可用（隐私模式等）按展开处理
    }
  }, []);
  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        window.localStorage.setItem(COLLAPSE_STORAGE_KEY, next ? "1" : "0");
      } catch {
        // 同上
      }
      return next;
    });
  };

  // Ctrl/Cmd+K 新建对话快捷键（kimi 语义；浏览器聚焦输入框时由应用层快捷键先行）
  // 流式进行中忽略：跳转会重挂载工作区致流式状态整体丢失（chat-workspace 实证注释）
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        if (isStreaming) return;
        event.preventDefault();
        router.push("/chat");
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [router, isStreaming]);

  // ── 会话搜索与列表（查）──
  // 搜索词防抖后再驱动查询（消除每键请求抖动）；keyword 为空 = 全量列表
  const [keyword, setKeyword] = useState("");
  const debouncedKeyword = useDebouncedValue(keyword, SEARCH_DEBOUNCE_MS);

  // 会话历史分页：keyword 变化即建立新查询（key 含 keyword）；工作区发送消息后
  // 按 SIDEBAR_SESSIONS_QUERY_KEY 前缀失效（invalidateQueries 前缀匹配，搜索态一并刷新）
  const sessionsQuery = useInfiniteQuery({
    queryKey: [...SIDEBAR_SESSIONS_QUERY_KEY, debouncedKeyword],
    queryFn: ({ pageParam }) =>
      getSessions(pageParam, SIDEBAR_SESSION_PAGE_SIZE, debouncedKeyword || undefined),
    initialPageParam: 1,
    getNextPageParam: (lastPage, allPages) => {
      const loaded = allPages.reduce((sum, page) => sum + page.records.length, 0);
      const total = Number(lastPage.total);
      return loaded < total && lastPage.records.length > 0 ? allPages.length + 1 : undefined;
    },
  });
  // 会话列表：分页数据展平（空态兜底稳定引用）
  const sessions = useMemo(
    () => sessionsQuery.data?.pages.flatMap((page) => page.records) ?? [],
    [sessionsQuery.data],
  );

  // ── 行内编辑（改）──：editingId = 展开编辑的行，editTitle 为该行输入值 ──
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState("");
  const [savingTitle, setSavingTitle] = useState(false);
  const editInputRef = useRef<HTMLInputElement | null>(null);

  /** 开始编辑：记录行 id 与初值并聚焦输入框 */
  function startEdit(session: SessionItem) {
    setEditingId(session.id);
    setEditTitle(session.title);
    // 聚焦等待渲染完成后执行（输入框渲染于编辑态分支）
    window.setTimeout(() => editInputRef.current?.focus(), 0);
  }

  /** 保存标题：PATCH → 失效列表（新标题生效）；空标题/未变化直接退出编辑 */
  async function saveTitle(sessionId: string) {
    const title = editTitle.trim();
    if (savingTitle) {
      return;
    }
    if (!title) {
      setEditingId(null);
      return;
    }
    setSavingTitle(true);
    try {
      await updateSessionTitle(sessionId, title);
      void queryClient.invalidateQueries({ queryKey: SIDEBAR_SESSIONS_QUERY_KEY });
      setEditingId(null);
    } catch {
      // 保存失败：保留编辑态（用户可修正重试），toast 提示
      notify("保存失败，请稍后重试");
    } finally {
      setSavingTitle(false);
    }
  }

  // 当前会话高亮：pathname /chat/{sessionId}；/chat 新对话无高亮
  const activeSessionId = pathname.startsWith("/chat/") ? pathname.slice("/chat/".length) : null;

  // ── 删除（删）──：ConfirmDialog 二次确认；409（活跃 run）toast 分级提示 ──
  const [deleteTarget, setDeleteTarget] = useState<SessionItem | null>(null);
  const [deleting, setDeleting] = useState(false);
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

  const confirmDelete = useCallback(async () => {
    if (!deleteTarget || deleting) {
      return;
    }
    const target = deleteTarget;
    setDeleting(true);
    try {
      await deleteSession(target.id);
      setDeleteTarget(null);
      // 删除当前激活会话：跳回新对话（原会话已不可再进）
      if (target.id === activeSessionId) {
        router.push("/chat");
      }
      void queryClient.invalidateQueries({ queryKey: SIDEBAR_SESSIONS_QUERY_KEY });
    } catch (error) {
      setDeleteTarget(null);
      // 409：会话存在活跃 run 正在对话（R3 后端 CONFLICT），提示稍后删除
      notify(
        error instanceof ApiError && error.code === 409 ? "会话正在对话中" : "删除失败，请稍后重试",
      );
    } finally {
      setDeleting(false);
    }
  }, [deleteTarget, deleting, activeSessionId, router, queryClient, notify]);

  /** 退出登录：二次确认后登出清凭据 → 清查询缓存 → 跳首页（登录经全局弹窗再进入） */
  async function handleLogout() {
    if (loggingOut) {
      return;
    }
    setLogoutConfirmOpen(false);
    setLoggingOut(true);
    try {
      await logout();
      queryClient.clear();
      router.push("/");
    } finally {
      setLoggingOut(false);
    }
  }

  const initial = user?.displayName?.charAt(0) || "学";

  return (
    <aside
      data-testid="chat-sidebar"
      className={`flex h-full shrink-0 flex-col border-r border-border bg-bg transition-[width] duration-200 ease-out ${
        collapsed ? "w-16" : "w-64"
      }`}
    >
      {/* 品牌行：展开=品牌+折叠开关；折叠=仅折叠开关 */}
      <div className="flex h-14 shrink-0 items-center">
        {collapsed ? (
          <button
            type="button"
            aria-label="展开侧栏"
            onClick={toggleCollapsed}
            className="mx-auto grid size-8 place-items-center rounded-lg text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
          >
            <CaretRight size={17} aria-hidden />
          </button>
        ) : (
          <div className="flex w-full items-center justify-between px-3">
            <Link
              href="/"
              className="flex items-center gap-2.5 focus-visible:ring-2 focus-visible:ring-brand"
            >
              <span className="bg-gradient-ai grid size-8 place-items-center rounded-lg text-white shadow-md shadow-brand/30">
                <Sparkle size={16} weight="fill" aria-hidden />
              </span>
              <span className="font-display text-[15px] font-bold tracking-tight text-text">
                课程助手
              </span>
            </Link>
            <button
              type="button"
              aria-label="收起侧栏"
              onClick={toggleCollapsed}
              className="grid size-8 place-items-center rounded-lg text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
            >
              <CaretLeft size={17} aria-hidden />
            </button>
          </div>
        )}
      </div>

      {/* 新建对话按钮（折叠为纯图标）；流式中置灰提示（点击仍跳转，快捷键已守卫） */}
      {collapsed ? (
        <Link
          href="/chat"
          aria-label="新建对话"
          title={isStreaming ? "正在生成回答，结束后再新建对话" : undefined}
          className={`mx-auto grid size-9 place-items-center rounded-xl border border-border bg-surface text-brand transition-colors hover:border-brand/40 hover:bg-brand-light ${
            isStreaming ? "cursor-not-allowed opacity-50" : ""
          }`}
        >
          <Plus size={16} weight="bold" aria-hidden />
        </Link>
      ) : (
        <Link
          href="/chat"
          title={isStreaming ? "正在生成回答，结束后再新建对话" : undefined}
          className="mx-2 flex h-10 shrink-0 items-center gap-2 rounded-xl border border-border bg-surface px-3 text-sm text-text transition-colors hover:border-brand/40 hover:bg-brand-light hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
        >
          <Plus size={15} weight="bold" aria-hidden className="text-brand" />
          <span className="flex-1">新建对话</span>
          <kbd className="rounded-md border border-border px-1.5 py-0.5 font-mono text-[11px] text-subtle">
            Ctrl K
          </kbd>
        </Link>
      )}

      {/* 搜索框（仅展开态）：标题模糊搜索，防抖 300ms；清除恢复全量 */}
      {!collapsed ? (
        <div className="mx-2 mt-2 shrink-0">
          <label className="relative block">
            <MagnifyingGlass
              size={15}
              aria-hidden
              className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-subtle"
            />
            <input
              type="search"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              aria-label="搜索会话"
              data-testid="sidebar-session-search"
              placeholder="搜索会话标题"
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
        </div>
      ) : null}

      {/* 会话历史区 */}
      {!collapsed ? (
        <p className="shrink-0 px-4 pt-4 pb-1.5 text-xs text-subtle">
          {debouncedKeyword ? `搜索「${debouncedKeyword}」` : "会话历史"}
        </p>
      ) : (
        <div className="h-3" />
      )}
      <nav aria-label="会话历史" className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
        {sessionsQuery.isPending ? (
          <div data-testid="sessions-skeleton" aria-busy="true" className="space-y-1.5 pt-1">
            {Array.from({ length: 4 }, (_, index) => (
              <div
                key={index}
                className={`h-9 animate-pulse rounded-lg bg-surface-2 ${collapsed ? "" : "mx-1"}`}
              />
            ))}
          </div>
        ) : sessions.length === 0 ? (
          !collapsed ? (
            <p className="px-2.5 py-2 text-xs text-subtle">
              {debouncedKeyword
                ? `没有找到「${debouncedKeyword}」相关会话`
                : "还没有会话，开始一段对话吧"}
            </p>
          ) : null
        ) : (
          <>
            <div className="space-y-0.5">
              {sessions.map((session) => {
                const active = session.id === activeSessionId;
                const href = `/chat/${session.id}`;
                // 行内编辑态：输入框 + 保存/取消（点击编辑按钮进入，二次确认语义）
                if (editingId === session.id) {
                  return (
                    <div
                      key={session.id}
                      data-testid="sidebar-session-edit"
                      className="flex h-9 items-center gap-1 rounded-lg border border-brand/30 bg-surface px-2"
                    >
                      <input
                        ref={editInputRef}
                        value={editTitle}
                        onChange={(event) => setEditTitle(event.target.value)}
                        maxLength={TITLE_MAX_LENGTH}
                        aria-label="编辑会话标题"
                        data-testid="sidebar-session-edit-input"
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            event.preventDefault();
                            void saveTitle(session.id);
                          }
                          if (event.key === "Escape") {
                            setEditingId(null);
                          }
                        }}
                        className="min-w-0 flex-1 bg-transparent text-sm text-text outline-none placeholder:text-subtle"
                      />
                      <button
                        type="button"
                        aria-label="保存标题"
                        disabled={savingTitle}
                        onClick={() => void saveTitle(session.id)}
                        className="shrink-0 rounded-md bg-brand px-2 py-0.5 text-xs font-medium text-white transition-colors hover:bg-brand-strong disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
                      >
                        {savingTitle ? "保存中…" : "保存"}
                      </button>
                      <button
                        type="button"
                        aria-label="取消编辑"
                        disabled={savingTitle}
                        onClick={() => setEditingId(null)}
                        className="shrink-0 rounded-md px-1.5 py-0.5 text-xs text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
                      >
                        取消
                      </button>
                    </div>
                  );
                }
                return (
                  <div
                    key={session.id}
                    data-testid="sidebar-session-item"
                    className={`group flex h-9 items-center rounded-lg transition-colors ${
                      collapsed ? "justify-center" : "gap-0 pr-1"
                    } ${
                      active
                        ? "bg-brand-soft font-medium text-brand-strong"
                        : "text-muted hover:bg-surface-2 hover:text-text"
                    }`}
                  >
                    <Link
                      href={href}
                      title={session.title}
                      className={`flex h-full min-w-0 flex-1 items-center rounded-lg ${
                        collapsed ? "justify-center" : "gap-2.5 px-2.5"
                      } focus-visible:ring-2 focus-visible:ring-brand`}
                    >
                      <ChatCircleText size={16} aria-hidden className="shrink-0" />
                      {!collapsed ? (
                        <span className="min-w-0 flex-1 truncate text-sm">{session.title}</span>
                      ) : null}
                    </Link>
                    {/* 行内操作（仅展开态 hover 显示）：编辑标题 / 删除（二次确认） */}
                    {!collapsed ? (
                      <div className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                        <button
                          type="button"
                          aria-label={`编辑会话标题 ${session.title}`}
                          onClick={() => startEdit(session)}
                          className="grid size-6 place-items-center rounded-md text-subtle transition-colors hover:bg-surface-2 hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
                        >
                          <PencilSimple size={13} aria-hidden />
                        </button>
                        <button
                          type="button"
                          aria-label={`删除会话 ${session.title}`}
                          onClick={() => setDeleteTarget(session)}
                          className="grid size-6 place-items-center rounded-md text-subtle transition-colors hover:bg-surface-2 hover:text-danger focus-visible:ring-2 focus-visible:ring-danger"
                        >
                          <Trash size={13} aria-hidden />
                        </button>
                      </div>
                    ) : null}
                  </div>
                );
              })}
            </div>
            {/* 分页「加载更多」：无下一页不渲染 */}
            {!collapsed && sessionsQuery.hasNextPage ? (
              <div className="px-1 pt-1.5">
                <button
                  type="button"
                  onClick={() => void sessionsQuery.fetchNextPage()}
                  disabled={sessionsQuery.isFetchingNextPage}
                  data-testid="sidebar-load-more"
                  className="w-full rounded-lg border border-border bg-surface py-1.5 text-xs font-medium text-muted transition-colors hover:border-brand/40 hover:text-brand-strong disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-brand"
                >
                  {sessionsQuery.isFetchingNextPage ? "加载中…" : "加载更多"}
                </button>
              </div>
            ) : null}
          </>
        )}
      </nav>

      {/* 底部用户区：渐变头像 + 显示名 + 退出（二次确认） */}
      <div
        className={`flex shrink-0 items-center border-t border-border py-2.5 ${
          collapsed ? "flex-col gap-2" : "gap-2.5 px-3"
        }`}
      >
        <span
          data-testid="sidebar-avatar"
          className="bg-gradient-ai grid size-8 shrink-0 place-items-center rounded-full text-xs font-bold text-white shadow-sm shadow-brand/30"
        >
          {initial}
        </span>
        {!collapsed ? (
          <>
            <span className="min-w-0 flex-1 truncate text-sm text-text">
              {user?.displayName ?? "同学"}
            </span>
            <button
              type="button"
              aria-label="退出登录"
              onClick={() => setLogoutConfirmOpen(true)}
              disabled={loggingOut}
              className="grid size-8 shrink-0 place-items-center rounded-lg text-muted transition-colors hover:bg-surface-2 hover:text-danger disabled:opacity-60"
            >
              <SignOut size={16} aria-hidden />
            </button>
          </>
        ) : null}
      </div>

      {/* 删除二次确认（danger） */}
      <ConfirmDialog
        open={deleteTarget !== null}
        title="删除会话"
        description={<span>确定删除「{deleteTarget?.title}」吗？删除后不可恢复。</span>}
        confirmText="删除"
        danger
        loading={deleting}
        onConfirm={() => void confirmDelete()}
        onCancel={() => setDeleteTarget(null)}
      />
      {/* 登出二次确认 */}
      <ConfirmDialog
        open={logoutConfirmOpen}
        title="退出登录"
        description="确定退出登录吗？退出后需要重新登录才能继续使用。"
        confirmText="退出"
        loading={loggingOut}
        onConfirm={() => void handleLogout()}
        onCancel={() => setLogoutConfirmOpen(false)}
      />
      <ChatToast message={toast} />
    </aside>
  );
}
