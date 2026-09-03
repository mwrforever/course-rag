"use client";

/**
 * 课程助手左侧栏（UI 重构 2026-08-25：kimi 式对话应用壳；会话管理化 2026-08-26；
 * 2026-08-29 Task 13 弹窗化：行内编辑 → RenameDialog、搜索框 → SessionSearchPanel
 * 浮层、新建对话 Link → button 信号化；2026-09-02 M1：收起态逐会话图标 → 历史
 * 入口 hover 浮层（最新 10 条）、搜索统一 SessionSearchDialog 弹窗、内嵌面板下线）
 *
 * 结构（对齐 kimi 设计稿 assets/kimi.css 侧边栏）：品牌区 → 新建对话按钮（Ctrl+K 快捷键）
 * → 会话搜索入口（M1 弹窗化）→ 会话历史列表（分页加载、当前会话激活态、
 * hover 行操作：重命名 / 删除）→ 底部用户区（渐变头像 + 显示名 + 退出）。
 * 折叠态 260px↔64px 宽度过渡（200ms），偏好经 localStorage 持久化（kimi 语义）；
 * 折叠态列表区收敛为「历史」入口：hover 弹浮层（顶部搜索入口 + 最新 10 条，触屏退化 click 切换）。
 *
 * 会话管理（用户拍板：会话在侧边栏单一管理，二次确认契约）：
 * - 增：新建对话无确认（无破坏性、可撤销）——/chat 同路由经 Context 新建信号驱动
 *   工作区干净态（2026-09-01 起流式生成中也可新建：旧会话 run 在服务端继续执行，
 *   切回时经 active-run + reconnect 全量回放续流，见 chat-workspace 注释）
 * - 改：重命名弹窗 RenameDialog（预填标题 + zod 非空≤50 校验 + Enter 提交）
 * - 删：ConfirmDialog 二次确认；后端 409（活跃 run）→ toast「会话正在对话中」；
 *   删除当前激活会话后回 /chat 新对话
 * - 查：SessionSearchDialog 统一弹窗（M1：keyword 防抖 + 滚动分页 + 点击跳转；
 *   主列表恒全量分页；收起态浮层内搜索入口同接此弹窗）
 * - 登出：ConfirmDialog 二次确认（用户拍板）
 *
 * 职责：纯导航壳，不承载业务状态；会话定位能力由 /chat/[id] 承担。
 */
import {
  CaretLeft,
  CaretRight,
  ChatCircleText,
  ClockCounterClockwise,
  MagnifyingGlass,
  PencilSimple,
  Plus,
  SignIn,
  SignOut,
  Sparkle,
  Trash,
} from "@phosphor-icons/react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChatToast } from "@/components/chat/chat-toast";
import {
  useChatStreamingSessionId,
  useRequestNewChat,
} from "@/components/chat/chat-streaming-context";
import { RenameDialog } from "@/components/chat/rename-dialog";
import { SessionSearchDialog, relativeTime } from "@/components/chat/session-search-dialog";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { ApiError, deleteSession, getSessions, updateSessionTitle } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import type { SessionItem } from "@/lib/types";

/** 侧栏折叠偏好 localStorage 键（布局状态持久化，kimi 语义） */
const COLLAPSE_STORAGE_KEY = "cc.chat-sidebar.collapsed";
/** 会话历史查询键（导出共享：工作区发送消息后按此失效，保证新会话即时进侧栏） */
export const SIDEBAR_SESSIONS_QUERY_KEY = ["chat-sidebar-sessions"] as const;
/** 会话列表每页容量（分页加载，加载更多逐页追加） */
const SIDEBAR_SESSION_PAGE_SIZE = 20;
/** 收起态历史浮层容量（M1：最新 10 条，spec M1.1） */
const RECENT_POPOVER_SIZE = 10;
/** toast 展示时长（毫秒），到时自动消失） */
const TOAST_DURATION_MS = 2400;

/**
 * 课程助手左侧栏（可折叠）
 */
