/**
 * (main) 路由组页面过渡模板 —— 页面切换入场动画（卡顿治理 2026-08-26）
 *
 * template 每次导航重新渲染子页（App Router 语义，与 layout 常驻相反），
 * 配合 page-in 入场动画掩蔽「旧内容瞬间卸载 + 骨架闪入」的生硬感；
 * 只动 opacity/transform（合成层，不触发重排），280ms ease-out。
 * prefers-reduced-motion 下经 motion-safe: 变体静态降级（可访问性优先）。
 * (chat) 组不加整页过渡：聊天页为应用态，避免与流式 Provider 生命周期冲突。
 */
export default function Template({ children }: { children: React.ReactNode }) {
  return <div className="motion-safe:animate-page-in">{children}</div>;
}
