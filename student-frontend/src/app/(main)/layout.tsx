import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";
import { QueryProvider } from "@/lib/query-provider";

/**
 * 主站路由组布局：顶导 64px + 内容区 + 全站底栏、无侧边栏（设计文档 §1.1）
 *
 * 挂载 react-query QueryProvider：课程/会话等服务端状态统一缓存与失效入口（设计 §1.8）；
 * 与 (auth) 路由组（登录页，无顶导壳）互斥分组：根布局保持中性壳，
 * 顶导归属本组，登录页由此获得全屏独立的视觉结构。
 * 底栏 2026-08-26 由首页内联一行版权上移为布局级 SiteFooter（全组页面共用）。
 */
export default function MainLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <QueryProvider>
      <SiteHeader />
      <main className="flex-1">{children}</main>
      <SiteFooter />
    </QueryProvider>
  );
}
