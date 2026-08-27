/**
 * 动效偏好探测工具（v-reveal 指令 / useCountUp / DataTable 行入场共用）
 *
 * 职责：统一探测用户是否偏好减少动效（prefers-reduced-motion）。
 * 所有入场类动效（滚动 reveal / 数字滚动 / 行级联入场）在命中该偏好时
 * 必须降级为「直接呈现终态」，这是无障碍底线（设计稿 A27 的 JS 侧对应）。
 *
 * 线程安全注意：纯只读探测，无共享可变状态。
 */

/**
 * 探测当前用户是否偏好减少动效
 *
 * @returns true 表示应跳过入场动画直接呈现终态；
 *   环境不支持 matchMedia（如 SSR/旧 jsdom）时返回 false（保守起见放行动效，
 *   但各调用方仍受 CSS 层 @media 降级总开关兜底）
 */
export function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false
  }
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}
