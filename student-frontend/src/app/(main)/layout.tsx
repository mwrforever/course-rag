import { SiteHeader } from "@/components/site-header";

/**
 * 主站路由组布局：顶导 64px + 全宽内容区、无侧边栏（设计文档 §1.1）
 *
 * 与 (auth) 路由组（登录页，无顶导壳）互斥分组：根布局保持中性壳，
 * 顶导归属本组，登录页由此获得全屏独立的视觉结构。
 */
export default function MainLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <>
      <SiteHeader />
      <main className="flex-1">{children}</main>
    </>
  );
}
