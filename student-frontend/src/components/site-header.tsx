import Link from "next/link";

// 顶导导航模型（设计文档 §1.1）：Logo + 四个主导航 + 右侧头像区，无侧边栏
const NAV_ITEMS = [
  { href: "/", label: "首页" },
  { href: "/courses", label: "我的课程" },
  { href: "/sessions", label: "会话" },
  { href: "/profile", label: "个人中心" },
] as const;

/**
 * 全站顶部导航壳（最小占位版）
 *
 * 64px 固定高度、surface 底 + 底部 1px 边框，滚动时 sticky 置顶。
 * 当前为工程初始化占位：导航激活态 teal 短下划线与头像下拉
 * （displayName + 退出登录）随路由与认证任务落地后补齐。
 */
export function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 h-16 border-b border-border bg-surface">
      <div className="mx-auto flex h-full w-full max-w-6xl items-center justify-between px-6">
        {/* Logo 占位：几何徽标随首页任务落地，先以品牌名文字承载 */}
        <Link href="/" className="font-display text-lg font-bold tracking-tight text-text">
          课程助手
        </Link>
        <nav aria-label="主导航" className="hidden items-center gap-6 md:flex">
          {NAV_ITEMS.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="rounded-xl px-2 py-1 text-sm text-muted transition-colors hover:text-brand-strong"
            >
              {item.label}
            </Link>
          ))}
        </nav>
        {/* 头像占位：认证任务落地后替换为 displayName 首字母与下拉菜单 */}
        <div
          aria-hidden
          className="flex h-8 w-8 items-center justify-center rounded-full border border-border bg-surface-2 text-xs text-subtle"
        >
          学
        </div>
      </div>
    </header>
  );
}
