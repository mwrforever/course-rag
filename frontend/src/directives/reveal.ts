/**
 * v-reveal 滚动入场指令（设计稿 A1）
 *
 * 职责：元素进入视口（IntersectionObserver threshold .15）前保持隐藏态
 * （.reveal：opacity 0 + translateY(24px)），命中后加 .in 过渡到可见并停止观察。
 * 级联延迟：v-reveal="120" 写入 --d: .12s（样式见 main.css utilities 层）。
 *
 * 降级策略（无障碍底线）：
 * - 用户偏好减少动效（prefers-reduced-motion: reduce）→ 不添加隐藏类，元素原生可见；
 * - 环境无 IntersectionObserver（旧环境/测试 jsdom 未注入）→ 同上直接可见。
 *
 * 线程安全注意：观察器经 WeakMap 与元素绑定，元素卸载即随 DOM 回收，无泄漏。
 */
import type { ObjectDirective } from 'vue'

import { prefersReducedMotion } from '@/lib/motion'

/** 元素 → 其绑定的观察器（unmounted 钩子显式断开，避免僵尸观察） */
const observers = new WeakMap<HTMLElement, IntersectionObserver>()

/**
 * v-reveal 指令对象（main.ts 全局注册为 v-reveal）
 *
 * 用法：
 * - `v-reveal` —— 常规滚动入场
 * - `v-reveal="120"` —— 级联延迟 120ms（列表/网格错峰入场）
 */
export const vReveal: ObjectDirective<HTMLElement, number | undefined> = {
  mounted(el, binding) {
    // 级联延迟（毫秒）：数字修饰写入 --d 供 CSS transition 消费；非法值按 0 处理
    const delayMs = typeof binding.value === 'number' && binding.value > 0 ? binding.value : 0
    if (delayMs > 0) {
      el.style.setProperty('--d', `${delayMs / 1000}s`)
    }

    // 降级：偏好减少动效或环境不支持 IO → 不隐藏、不观察，元素直接可见
    if (prefersReducedMotion() || typeof IntersectionObserver === 'undefined') {
      return
    }

    // 隐藏态先行（CSS 见 main.css .reveal），IO 命中后 .in 过渡到可见
    el.classList.add('reveal')
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          el.classList.add('in')
          io.disconnect()
          observers.delete(el)
        }
      },
      { threshold: 0.15 },
    )
    io.observe(el)
    observers.set(el, io)
  },

  unmounted(el) {
    // 元素卸载：显式断开观察（IO 持有元素引用会阻止 GC）
    observers.get(el)?.disconnect()
    observers.delete(el)
  },
}
