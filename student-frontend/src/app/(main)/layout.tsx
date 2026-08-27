import { ScrollProgress } from "@/components/scroll-progress";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";
import { QueryProvider } from "@/lib/query-provider";

/**
 * 主站路由组布局：阅读进度条 + 顶导（深墨顶条 + 吸顶主栏）+ 内容区 + 全站底栏、无侧边栏
 *
 * 挂载 react-query QueryProvider：课程/会话等服务端状态统一缓存与失效入口；
 * 与 (auth) 路由组（登录页，无顶导壳的沉浸式分栏）互斥分组。
 * UI 全面重构 2026-08-27：顶栏/底栏切换为问渠学堂学院风（设计稿一还原），
 * 新增顶部阅读进度条（ScrollProgress 固定层）。
 */
export default function MainLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <QueryProvider>
      <ScrollProgress />
      <SiteHeader />
      <main className="flex-1">{children}</main>
      <SiteFooter />
    </QueryProvider>
  );
}
