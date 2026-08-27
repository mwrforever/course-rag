"use client";

/**
 * 全站顶部导航壳（UI 全面重构 2026-08-27：问渠学堂学院风）
 *
 * 结构（设计稿一还原）：深墨顶条（客服邮箱 + 快捷锚点）｜ 吸顶主栏：
 * 左（汉堡菜单 + 搜索入口）· 中（衬线大字 Logo 居中）· 右（认证区）。
 * 主导航收进汉堡抽屉（桌面/移动共用，设计稿极简顶栏语义）；
 * 滚动行为：下滑过阈值隐藏吸顶栏（保留可用性——抽屉开启或近顶部不隐藏）、
 * 过 40px 切换磨砂玻璃底；rAF 方向感知节流。
 *
 * 登录态契约（沿用 2026-08-26 拍板）：未登录 = 文字链「登录」跳独立登录页 + 胶囊「注册」按钮；
 * 已登录 = 头像下拉（身份信息 + 个人中心 + 退出登录），登出经 ConfirmDialog 二次确认。
 * 挂载静默续期窗口（isLoading）显示骨架占位防闪变。
 * 本组件用于 (main) 路由组（首页/课堂/个人中心）；课程助手对话页使用独立侧栏壳。
 */
import { SignOut } from "@phosphor-icons/react";
import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { useAuth } from "@/lib/auth-context";

import { SUPPORT_EMAIL } from "@/lib/site-config";

/** 主导航模型（课程助手对话页不在其中，见类注释；会话管理归侧边栏不设导航项） */
const NAV_ITEMS = [
  { href: "/", label: "首页" },
  { href: "/chat", label: "课程助手" },
  { href: "/courses", label: "课堂" },
] as const;

/**
 * 导航激活判定：首页精确匹配，子路前缀匹配（/chat/[id] 高亮「课程助手」）
 */
function isNavActive(pathname: string, href: string): boolean {
  return href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);
}

/**
 * 全站顶部导航壳（深墨顶条 + 吸顶主栏 + 汉堡抽屉 + 用户下拉）
 */
