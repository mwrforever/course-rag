"use client";

/**
 * 滚动视差 Hook —— rAF 驱动的轻量视差（源自设计稿 hero 三层差速与 collage/tiles 视差实现）
 *
 * 返回待附着元素的 ref；元素带 data-parallax 数字时作为速度系数（像素比），否则取默认。
 * 仅当元素位于视口附近时计算位移（性能守卫），reduced-motion 下整体禁用。
 *
 * 用法：<div ref={useParallax()} data-parallax={26}>…</div>
 */
import { useEffect, useRef } from "react";

/** 创建 rAF 视差绑定 */
export function useParallax(): React.RefObject<HTMLDivElement | null> {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      return;
    }
    let rafId = 0;

    const tick = () => {
      const rect = el.getBoundingClientRect();
      const vh = window.innerHeight;
      // 性能守卫：离屏较远（上下缓冲 120px）跳过本帧
      if (rect.bottom > -120 && rect.top < vh + 120) {
        const progress = (rect.top + rect.height / 2 - vh / 2) / vh;
        const speed = Number.parseFloat(el.dataset.parallax || "") || 40;
        el.style.transform = `translate3d(0, ${(progress * speed).toFixed(1)}px, 0)`;
      }
      rafId = requestAnimationFrame(tick);
    };
    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, []);

  return ref;
}
