"use client";

/**
 * 滚动视差 Hook —— rAF 驱动的轻量视差（源自设计稿 hero 三层差速与 collage/tiles 视差实现）
 *
 * 返回待附着元素的 ref；元素带 data-parallax 数字时作为速度系数（像素比），否则取默认。
 * 仅当元素位于视口附近时计算位移（性能守卫），reduced-motion 下整体禁用。
 * BUG-29+PERF-23：循环改 useRafLoop 空闲降级——元素滚出视口（含 120px 缓冲）即
 * 暂停 cancelAnimationFrame，重新入视口同步补一帧再续排（滚动视差帧率不回退）；
 * 元素位移由布局滚动驱动（transform 仅 ±speed 像素），按自身可见性启停无死锁风险。
 *
 * 用法：<div ref={useParallax()} data-parallax={26}>…</div>
 */
import { useRef } from "react";
import { useRafLoop } from "@/components/motion/raf-loop";

/** 创建 rAF 视差绑定 */
export function useParallax(): React.RefObject<HTMLDivElement | null> {
  const ref = useRef<HTMLDivElement>(null);

  // 帧体：离屏跳过写样式（120px 缓冲与 useRafLoop 观察器 rootMargin 对齐，
  // 缓冲之外循环已整体暂停，此处守卫为同窗口内的写样式守卫）
  useRafLoop(() => {
    const el = ref.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const vh = window.innerHeight;
    // 性能守卫：离屏较远（上下缓冲 120px）跳过本帧
    if (rect.bottom > -120 && rect.top < vh + 120) {
      const progress = (rect.top + rect.height / 2 - vh / 2) / vh;
      const speed = Number.parseFloat(el.dataset.parallax || "") || 40;
      el.style.transform = `translate3d(0, ${(progress * speed).toFixed(1)}px, 0)`;
    }
  }, ref);

  return ref;
}
