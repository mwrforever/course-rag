/**
 * 认证路由组布局：无顶导底栏的全屏独立壳（设计稿二左右分栏沉浸式结构）
 *
 * 与 (main) 路由组互斥分组：登录页由此获得整屏自由版式；
 * 根布局保持中性（AuthProvider/全局弹窗在根层挂载，本组同样受用）。
 */
export default function AuthLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return <div className="min-h-screen bg-bg">{children}</div>;
}
