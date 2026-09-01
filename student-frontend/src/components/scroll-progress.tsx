"use client";

/**
 * 顶部阅读进度条 —— 固定视口顶部的 2.5px 赭棕进度线（设计稿同款）
 *
 * rAF 节流读取滚动比例写 transform:scaleX，零布局抖动；
 * reduced-motion 下隐藏（进度条属装饰性动效，不影响信息获取）。
 * BUG-29+PERF-23：rAF 循环改 useRafLoop 空闲降级——本条固定视口顶恒在视口内，
 * 离屏暂停天然不适用（target 传 null），仅页面切后台暂停、回前台同步补一帧续跑。
 */
import { useRef } from "react";
import { useRafLoop } from "@/components/motion/raf-loop";

/** 顶部阅读进度条 */
export function ScrollProgress() {
  const barRef = useRef<HTMLElement>(null);

  // 帧体语义不变：读滚动比例写 scaleX（循环启停归 useRafLoop）
  useRafLoop(() => {
    const el = barRef.current;
    if (el) {
      const max = document.documentElement.scrollHeight - window.innerHeight;
      const progress = max > 0 ? window.scrollY / max : 0;
      el.style.transform = `scaleX(${progress.toFixed(4)})`;
    }
  });

  return (
    <div aria-hidden className="pointer-events-none fixed inset-x-0 top-0 z-[300] h-[2.5px]">
      <i
        ref={barRef as React.RefObject<HTMLElement | null>}
        className="block h-full w-full origin-left scale-x-0 bg-brand"
      />
    </div>
  );
}
