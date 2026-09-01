"use client";

/**
 * 全站顶部导航壳（2026-08-27 用户拍板改版：单层导航）
 *
 * 结构：单层吸顶栏——左（衬线 Logo）· 中左（主导航三项：首页 / 课程助手 / 课程中心，
 * 关联路由 /、/chat、/courses）· 右（认证区）。原深墨顶条与汉堡全屏抽屉按用户
 * 「不要三层、统一合并成一层」要求移除；搜索图标入口并入「课程中心」导航项。
 * 滚动行为：下滑过阈值隐藏吸顶栏、过 40px 切换磨砂玻璃底（rAF 方向感知节流；
 * BUG-29+PERF-23：循环改 useRafLoop 空闲降级——吸顶栏为自隐藏元素，若按自身
 * 可见性暂停会在隐藏后死锁，故仅页面切后台暂停）。
 *
 * 登录态契约（沿用 2026-08-26 拍板）：未登录 = 文字链「登录」跳独立登录页 + 胶囊「注册」按钮；
 * 已登录 = 头像下拉（身份信息 + 个人中心 + 退出登录），登出经 ConfirmDialog 二次确认。
 * 挂载静默续期窗口（isLoading）显示骨架占位防闪变。
 * 本组件用于 (main) 路由组（首页/课程中心/个人中心）；课程助手对话页使用独立侧栏壳
 * （侧栏品牌区提供回首页入口，导航三项在此栏承载）。
 */
import { BookOpen, SignOut, User } from "@phosphor-icons/react";
import { useQueryClient } from "@tanstack/react-query";
import { motion, useReducedMotion } from "motion/react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { useRafLoop } from "@/components/motion/raf-loop";
import { useAuth } from "@/lib/auth-context";

/** 主导航模型（单层直出：三项关联路由；课程助手对话页在侧栏壳另有入口） */
const NAV_ITEMS = [
  { href: "/", label: "首页" },
  { href: "/chat", label: "课程助手" },
  { href: "/courses", label: "课程中心" },
] as const;

/**
 * 导航激活判定：首页精确匹配，子路前缀匹配（/chat/[id] 高亮「课程助手」、
 * /courses/[id] 高亮「课程中心」）
 */
function isNavActive(pathname: string, href: string): boolean {
  return href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);
}

/**
 * 全站顶部导航壳（单层主栏 + 用户下拉）
 */
