"use client";

/**
 * 全站顶部导航壳（UI 重构 2026-08-25：kimi 蓝系高级感）
 *
 * 64px 固定高度、bg/80 + backdrop-blur 玻璃底 + 底部 1px 边框，滚动时 sticky 置顶。
 * 结构：渐变 Logo + 品牌名 ｜ 主导航（激活态品牌蓝字 + 下划线指示）｜ 用户区
 * （桌面渐变头像 + 移动端汉堡按钮，统一弹出下拉：身份信息 + 导航 + 个人中心 + 退出登录）。
 *
 * 下拉关闭语义（修复历史缺陷）：Esc 键盘 + 点击外部（mousedown/touchstart）+ 路由变化三重关闭；
 * 退出登录与个人中心同契约：登出清凭据 → 清 react-query 缓存 → 跳登录页。
 * 本组件仅用于 (main) 路由组（首页/课堂/会话/个人中心）；课程助手对话页使用独立 kimi 侧栏壳。
 */
import { List, SignOut, Sparkle } from "@phosphor-icons/react";
import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";

/** 主导航模型（课程助手对话页不在其中，见类注释） */
const NAV_ITEMS = [
  { href: "/", label: "首页" },
  { href: "/chat", label: "课程助手" },
  { href: "/courses", label: "课堂" },
  { href: "/sessions", label: "会话" },
] as const;

/**
 * 导航激活判定：首页精确匹配，子路前缀匹配（/chat/[id] 高亮「课程助手」）
 *
 * @param href 导航目标（NAV_ITEMS）
 * @returns 当前路由是否命中
 */
function isNavActive(pathname: string, href: string): boolean {
  return href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);
}

/**
 * 全站顶部导航壳（主导航 + 用户下拉）
 */
export function SiteHeader() {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { user, logout } = useAuth();
  // 用户下拉展开态（Esc / 点击外部 / 路由变化关闭）
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);

  // 下拉关闭：Esc 键盘 + 点击外部（修复历史「无外部点击关闭」缺陷）
  useEffect(() => {
    if (!menuOpen) {
      return;
    }
    const onPointer = (event: MouseEvent | TouchEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("touchstart", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("touchstart", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [menuOpen]);

  // 路由变化自动收起下拉（keydown 监听随 useEffect 卸载，保证不悬挂）
  useEffect(() => setMenuOpen(false), [pathname]);

  /** 退出登录：登出清凭据 → 清查询缓存（防账号间串数据）→ 跳登录页 */
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
    <header className="sticky top-0 z-40 h-16 border-b border-border bg-bg/80 backdrop-blur">
      <div className="mx-auto flex h-full w-full max-w-6xl items-center gap-4 px-6">
        {/* Logo：品牌蓝紫渐变徽标 + 品牌名 */}
        <Link
          href="/"
          className="flex shrink-0 items-center gap-2.5 focus-visible:ring-2 focus-visible:ring-brand"
        >
          <span
            data-testid="site-logo"
            className="bg-gradient-ai grid size-9 place-items-center rounded-xl text-white shadow-md shadow-brand/30"
          >
            <Sparkle size={18} weight="fill" aria-hidden />
          </span>
          <span className="font-display text-[17px] font-bold tracking-tight text-text">
            课程助手
          </span>
        </Link>

        {/* 主导航：桌面显示，激活态品牌蓝字 + 下划线指示 */}
        <nav aria-label="主导航" className="hidden flex-1 items-center gap-1 md:flex">
          {NAV_ITEMS.map((item) => {
            const active = isNavActive(pathname, item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`relative rounded-lg px-3 py-2 text-sm transition-colors ${
                  active
                    ? "font-medium text-brand-strong"
                    : "text-muted hover:bg-surface-2 hover:text-text"
                }`}
              >
                {item.label}
                {active ? (
                  <span
                    aria-hidden
                    className="absolute inset-x-3 -bottom-px h-0.5 rounded-full bg-gradient-ai"
                  />
                ) : null}
              </Link>
            );
          })}
        </nav>

        {/* 用户区：桌面渐变头像 / 移动端汉堡按钮，共用下拉面板 */}
        <div ref={menuRef} className="relative ml-auto shrink-0 md:ml-0">
          <button
            type="button"
            aria-label="用户菜单"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
            className="flex items-center gap-2 rounded-xl p-1 transition-colors hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-brand"
          >
            <span
              data-testid="header-avatar"
              className="bg-gradient-ai hidden size-8 place-items-center rounded-full text-xs font-bold text-white shadow-sm shadow-brand/30 md:grid"
            >
              {initial}
            </span>
            <span className="grid size-8 place-items-center rounded-lg border border-border bg-surface text-muted md:hidden">
              <List size={18} aria-hidden />
            </span>
          </button>

          {menuOpen ? (
            <div
              data-testid="user-menu"
              className="absolute right-0 top-12 z-50 w-60 overflow-hidden rounded-xl border border-border bg-surface p-1.5 shadow-lg"
            >
              {/* 身份信息 */}
              <div className="border-b border-border px-3 py-2.5">
                <p className="text-sm font-medium text-text">{user?.displayName ?? "未登录"}</p>
                <p className="mt-0.5 text-xs text-muted">
                  账号 {user?.userId ?? "—"} ·{" "}
                  {user?.role === "STUDENT" ? "学生" : (user?.role ?? "—")}
                </p>
              </div>
              {/* 移动端导航（桌面导航已常显，此处无需重复） */}
              <div className="py-1 md:hidden">
                {[...NAV_ITEMS, { href: "/profile", label: "个人中心" }].map((item) => (
                  <Link
                    key={item.href}
                    href={item.href}
                    onClick={() => setMenuOpen(false)}
                    className={`block rounded-lg px-3 py-2 text-sm transition-colors ${
                      isNavActive(pathname, item.href)
                        ? "font-medium text-brand-strong"
                        : "text-muted hover:bg-surface-2 hover:text-text"
                    }`}
                  >
                    {item.label}
                  </Link>
                ))}
              </div>
              {/* 个人中心（桌面常驻入口）+ 退出登录 */}
              <div className="border-t border-border pt-1">
                <Link
                  href="/profile"
                  onClick={() => setMenuOpen(false)}
                  className="hidden rounded-lg px-3 py-2 text-sm text-muted transition-colors hover:bg-surface-2 hover:text-text md:block"
                >
                  个人中心
                </Link>
                <button
                  type="button"
                  aria-label="退出登录"
                  onClick={() => void handleLogout()}
                  disabled={loggingOut}
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-danger transition-colors hover:bg-danger/10 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  <SignOut size={15} aria-hidden />
                  {loggingOut ? "退出中…" : "退出登录"}
                </button>
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </header>
  );
}
