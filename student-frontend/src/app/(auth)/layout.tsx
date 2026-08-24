/**
 * 认证路由组布局：无顶导壳（设计文档 §1.5.7）
 *
 * teal-50 → white → stone-50 对角渐变全屏背景，承载登录页等认证场景的独立视觉结构；
 * 与 (main) 路由组（顶导壳）互斥分组。
 */
export default function AuthLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <div className="min-h-screen bg-linear-to-br from-brand-light via-surface to-bg">
      {children}
    </div>
  );
}