export function ChatSidebar() {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { user, logout, openLoginDialog } = useAuth();
  // 新建对话信号出口（/chat 同路由：驱动工作区 reset 干净态，不重挂载）
  const requestNewChat = useRequestNewChat();
  // 生成中会话定位（2026-08-27）：对应会话行渲染生成中动画（脉冲点 + 标题闪烁）
  const streamingSessionId = useChatStreamingSessionId();
  const [collapsed, setCollapsed] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  // 登出二次确认（用户拍板：登出必须确认）
  const [logoutConfirmOpen, setLogoutConfirmOpen] = useState(false);
  // ── M1 收起态交互：历史浮层展开态 + 统一搜索弹窗开关 ──
  const [historyOpen, setHistoryOpen] = useState(false);
  const [searchDialogOpen, setSearchDialogOpen] = useState(false);

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

  /**
   * 新建对话统一入口（按钮与 Ctrl+K 共用）：
   * - /chat 同路由：经 Context 新建信号驱动工作区 detach 旧流 + reset 干净态（不重挂载）
   * - 其它路由：router.push('/chat') 由页面重挂载天然干净
   * - 多会话并发（2026-09-01 用户拍板）：流式生成中也可新建——旧会话 run 继续在
   *   服务端执行，切回时经 active-run + reconnect 全量回放续流
   */
  const handleNewChat = useCallback(() => {
    if (pathname === "/chat") {
      requestNewChat();
    } else {
      router.push("/chat");
    }
  }, [pathname, requestNewChat, router]);

  // Ctrl/Cmd+K 新建对话快捷键（kimi 语义；浏览器聚焦输入框时由应用层快捷键先行）
  // 流式生成中不忽略：多会话并发允许同时开启多个问答（见 handleNewChat 注释）
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        handleNewChat();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [handleNewChat]);

  // ── 会话历史分页（主列表恒全量；keyword 搜索职责在 SessionSearchDialog 弹窗）──
  // 工作区发送消息后按 SIDEBAR_SESSIONS_QUERY_KEY 失效（新会话即时进列表）
  const sessionsQuery = useInfiniteQuery({
    queryKey: [...SIDEBAR_SESSIONS_QUERY_KEY],
    queryFn: ({ pageParam }) => getSessions(pageParam, SIDEBAR_SESSION_PAGE_SIZE),
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

  // ── M1 收起态浮层数据源：最新 10 条（复用主列表 key 前缀，工作区写入/改名/删除
  //    后的前缀失效联动覆盖本查询）；仅收起态启用，展开态主列表已承载数据不重复请求 ──
  const recentSessionsQuery = useQuery({
    queryKey: [...SIDEBAR_SESSIONS_QUERY_KEY, "recent10"],
    queryFn: () => getSessions(1, RECENT_POPOVER_SIZE),
    enabled: collapsed,
  });
  const recentSessions = useMemo(
    () => recentSessionsQuery.data?.records ?? [],
    [recentSessionsQuery.data],
  );

  // ── 轻量 toast（侧栏级状态：改名/删除分级提示，定时自动消失）──
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

  // ── 重命名（改）──：RenameDialog 弹窗（预填标题 + zod 校验，Task 13 弹窗化） ──
  const [renameTarget, setRenameTarget] = useState<SessionItem | null>(null);
  const [renaming, setRenaming] = useState(false);

  /**
   * 重命名确认：PATCH → 失效列表（新标题生效）→ 关窗
   * 失败：toast 提示且弹窗保留（用户修正后可重试）
   *
   * @param title 弹窗 zod 校验通过后的新标题（非空 ≤50 字）
   */
  const confirmRename = useCallback(
    async (title: string) => {
      if (!renameTarget || renaming) {
        return;
      }
      const target = renameTarget;
      setRenaming(true);
      try {
        await updateSessionTitle(target.id, title);
        setRenameTarget(null);
        void queryClient.invalidateQueries({ queryKey: SIDEBAR_SESSIONS_QUERY_KEY });
      } catch {
        // 保存失败：弹窗保留（用户可修正重试），toast 提示
        notify("保存失败，请稍后重试");
      } finally {
        setRenaming(false);
      }
    },
    [renameTarget, renaming, queryClient, notify],
  );

  // 当前会话高亮：pathname /chat/{sessionId}；/chat 新对话无高亮
  const activeSessionId = pathname.startsWith("/chat/") ? pathname.slice("/chat/".length) : null;

  // ── 删除（删）──：ConfirmDialog 二次确认；409（活跃 run）toast 分级提示 ──
  const [deleteTarget, setDeleteTarget] = useState<SessionItem | null>(null);
  const [deleting, setDeleting] = useState(false);

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

      {/* 新建对话按钮（折叠为纯图标）；多会话并发（2026-09-01）：流式生成中可用——
          /chat 同路由经信号 detach+reset 干净态（不重挂载），其它路由跳转 */}
      {collapsed ? (
        <button
          type="button"
          aria-label="新建对话"
          onClick={handleNewChat}
          className="mx-auto grid size-9 place-items-center rounded-xl border border-border bg-surface text-brand transition-colors hover:border-brand/40 hover:bg-brand-light focus-visible:ring-2 focus-visible:ring-brand"
        >
          <Plus size={16} weight="bold" aria-hidden />
        </button>
      ) : (
        <button
          type="button"
          onClick={handleNewChat}
          className="mx-2 flex h-10 shrink-0 items-center gap-2 rounded-xl border border-border bg-surface px-3 text-sm text-text transition-colors hover:border-brand/40 hover:bg-brand-light hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
        >
          <Plus size={15} weight="bold" aria-hidden className="text-brand" />
          <span className="flex-1 text-left">新建对话</span>
          <kbd className="rounded-md border border-border px-1.5 py-0.5 font-mono text-[11px] text-subtle">
            Ctrl K
          </kbd>
        </button>
      )}

      {/* 会话搜索入口（M1 弹窗化：统一打开 SessionSearchDialog，内嵌面板下线） */}
      {!collapsed ? (
        <button
          type="button"
          onClick={() => setSearchDialogOpen(true)}
          className="mx-2 mt-2 flex h-9 shrink-0 items-center gap-2 rounded-xl px-3 text-sm text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
        >
          <MagnifyingGlass size={15} aria-hidden />
          <span className="flex-1 text-left">搜索会话</span>
        </button>
      ) : null}

      {/* 会话历史区（M1：展开态 = 分组标题 + 分页列表；收起态 = 「历史」入口 + 浮层） */}
      {collapsed ? (
        // 收起态（M1）：逐会话图标信息量低 → 单个「历史」入口 + hover 浮层（最新 10 条）+ 搜索入口。
        // enter 挂包裹层与按钮、leave 只挂包裹层：浮层是包裹层的 DOM 子树，鼠标自按钮滑入
        // 浮层不触发 leave（mouseleave 按 DOM 包含判定）；浮层贴边 left-full（无外间距空隙，
        // 空隙会成为离开包裹层的命中死角导致浮层提前卸载）；click 切换兜底触屏（无 hover 场景）
        <div
          className="relative shrink-0 px-2 py-2"
          onMouseEnter={() => setHistoryOpen(true)}
          onMouseLeave={() => setHistoryOpen(false)}
        >
          <button
            type="button"
            data-testid="collapsed-history-entry"
            aria-label="历史会话"
            aria-expanded={historyOpen}
            onMouseEnter={() => setHistoryOpen(true)}
            onClick={() => setHistoryOpen((prev) => !prev)}
            className="mx-auto grid size-9 place-items-center rounded-xl text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
          >
            <ClockCounterClockwise size={17} aria-hidden />
          </button>
          {historyOpen ? (
            <div
              data-testid="collapsed-history-popover"
              className="absolute left-full top-0 z-40 w-64 rounded-xl border border-border bg-surface p-2 shadow-lg"
            >
              {/* 搜索入口（spec M1.1：浮层顶部）：打开统一搜索弹窗 */}
              <button
                type="button"
                onClick={() => {
                  setHistoryOpen(false);
                  setSearchDialogOpen(true);
                }}
                className="mb-1 flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-sm text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
              >
                <MagnifyingGlass size={14} aria-hidden /> 搜索会话
              </button>
              <p className="px-2 pb-1.5 text-xs text-subtle">最近会话</p>
              {recentSessions.map((session) => (
                <Link
                  key={session.id}
                  href={`/chat/${session.id}`}
                  data-testid="popover-session-item"
                  onClick={() => setHistoryOpen(false)}
                  className={`flex h-9 items-center gap-2.5 rounded-lg px-2.5 text-sm transition-colors ${
                    session.id === activeSessionId
                      ? "bg-brand-soft font-medium text-brand-strong"
                      : "text-muted hover:bg-surface-2 hover:text-text"
                  }`}
                >
                  <span className="min-w-0 flex-1 truncate">{session.title}</span>
                  <span className="shrink-0 text-xs text-subtle">
                    {relativeTime(String(session.lastMessageAt ?? session.createdAt))}
                  </span>
                </Link>
              ))}
            </div>
          ) : null}
        </div>
      ) : (
        <>
          <p className="shrink-0 px-4 pt-4 pb-1.5 text-xs text-subtle">会话历史</p>
          <nav aria-label="会话历史" className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
            {sessionsQuery.isPending ? (
              <div data-testid="sessions-skeleton" aria-busy="true" className="space-y-1.5 pt-1">
                {Array.from({ length: 4 }, (_, index) => (
                  <div key={index} className="h-9 mx-1 animate-pulse rounded-lg bg-surface-2" />
                ))}
              </div>
            ) : sessions.length === 0 ? (
              // 主列表恒全量（keyword 搜索在弹窗内）：空态即无任何会话
              <p className="px-2.5 py-2 text-xs text-subtle">还没有会话，开始一段对话吧</p>
            ) : (
              <>
                <div className="space-y-0.5">
                  {sessions.map((session) => {
                    const active = session.id === activeSessionId;
                    const href = `/chat/${session.id}`;
                    return (
                      <div
                        key={session.id}
                        data-testid="sidebar-session-item"
                        className={`group flex h-9 items-center gap-0 rounded-lg pr-1 transition-colors ${
                          active
                            ? "bg-brand-soft font-medium text-brand-strong"
                            : "text-muted hover:bg-surface-2 hover:text-text"
                        }`}
                      >
                        <Link
                          href={href}
                          title={session.title}
                          className="flex h-full min-w-0 flex-1 items-center gap-2.5 rounded-lg px-2.5 focus-visible:ring-2 focus-visible:ring-brand"
                        >
                          {/* 生成中动画（2026-08-27）：该会话正在流式生成时脉冲点 + 图标换旋转指示 */}
                          {session.id === streamingSessionId ? (
                            <>
                              <span
                                data-testid="session-generating-dot"
                                aria-label="回答生成中"
                                className="size-1.5 shrink-0 animate-pulse rounded-full bg-brand motion-reduce:animate-none"
                              />
                              <ChatCircleText
                                size={16}
                                aria-hidden
                                className="shrink-0 animate-pulse text-brand motion-reduce:animate-none"
                              />
                            </>
                          ) : (
                            <ChatCircleText size={16} aria-hidden className="shrink-0" />
                          )}
                          <span className="min-w-0 flex-1 truncate text-sm">{session.title}</span>
                        </Link>
                        {/* 行操作（hover 显示）：重命名（弹窗）/ 删除（二次确认） */}
                        <div className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                          <button
                            type="button"
                            aria-label={`编辑会话标题 ${session.title}`}
                            onClick={() => setRenameTarget(session)}
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
                      </div>
                    );
                  })}
                </div>
                {/* 分页「加载更多」：无下一页不渲染 */}
                {sessionsQuery.hasNextPage ? (
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
        </>
      )}

      {/* 底部用户区：已登录 = 渐变头像 + 显示名 + 退出（二次确认）；未登录 = 登录入口（2026-08-26） */}
      {user ? (
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
                {user.displayName ?? "同学"}
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
      ) : (
        <div className="shrink-0 border-t border-border py-2.5 px-3">
          <button
            type="button"
            onClick={() => openLoginDialog()}
            data-testid="sidebar-login"
            className={`flex items-center justify-center gap-2 rounded-lg bg-gradient-ai text-sm font-medium text-white shadow-md shadow-brand/30 transition-[transform,opacity] hover:-translate-y-0.5 active:translate-y-0 focus-visible:ring-2 focus-visible:ring-brand ${
              collapsed ? "size-8" : "w-full py-2"
            }`}
          >
            <SignIn size={16} aria-hidden />
            {!collapsed ? "登录" : null}
          </button>
        </div>
      )}

      {/* 重命名弹窗（Task 13 弹窗化：预填标题 + zod 非空≤50 校验） */}
      <RenameDialog
        open={renameTarget !== null}
        initialTitle={renameTarget?.title ?? ""}
        onConfirm={(title) => confirmRename(title)}
        onCancel={() => setRenameTarget(null)}
      />
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
      {/* 统一搜索弹窗（M1：展开态搜索按钮 + 收起态浮层搜索入口共接；portal 挂 body；
          挂侧栏组件级 → (chat) layout 常驻跨路由保持，点击结果经 onClose 关闭） */}
      <SessionSearchDialog open={searchDialogOpen} onClose={() => setSearchDialogOpen(false)} />
      <ChatToast message={toast} />
    </aside>
  );
}