export function SiteHeader() {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { user, logout, isLoading } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  // 登出二次确认（用户拍板：登出必须确认）
  const [logoutConfirmOpen, setLogoutConfirmOpen] = useState(false);

  // 滚动状态：下滑隐藏吸顶栏 / 过阈值切玻璃底（方向感知 + rAF 节流）
  useEffect(() => {
    const header = document.getElementById("site-header");
    if (!header || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      return;
    }
    let lastY = window.scrollY;
    let rafId = 0;
    const tick = () => {
      const y = window.scrollY;
      const down = y > lastY && Math.abs(y - lastY) > 1;
      lastY = y;
      header.classList.toggle("scrolled", y > 40);
      // 下滑且远离顶部时隐藏（抽屉展开时不隐藏，保证可回退）
      header.classList.toggle("header-hide", down && y > 200 && !menuOpenRef.current);
      rafId = requestAnimationFrame(tick);
    };
    const menuOpenRef = { current: false };
    const syncMenuOpen = () => {
      menuOpenRef.current = menuOpen;
    };
    syncMenuOpen();
    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [menuOpen]);

  // 抽屉/用户下拉关闭：Esc + 点击外部 + 路由变化
  useEffect(() => {
    if (!menuOpen && !userMenuOpen) {
      return;
    }
    const closeAll = () => {
      setMenuOpen(false);
      setUserMenuOpen(false);
    };
    const onPointer = (event: MouseEvent | TouchEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        closeAll();
      }
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        closeAll();
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
  }, [menuOpen, userMenuOpen]);

  // 路由变化自动收起全部浮层
  useEffect(() => {
    setMenuOpen(false);
    setUserMenuOpen(false);
  }, [pathname]);

  /** 退出登录：二次确认后登出清凭据 → 清查询缓存（防账号间串数据）→ 跳首页 */
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
    <>
      {/* ===== 深墨顶条：客服邮箱 + 快捷锚点 ===== */}
      <div className="bg-ink-900 text-[10.5px] tracking-[0.14em] text-bg/85 uppercase">
        <div className="mx-auto flex h-11 w-full max-w-[1360px] items-center justify-between px-6">
          <div className="flex gap-8">
            <a href={`mailto:${SUPPORT_EMAIL}`} className="transition-colors hover:text-white">
              {SUPPORT_EMAIL}
            </a>
            <Link href="/#services" className="hidden transition-colors hover:text-white sm:inline">
              平台能力
            </Link>
          </div>
          <div className="hidden gap-8 sm:flex">
            <a
              href={`mailto:${SUPPORT_EMAIL}?subject=合作咨询`}
              className="transition-colors hover:text-white"
            >
              合作咨询
            </a>
            <Link href="/#knowledge-hub" className="transition-colors hover:text-white">
              上手指引
            </Link>
          </div>
        </div>
      </div>

      {/* ===== 吸顶主栏 ===== */}
      <header
        id="site-header"
        data-testid="site-header"
        className="sticky top-0 z-[100] text-bg transition-[transform,background,box-shadow] duration-500 ease-out"
        style={{ background: "linear-gradient(rgb(16 12 9 / 28%), rgb(16 12 9 / 0%))" }}
      >
        <div className="mx-auto grid h-[78px] w-full max-w-[1360px] grid-cols-[1fr_auto_1fr] items-center px-6 lg:h-20">
          {/* 左：汉堡 + 搜索 */}
          <div className="flex items-center gap-5">
            <button
              type="button"
              aria-label="打开菜单"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen((open) => !open)}
              data-testid="header-burger"
              className="group flex flex-col gap-1.5 p-2"
            >
              <span className="block h-[1.6px] w-[22px] bg-current transition-transform group-hover:-translate-y-0.5" />
              <span className="block h-[1.6px] w-[22px] bg-current transition-transform group-hover:translate-y-0.5" />
            </button>
            <Link
              href="/courses"
              aria-label="搜索课程"
              className="opacity-90 transition-opacity hover:opacity-100"
            >
              <svg
                width="19"
                height="19"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
                aria-hidden
              >
                <circle cx="11" cy="11" r="6.5" />
                <path d="M16 16l5 5" />
              </svg>
            </Link>
          </div>

          {/* 中：衬线大字 Logo */}
          <Link
            href="/"
            className="font-serif-display whitespace-nowrap text-xl font-medium tracking-[0.22em] focus-visible:ring-2 focus-visible:ring-bg md:text-[23px]"
          >
            <span data-testid="site-logo">问渠学堂</span>
          </Link>

          {/* 右：认证区 */}
          <div className="flex items-center justify-end gap-6">
            {user ? (
              <div ref={menuRef} className="relative">
                <button
                  type="button"
                  aria-label="用户菜单"
                  aria-expanded={userMenuOpen}
                  onClick={() => setUserMenuOpen((open) => !open)}
                  className="grid size-9 place-items-center rounded-full border border-bg/40 font-serif-display text-sm transition-colors hover:bg-bg/10"
                >
                  <span data-testid="header-avatar">{initial}</span>
                </button>

                {userMenuOpen ? (
                  <div
                    data-testid="user-menu"
                    className="absolute right-0 top-12 z-50 w-60 overflow-hidden rounded-lg border border-border bg-surface p-1.5 shadow-lg"
                  >
                    <div className="border-b border-border px-3 py-2.5 text-text">
                      <p className="text-sm font-medium">{user?.displayName ?? "未登录"}</p>
                      <p className="mt-0.5 text-xs text-muted">
                        账号 {user?.userId ?? "—"} ·{" "}
                        {user?.role === "STUDENT" ? "学生" : (user?.role ?? "—")}
                      </p>
                    </div>
                    <div className="py-1 md:hidden">
                      {[...NAV_ITEMS, { href: "/profile", label: "个人中心" }].map((item) => (
                        <Link
                          key={item.href}
                          href={item.href}
                          onClick={() => setUserMenuOpen(false)}
                          className={`block rounded-md px-3 py-2 text-sm transition-colors ${
                            isNavActive(pathname, item.href)
                              ? "font-medium text-brand-strong"
                              : "text-muted hover:bg-surface-2 hover:text-text"
                          }`}
                        >
                          {item.label}
                        </Link>
                      ))}
                    </div>
                    <div className="border-t border-border pt-1">
                      <Link
                        href="/profile"
                        onClick={() => setUserMenuOpen(false)}
                        className="hidden rounded-md px-3 py-2 text-sm text-muted transition-colors hover:bg-surface-2 hover:text-text md:block"
                      >
                        个人中心
                      </Link>
                      <button
                        type="button"
                        aria-label="退出登录"
                        onClick={() => setLogoutConfirmOpen(true)}
                        disabled={loggingOut}
                        className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-danger transition-colors hover:bg-danger/10 disabled:cursor-not-allowed disabled:opacity-60"
                      >
                        <SignOut size={15} aria-hidden />
                        {loggingOut ? "退出中…" : "退出登录"}
                      </button>
                    </div>
                  </div>
                ) : null}
              </div>
            ) : isLoading ? (
              /* 挂载静默续期窗口：骨架占位防闪变 */
              <span aria-hidden className="block size-8 animate-pulse rounded-full bg-bg/30" />
            ) : (
              <>
                <Link
                  href="/login"
                  data-testid="login-link"
                  className="relative pb-1 text-[11px] tracking-[0.16em] uppercase opacity-85 transition-opacity hover:opacity-100 after:absolute after:inset-x-0 after:bottom-0 after:h-px after:origin-right after:scale-x-0 after:bg-bg after:transition-transform after:duration-500 hover:after:origin-left hover:after:scale-x-100"
                >
                  登录
                </Link>
                <Link
                  href="/login?tab=register"
                  data-testid="register-link"
                  className="btn-pill btn-cream !py-[13px] !text-[11px]"
                >
                  注册
                </Link>
              </>
            )}
          </div>
        </div>

        {/* ===== 汉堡全屏抽屉（导航面板，桌面/移动共用） ===== */}
        {menuOpen ? (
          <div
            data-testid="nav-drawer"
            className="absolute inset-x-0 top-full border-t border-bg/15 bg-ink-800/95 backdrop-blur-md"
          >
            <nav
              aria-label="主导航"
              className="mx-auto grid w-full max-w-[1360px] gap-1 px-6 py-8 md:grid-cols-3"
            >
              {NAV_ITEMS.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`font-serif-display rounded-md px-4 py-3 text-lg transition-colors ${
                    isNavActive(pathname, item.href)
                      ? "bg-bg/10 text-bg"
                      : "text-bg/75 hover:bg-bg/5 hover:text-bg"
                  }`}
                >
                  {item.label}
                </Link>
              ))}
              {!user ? (
                <Link
                  href="/login"
                  onClick={() => setMenuOpen(false)}
                  className="rounded-md px-4 py-3 text-sm tracking-widest text-bg/75 uppercase transition-colors hover:bg-bg/5 hover:text-bg md:hidden"
                >
                  登录 / 注册
                </Link>
              ) : null}
            </nav>
          </div>
        ) : null}
      </header>

      {/* 登出二次确认（用户拍板：登出必须确认） */}
      <ConfirmDialog
        open={logoutConfirmOpen}
        title="退出登录"
        description="确定退出登录吗？退出后需要重新登录才能继续使用。"
        confirmText="退出"
        loading={loggingOut}
        onConfirm={() => void handleLogout()}
        onCancel={() => setLogoutConfirmOpen(false)}
      />
    </>
  );
}
