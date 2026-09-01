"use client";

/**
 * rAF 常驻循环 Hook（BUG-29+PERF-23：空闲降级）
 *
 * 职责：把「每帧 tick + 尾部自排程」的常驻 requestAnimationFrame 循环收敛为
 * 统一可暂停形态，替代散落各组件的自管理 rAF 循环（hero 视差 / site-header
 * 方向感知 / scroll-progress 进度线 / useParallax 视差实例）：
 * - 页面切后台（document.hidden）：取消排程暂停，visibilitychange 回前台恢复
 * - 观察目标离视口（IntersectionObserver，上下 120px 缓冲对齐常见帧体跳过窗口）：
 *   暂停；重新入视口恢复
 * - 恢复语义「先同步执行一次 tick 再排 rAF」：补齐暂停期间的滚动位移，
 *   保证滚动视差帧率不回退（入视口无缝衔接）
 * - prefers-reduced-motion：不启动循环（动效原语的统一降级口径，调用方无需自查）
 *
 * 使用约束：
 * - tick 为纯帧体（读滚动位置/rect、写 style/class），不得自排 rAF、
 * 不得跨帧持有排程权（排程统一归本 Hook）
 * - target 传元素 ref 时按其视口可见性启停；不传/为 null 时仅受
 *   document.hidden 约束——**自隐藏元素（如吸顶栏下滑收起）必须传 null**：
 *   若按自身可见性暂停会在隐藏后死锁（循环是唯一恢复者）
 * - 帧体闭包每渲染更新经 ref 透传（读最新组件 state/ref），不重建循环
 */
import { useEffect, useRef, type RefObject } from "react";

/** IntersectionObserver 视口缓冲（上下各 120px，与帧体离屏跳过窗口对齐防边缘频繁启停） */
const VIEWPORT_ROOT_MARGIN = "120px 0px 120px 0px";

/**
 * 创建可空闲暂停的 rAF 常驻循环
 *
 * @param tick 每帧执行的纯帧体（不自排 rAF）
 * @param target 可选观察元素 ref：离视口暂停/入视口恢复；null=仅页面切后台暂停
 */
export function useRafLoop(tick: () => void, target?: RefObject<HTMLElement | null> | null): void {
  // 最新帧体引用：调用方每渲染新闭包（读最新 state/ref）也不重建循环
  const tickRef = useRef(tick);
  useEffect(() => {
    tickRef.current = tick;
  });

  useEffect(() => {
    // 动效统一降级：reduced-motion 用户不启动循环（与 Reveal/useParallax 口径一致）
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      return;
    }
    const el = target?.current ?? null;
    let rafId = 0;
    // 循环运行中标记（启动/暂停的幂等守卫）
    let running = false;
    // 目标视口可见性：初始按可见启动（离屏由观察器回调立即纠偏为暂停）
    let inViewport = true;

    /** 帧体执行并续排下一帧（running 为假即停：暂停发生在本帧期间不泄漏排程） */
    const frame = () => {
      tickRef.current();
      if (running) {
        rafId = requestAnimationFrame(frame);
      }
    };

    /** 启动/恢复：先同步补一帧（覆盖暂停期间的位移，无缝恢复），再排 rAF */
    const start = () => {
      if (running || document.hidden) return;
      running = true;
      tickRef.current();
      rafId = requestAnimationFrame(frame);
    };

    /** 暂停：取消已排程帧（幂等） */
    const stop = () => {
      if (!running) return;
      running = false;
      cancelAnimationFrame(rafId);
    };

    /** 页面可见性切换：后台暂停、回前台恢复（离屏时由 inViewport 守卫拦住） */
    const onVisibility = () => {
      if (document.hidden) {
        stop();
      } else if (inViewport) {
        start();
      }
    };

    document.addEventListener("visibilitychange", onVisibility);

    // 视口观察（无目标/环境无 IntersectionObserver 时退化为仅 document.hidden 暂停）
    let observer: IntersectionObserver | null = null;
    if (el && typeof IntersectionObserver !== "undefined") {
      observer = new IntersectionObserver(
        (entries) => {
          const entry = entries[entries.length - 1];
          if (entry) {
            inViewport = entry.isIntersecting;
          }
          if (inViewport && !document.hidden) {
            start();
          } else {
            stop();
          }
        },
        { rootMargin: VIEWPORT_ROOT_MARGIN },
      );
      observer.observe(el);
    }

    // 初始启动（后台挂载/离屏场景由上述监听立即纠偏为暂停）
    start();

    // 卸载清理：停循环、摘监听、断观察
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
      observer?.disconnect();
    };
  }, [target]);
}
