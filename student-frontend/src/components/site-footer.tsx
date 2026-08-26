/**
 * 全站底栏（2026-08-26 落地：首页「一行版权」升级为完整底栏，电商平台底栏语义）
 *
 * 结构：品牌区（渐变 Logo + 一句话介绍）+ 产品/资源两列站内导航 + 版权行；
 * 视觉与顶导同源（kimi 蓝系：渐变徽标 + bg-surface 面板 + border-border 分隔）。
 *
 * 实现约束：本组件为服务端组件（纯静态零 JS），**禁止引入 @phosphor-icons/react**
 * ——该库 context 模块顶层调用 createContext，RSC 层 react-server 精简导出无此 API
 * （2026-08-26 实测：layout 引用后首页全量 500，已改内联 SVG）。
 *
 * 链接契约：均为站内真实路由，无占位死链；未登录点击资源列「个人中心」由
 * middleware 带回登录弹窗，与全站公开浏览契约一致。
 * 供 (main) 路由组各页共用（首页/课堂/个人中心）；课程助手对话页使用独立侧栏壳，不含本底栏。
 */
import Link from "next/link";

/** 产品导航（与顶导 NAV_ITEMS 同源语义） */
const FOOTER_PRODUCT = [
  { href: "/", label: "首页" },
  { href: "/chat", label: "课程助手" },
  { href: "/courses", label: "课堂" },
] as const;

/** 资源导航（站内真实路由） */
const FOOTER_RESOURCES = [{ href: "/profile", label: "个人中心" }] as const;

/**
 * 全站底栏
 */
export function SiteFooter() {
  return (
    <footer data-testid="site-footer" className="border-t border-border bg-surface/60">
      <div className="mx-auto w-full max-w-6xl px-6 py-12">
        <div className="grid grid-cols-1 gap-10 md:grid-cols-[1.5fr_1fr_1fr]">
          {/* 品牌区：渐变 Logo + 品牌名 + 一句话介绍（顶导品牌语义延续；图标内联 SVG 保服务端组件纯静态） */}
          <div>
            <Link
              href="/"
              className="flex items-center gap-2.5 focus-visible:ring-2 focus-visible:ring-brand"
            >
              <span className="bg-gradient-ai grid size-9 place-items-center rounded-xl text-white shadow-md shadow-brand/30">
                <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden className="size-[18px]">
                  <path d="M12 2.5c.7 4.4 2.4 6.1 6.8 6.8-4.4.7-6.1 2.4-6.8 6.8-.7-4.4-2.4-6.1-6.8-6.8 4.4-.7 6.1-2.4 6.8-6.8Z" />
                </svg>
              </span>
              <span className="font-display text-[17px] font-bold tracking-tight text-text">
                课程助手
              </span>
            </Link>
            <p className="mt-3 max-w-xs text-sm leading-relaxed text-muted">
              课堂资料、AI 助教、对话溯源，都在一个地方
            </p>
          </div>

          {/* 产品导航列 */}
          <nav aria-label="产品导航">
            <h3 className="text-sm font-medium text-text">产品</h3>
            <ul className="mt-3 space-y-2.5">
              {FOOTER_PRODUCT.map((item) => (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    className="text-sm text-muted transition-colors hover:text-brand-strong"
                  >
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>

          {/* 资源导航列 */}
          <nav aria-label="资源导航">
            <h3 className="text-sm font-medium text-text">资源</h3>
            <ul className="mt-3 space-y-2.5">
              {FOOTER_RESOURCES.map((item) => (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    className="text-sm text-muted transition-colors hover:text-brand-strong"
                  >
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
        </div>

        {/* 版权行 */}
        <div className="mt-10 border-t border-border pt-6 text-xs text-subtle">
          © 2026 课程助手 · 保留所有权利
        </div>
      </div>
    </footer>
  );
}
