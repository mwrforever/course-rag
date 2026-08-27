/**
 * 数字滚动 composable（设计稿 A2 count-up：1700ms easeOutCubic）
 *
 * 职责：目标值变化时从当前显示值用 requestAnimationFrame 平滑滚动到新目标，
 * 返回响应式当前值（整数，调用方自行格式化千分位等展示形态）。
 *
 * 降级策略：
 * - 用户偏好减少动效 → 直接跳到终值（无障碍底线）；
 * - duration ≤ 0 或环境无 requestAnimationFrame → 同上直接跳终值。
 *
 * 生命周期：基于 onScopeDispose 清理未完成的 rAF（组件卸载/作用域销毁即取消），
 * 不存在卸载后回调写已死 ref 的问题。
 *
 * 线程安全注意：rAF 单队列串行回调，实例间互不共享状态，并发安全。
 */
import { onScopeDispose, ref, toValue, watch } from 'vue'

import type { Ref, MaybeRefOrGetter } from 'vue'

import { prefersReducedMotion } from '@/lib/motion'

/** 默认滚动时长（毫秒）：设计稿 A2 实测 1700ms */
export const COUNT_UP_DURATION_MS = 1700

/** count-up 配置项 */
export interface UseCountUpOptions {
  /**
   * 滚动时长（毫秒）
   * 取值范围：>0 生效；≤0 视为禁用动画直接跳终值；缺省 1700（设计稿 A2）
   */
  duration?: number
}

/**
 * 数字滚动（目标值 → 响应式当前值）
 *
 * @param target 目标数值的响应式源：可为 Ref / 普通数字 / getter（页面传
 *   () => Number(stats.documentCount) 之类；目标变化会从当前显示值续滚）
 * @param options 配置项（duration 见 UseCountUpOptions）
 * @returns Ref<number> 当前显示值（始终为整数，初始为 0，动画结束精确等于目标值）
 */
export function useCountUp(
  target: MaybeRefOrGetter<number>,
  options: UseCountUpOptions = {},
): Ref<number> {
  const duration = options.duration ?? COUNT_UP_DURATION_MS
  /** 当前显示值（整数；easeOutCubic 各帧取整展示） */
  const current = ref(0)
  /** 未完成的 rAF 句柄（0 表示空闲） */
  let rafId = 0

  /** 取消进行中的动画帧 */
  const cancel = () => {
    if (rafId !== 0) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
  }

  /** 从 from 滚动到 to（含全部降级分支） */
  const run = (to: number) => {
    cancel()
    // 降级：减少动效偏好 / 非正时长 / 环境（旧 jsdom）无 rAF → 直接呈现终值
    if (duration <= 0 || prefersReducedMotion() || typeof requestAnimationFrame !== 'function') {
      current.value = to
      return
    }
    const from = current.value
    const start = performance.now()
    const step = (now: number) => {
      // 进度 p ∈ [0,1]；easeOutCubic = 1-(1-p)^3（设计稿 A2 缓动）
      const p = Math.min((now - start) / duration, 1)
      const eased = 1 - Math.pow(1 - p, 3)
      current.value = Math.round(from + (to - from) * eased)
      if (p < 1) {
        rafId = requestAnimationFrame(step)
      } else {
        rafId = 0
      }
    }
    rafId = requestAnimationFrame(step)
  }

  // 目标变化（含 immediate 首跑）触发滚动；数据迟到（undefined→数）安全起见由调用方保证源为 number
  watch(
    () => toValue(target),
    (to) => run(to),
    { immediate: true },
  )

  // 作用域销毁（组件卸载）清理未完成帧
  onScopeDispose(cancel)

  return current
}