export function SiteHeader() {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { user, logout, isLoading } = useAuth();
  // 下拉入场动效降级：prefers-reduced-motion 下浮层直接呈现
  const reduceMotion = useReducedMotion() ?? true;
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const headerRef = useRef<HTMLElement | null>(null);
  // 上帧滚动位置（null=首帧，按当帧 scrollY 初始化避免中位刷新误判方向）
  const lastYRef = useRef<number | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  // 登出二次确认（用户拍板：登出必须确认）
  const [logoutConfirmOpen, setLogoutConfirmOpen] = useState(false);

  // 滚动状态：下滑隐藏吸顶栏 / 过阈值切玻璃底（方向感知 + rAF 节流；
  // 循环启停归 useRafLoop：仅页面切后台暂停——吸顶栏自隐藏，按自身可见性
  // 暂停会死锁；用户下拉开启时不隐藏，保证可回退）
  useRafLoop(() => {
    const header = headerRef.current;
    if (!header) return;
    const y = window.scrollY;
    const lastY = lastYRef.current ?? y;
    const down = y > lastY && Math.abs(y - lastY) > 1;
    lastYRef.current = y;
    header.classList.toggle("scrolled", y > 40);
    // 下滑且远离顶部时隐藏（下拉展开时不隐藏）
    header.classList.toggle("header-hide", down && y > 200 && !userMenuOpen);
  });

  // 用户下拉关闭：Esc + 点击外部 + 路由变化
  useEffect(() => {
    if (!userMenuOpen) {
      return;
    }
    const close = () => setUserMenuOpen(false);
    const onPointer = (event: MouseEvent | TouchEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        close();
      }
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        close();
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
  }, [userMenuOpen]);

  // 路由变化自动收起浮层
  useEffect(() => {
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
      {/* ===== 单层吸顶主栏：Logo · 主导航 · 认证区 ===== */}
      <header
        ref={headerRef}
        id="site-header"
        data-testid="site-header"
        className="sticky top-0 z-[100] text-bg transition-[transform,background,box-shadow] duration-500 ease-out"
        style={{ background: "linear-gradient(rgb(16 12 9 / 28%), rgb(16 12 9 / 0%))" }}
      >
        <div className="mx-auto flex h-[68px] w-full max-w-[1360px] items-center gap-4 px-4 md:h-[78px] md:gap-8 md:px-6">
          {/* 左：衬线 Logo（回首页） */}
          <Link
            href="/"
            className="font-serif-display shrink-0 text-lg font-medium tracking-[0.22em] focus-visible:ring-2 focus-visible:ring-bg md:text-[23px]"
          >
            <span data-testid="site-logo">问渠学堂</span>
          </Link>

          {/* 中：主导航三项（单层直出，激活下划线指示；移动端缩小字号并允许横向收窄） */}
          <nav
            aria-label="主导航"
            data-testid="main-nav"
            className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto md:gap-2"
          >
            {NAV_ITEMS.map((item) => {
              const active = isNavActive(pathname, item.href);
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  data-testid={`nav-${item.href === "/" ? "home" : item.href.slice(1)}`}
                  aria-current={active ? "page" : undefined}
                  className={`relative shrink-0 rounded-md px-2.5 py-2 text-[13px] tracking-[0.08em] transition-colors focus-visible:ring-2 focus-visible:ring-bg md:px-3.5 md:text-sm ${
                    active ? "text-bg" : "text-bg/75 hover:text-bg"
                  } after:absolute after:inset-x-2.5 after:bottom-0.5 after:h-px after:origin-left after:bg-bg after:transition-transform after:duration-500 md:after:inset-x-3.5 ${
                    active ? "after:scale-x-100" : "after:scale-x-0 hover:after:scale-x-100"
                  }`}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>

          {/* 右：认证区 */}
          <div className="flex shrink-0 items-center justify-end gap-4 md:gap-6">
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
                  <motion.div
                    data-testid="user-menu"
                    initial={reduceMotion ? false : { opacity: 0, y: -6, scale: 0.97 }}
                    animate={{ opacity: 1, y: 0, scale: 1 }}
                    transition={{ duration: 0.18, ease: "easeOut" }}
                    style={{ transformOrigin: "top right" }}
                    className="absolute right-0 top-12 z-50 w-60 overflow-hidden rounded-lg border border-border bg-surface p-1.5 shadow-lg"
                  >
                    <div className="border-b border-border px-3 py-2.5 text-text">
                      <p className="text-sm font-medium">{user?.displayName ?? "未登录"}</p>
                      <p className="mt-0.5 text-xs text-muted">
                        账号 {user?.userId ?? "—"} ·{" "}
                        {user?.role === "STUDENT" ? "学生" : (user?.role ?? "—")}
                      </p>
                    </div>
                    <div className="border-t border-border pt-1">
                      <Link
                        href="/my-courses"
                        onClick={() => setUserMenuOpen(false)}
                        className="flex items-center gap-2 rounded-md px-3 py-2 text-sm text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
                      >
                        <BookOpen size={15} aria-hidden />
                        我的课程
                      </Link>
                      <Link
                        href="/profile"
                        onClick={() => setUserMenuOpen(false)}
                        className="flex items-center gap-2 rounded-md px-3 py-2 text-sm text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
                      >
                        <User size={15} aria-hidden />
                        个人中心
                      </Link>
                      <button
                        type="button"
                        aria-label="退出登录"
                        onClick={() => setLogoutConfirmOpen(true)}
                        disabled={loggingOut}
                        className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-danger transition-colors hover:bg-danger/10 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-danger"
                      >
                        <SignOut size={15} aria-hidden />
                        {loggingOut ? "退出中…" : "退出登录"}
                      </button>
                    </div>
                  </motion.div>
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
