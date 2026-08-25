"use client";

/**
 * 课程助手左侧栏（UI 重构 2026-08-25：kimi 式对话应用壳）
 *
 * 结构（对齐 kimi 设计稿 assets/kimi.css 侧边栏）：品牌区 → 新建对话按钮（Ctrl+K 快捷键）
 * → 会话历史列表（最近一页、当前会话激活态、hover 色）→ 底部用户区（渐变头像 + 显示名 + 退出）。
 * 折叠态 260px↔64px 宽度过渡（200ms），偏好经 localStorage 持久化（kimi 语义）。
 *
 * 职责：纯导航壳，不承载业务状态；会话定位能力由 /chat/[id] 承担。
 */
import {
  CaretLeft,
  CaretRight,
  ChatCircleText,
  Plus,
  SignOut,
  Sparkle,
} from "@phosphor-icons/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useChatStreaming } from "@/components/chat/chat-streaming-context";
import { getSessions } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/** 侧栏折叠偏好 localStorage 键（布局状态持久化，kimi 语义） */
const COLLAPSE_STORAGE_KEY = "cc.chat-sidebar.collapsed";
/** 会话历史查询键（导出共享：工作区发送消息后按此失效，保证新会话即时进侧栏） */
export const SIDEBAR_SESSIONS_QUERY_KEY = ["chat-sidebar-sessions"] as const;
/** 会话历史侧栏拉取容量（一页即可覆盖常用会话；全量分页由 /sessions 页承担） */
const SIDEBAR_SESSION_PAGE_SIZE = 20;

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

  // 会话历史（最近一页；loading 态骨架、空态提示）
  const sessionsQuery = useQuery({
    queryKey: SIDEBAR_SESSIONS_QUERY_KEY,
    queryFn: () => getSessions(1, SIDEBAR_SESSION_PAGE_SIZE),
  });
  const sessions = sessionsQuery.data?.records ?? [];

  // 当前会话高亮：pathname /chat/{sessionId}；/chat 新对话无高亮
  const activeSessionId = pathname.startsWith("/chat/") ? pathname.slice("/chat/".length) : null;

  /** 退出登录：登出清凭据 → 清查询缓存 → 跳登录页（与个人中心同契约） */
  async function handleLogout() {
    if (loggingOut) {
      return;
    }
    setLoggingOut(true);
    try {
      await logout();
      queryClient.clear();
      router.push("/login");
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

      {/* 会话历史区 */}
      {!collapsed ? (
        <p className="shrink-0 px-4 pt-5 pb-1.5 text-xs text-subtle">会话历史</p>
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
            <p className="px-2.5 py-2 text-xs text-subtle">还没有会话，开始一段对话吧</p>
          ) : null
        ) : (
          <div className="space-y-0.5">
            {sessions.map((session) => {
              const active = session.id === activeSessionId;
              const href = `/chat/${session.id}`;
              return (
                <Link
                  key={session.id}
                  href={href}
                  title={session.title}
                  data-testid="sidebar-session-item"
                  className={`flex h-9 items-center rounded-lg transition-colors ${
                    collapsed ? "justify-center" : "gap-2.5 px-2.5"
                  } ${
                    active
                      ? "bg-brand-soft font-medium text-brand-strong"
                      : "text-muted hover:bg-surface-2 hover:text-text"
                  }`}
                >
                  <ChatCircleText size={16} aria-hidden className="shrink-0" />
                  {!collapsed ? (
                    <span className="min-w-0 flex-1 truncate text-sm">{session.title}</span>
                  ) : null}
                </Link>
              );
            })}
          </div>
        )}
      </nav>

      {/* 底部用户区：渐变头像 + 显示名 + 退出 */}
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
              onClick={() => void handleLogout()}
              disabled={loggingOut}
              className="grid size-8 shrink-0 place-items-center rounded-lg text-muted transition-colors hover:bg-surface-2 hover:text-danger disabled:opacity-60"
            >
              <SignOut size={16} aria-hidden />
            </button>
          </>
        ) : null}
      </div>
    </aside>
  );
}
