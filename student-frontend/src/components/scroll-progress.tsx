"use client";

/**
 * 顶部阅读进度条 —— 固定视口顶部的 2.5px 赭棕进度线（设计稿同款）
 *
 * rAF 节流读取滚动比例写 transform:scaleX，零布局抖动；
 * reduced-motion 下隐藏（进度条属装饰性动效，不影响信息获取）。
 */
import { useEffect, useRef } from "react";

/** 顶部阅读进度条 */
export function ScrollProgress() {
  const barRef = useRef<HTMLElement>(null);

  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      return;
    }
    let rafId = 0;
    const tick = () => {
      const el = barRef.current;
      if (el) {
        const max = document.documentElement.scrollHeight - window.innerHeight;
        const progress = max > 0 ? window.scrollY / max : 0;
        el.style.transform = `scaleX(${progress.toFixed(4)})`;
      }
      rafId = requestAnimationFrame(tick);
    };
    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, []);

  return (
    <div aria-hidden className="pointer-events-none fixed inset-x-0 top-0 z-[300] h-[2.5px]">
      <i
        ref={barRef as React.RefObject<HTMLElement | null>}
        className="block h-full w-full origin-left scale-x-0 bg-brand"
      />
    </div>
  );
}
