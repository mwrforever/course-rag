"use client";

/**
 * 滚动入场原语 —— 方向感知 reveal 系统（源自设计稿逐帧实现）
 *
 * 职责：
 * - {@link Reveal}：单元素滚动入场包装器。进入视口按元素所处位置决定上/下位移方向
 *   （上半屏从下浮入、下半屏从上沉入，模拟「读到哪里、哪里醒来」的自然节律），
 *   支持 left/right/scale/blur 四种方向变体与 --d 延迟参数（CSS 侧承担全部动画，JS 只做触发）。
 *   默认可重复触发（离开视口复位，再次进入重新入场——多次滚动与反向滚动均有动画，
 *   2026-08-27 用户拍板）；once=true 时进场定格。
 *   根修（2026-08-27）：组件自身输出 `reveal` 类——此前调用方漏传该类导致区块
 *   永不入场或（裸类无观察器时）永久 opacity:0 空白（首页 EntryTiles 事故根因）。
 * - {@link Stagger}：子项错峰容器——为直接子元素注入递增 --i 序号索引，
 *   配合 CSS `.stagger>*{--rd:calc(var(--i)*.09s)}` 实现 stagger 进场。
 *
 * 可访问性：prefers-reduced-motion 时 IntersectionObserver 直接挂 .in（静态呈现）；
 * SSR 安全：初始 className 不含 .in（不依赖随机值），hydration 无 mismatch（2026-08-26 教训对齐）。
 */

import { useEffect, useRef } from "react";

/** 触发阈值与根边距（与设计稿一致：进入视口 12%、上下各让出 6%） */
const OBSERVER_OPTIONS: IntersectionObserverInit = {
  threshold: 0.12,
  rootMargin: "-6% 0px -6% 0px",
};

interface RevealProps extends React.HTMLAttributes<HTMLDivElement> {
  /** 入场方向变体：up（默认，方向感知）/ left / right / scale / blur */
  variant?: "up" | "left" | "right" | "scale" | "blur";
  /** 动画延迟秒数（映射 CSS --d，例如 stagger 外手动错峰用） */
  delay?: number;
  /** 是否进入一次后停止观察（true=定格不回退；false=离开视口复位循环） */
  once?: boolean;
}

/**
 * 滚动入场包装器（direction-aware reveal）
 *
 * @param props.variant 入场方向（默认 up 且方向感知）
 * @param props.delay   延迟秒数（0-1 区间常用）
 * @param props.once    true 时进场即定格；默认 false 反复触发（反向滚动重新入场）
 * @param props.children 内容
 */
export function Reveal({
  variant = "up",
  delay,
  once = false,
  children,
  className,
  ...rest
}: RevealProps) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) {
      return;
    }
    // reduced-motion：跳过观察直接静态呈现
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      el.classList.add("in");
      return;
    }
    const io = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const target = entry.target as HTMLElement;
        if (entry.isIntersecting) {
          // 方向感知：视口下半屏从下方浮入（--ty 正值）、上半屏从上方沉入
          if ((target.dataset.anim || "up") === "up") {
            target.style.setProperty(
              "--ty",
              entry.boundingClientRect.top > window.innerHeight * 0.5 ? "46px" : "-46px",
            );
          }
          // 强制 reflow 保证 --ty 在过渡启动前写入
          void target.offsetWidth;
          target.classList.add("in");
          if (once) {
            io.unobserve(target);
          }
        } else if (!once) {
          // 离开视口复位：下次进入（含反向滚动）重新入场（方向按届时位置感知）
          target.classList.remove("in");
        }
      }
    }, OBSERVER_OPTIONS);
    io.observe(el);
    return () => io.disconnect();
  }, [once]);

  return (
    <div
      ref={ref}
      data-anim={variant === "up" ? undefined : variant}
      style={delay ? ({ "--d": `${delay}s` } as React.CSSProperties) : undefined}
      {...rest}
      // 根修：reveal 类由组件自带（调用方裸 div 写 reveal 类无观察器 → 永久隐形，
      // 首页 EntryTiles 空白块事故根因）；置于 spread 之后防调用方 className 覆盖
      className={`reveal ${className ?? ""}`}
    >
      {children}
    </div>
  );
}

interface StaggerProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
}

/**
 * 子项错峰容器：渲染期为每个直接子 DOM 注入递增 --i 序号
 * （代理 ref 的 forEach 注入优于克隆子元素：保持子元素身份稳定不破 keyed reconciliation）
 */
export function Stagger({ children, ...rest }: StaggerProps) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = ref.current;
    if (!container) {
      return;
    }
    Array.from(container.children).forEach((child, index) => {
      (child as HTMLElement).style.setProperty("--i", String(index));
    });
  });

  return (
    <div ref={ref} className={`stagger ${rest.className ?? ""}`} {...rest}>
      {children}
    </div>
  );
}
