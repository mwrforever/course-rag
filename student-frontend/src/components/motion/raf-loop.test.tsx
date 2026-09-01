/**
 * useRafLoop 空闲降级契约测试（BUG-29+PERF-23）
 *
 * jsdom 无真实 rAF 调度与 IntersectionObserver 可见性语义，全链路打桩：
 * - rAF 手动队列（帧由测试驱动，确定性断言 tick 次数与排程/取消）
 * - IntersectionObserver 假实现（测试手工注入可见性变化）
 * - document.hidden 经 defineProperty 注入 + visibilitychange 派发
 *
 * 覆盖：挂载启动与逐帧续排 / 目标离视口取消暂停、入视口同步补帧续排 /
 * 页面切后台暂停、回前台恢复 / reduced-motion 不启动 / 无 target 不建观察器 /
 * 卸载清理（停循环 + 断观察器）。
 */
import { render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useRafLoop } from "./raf-loop";
import type { RefObject } from "react";

// ===== rAF 手动队列 =====

/** 已排程帧（句柄 → 回调）；帧不自动执行，由测试 flushFrames 驱动 */
const frames = new Map<number, FrameRequestCallback>();
let nextFrameId = 1;

/** 驱动当前已排程的全部帧执行一次（执行中新排的帧进入下轮） */
function flushFrames(): void {
  const pending = [...frames.values()];
  frames.clear();
  for (const callback of pending) {
    callback(0);
  }
}

/** 可控 IntersectionObserver 假实现（捕获回调供测试注入可见性变化） */
class FakeIntersectionObserver {
  static instances: FakeIntersectionObserver[] = [];
  readonly callback: IntersectionObserverCallback;
  readonly observed: Element[] = [];
  disconnected = false;

  constructor(callback: IntersectionObserverCallback) {
    this.callback = callback;
    FakeIntersectionObserver.instances.push(this);
  }
  observe(target: Element): void {
    this.observed.push(target);
  }
  unobserve(): void {}
  disconnect(): void {
    this.disconnected = true;
  }
  /** 测试驱动：注入一次可见性回调 */
  emit(isIntersecting: boolean): void {
    this.callback(
      [{ isIntersecting } as IntersectionObserverEntry],
      this as unknown as IntersectionObserver,
    );
  }
}

/** document.hidden 注入值（defineProperty getter 读取） */
let hiddenValue = false;

/** setup.ts 注入的 IO 桩（不可 configure，仅可赋值替换；afterEach 还原） */
const SetupIntersectionObserver = globalThis.IntersectionObserver;

/** 探针组件：挂 target 时把 ref 附着到 div（观察器有目标），否则不传（仅后台暂停） */
function Probe({
  tick,
  targetRef,
}: {
  tick: () => void;
  targetRef?: RefObject<HTMLDivElement | null>;
}) {
  useRafLoop(tick, targetRef);
  return targetRef ? <div ref={targetRef} data-testid="probe" /> : <div />;
}

beforeEach(() => {
  frames.clear();
  nextFrameId = 1;
  FakeIntersectionObserver.instances = [];
  hiddenValue = false;
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
    const id = nextFrameId;
    nextFrameId += 1;
    frames.set(id, callback);
    return id;
  });
  vi.stubGlobal("cancelAnimationFrame", (handle: number) => {
    frames.delete(handle);
  });
  // setup.ts 的 IO 桩不可 configure（stubGlobal 走 defineProperty 会抛错），
  // 桩声明为 writable —— 直接赋值替换为可控假实现
  globalThis.IntersectionObserver =
    FakeIntersectionObserver as unknown as typeof IntersectionObserver;
  Object.defineProperty(document, "hidden", {
    configurable: true,
    get: () => hiddenValue,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  globalThis.IntersectionObserver = SetupIntersectionObserver;
  delete (document as { hidden?: boolean }).hidden;
});

describe("useRafLoop 启动与逐帧续排", () => {
  it("挂载即同步执行首帧并持续排程；观察器附着目标元素", () => {
    const tick = vi.fn();
    const targetRef = { current: null as HTMLDivElement | null };
    const { getByTestId } = render(<Probe tick={tick} targetRef={targetRef} />);
    // 启动语义：先同步补一帧（非等首个 rAF），再排 rAF
    expect(tick).toHaveBeenCalledTimes(1);
    // 观察器已建立并观察目标（120px 缓冲口径）
    const io = FakeIntersectionObserver.instances[0];
    expect(io.observed).toContain(getByTestId("probe"));
    // 逐帧驱动：每 flush 一轮 = 执行一帧并续排下一帧
    flushFrames();
    expect(tick).toHaveBeenCalledTimes(2);
    flushFrames();
    expect(tick).toHaveBeenCalledTimes(3);
  });

  it("target 不传：不建 IntersectionObserver，循环仅受 document.hidden 约束", () => {
    const tick = vi.fn();
    render(<Probe tick={tick} />);
    expect(FakeIntersectionObserver.instances).toHaveLength(0);
    expect(tick).toHaveBeenCalledTimes(1);
    flushFrames();
    expect(tick).toHaveBeenCalledTimes(2);
  });
});

describe("useRafLoop 离视口暂停/恢复（IntersectionObserver）", () => {
  it("目标离视口：取消已排程帧停摆；重新入视口：同步补一帧再续排（无缝恢复）", () => {
    const tick = vi.fn();
    const targetRef = { current: null as HTMLDivElement | null };
    render(<Probe tick={tick} targetRef={targetRef} />);
    expect(tick).toHaveBeenCalledTimes(1);
    const io = FakeIntersectionObserver.instances[0];

    // 离视口 → 暂停：已排帧被取消，驱动不再产生 tick
    io.emit(false);
    flushFrames();
    expect(tick).toHaveBeenCalledTimes(1);

    // 入视口 → 恢复：先同步补一帧（覆盖暂停期间位移），再排新帧
    io.emit(true);
    expect(tick).toHaveBeenCalledTimes(2);
    flushFrames();
    expect(tick).toHaveBeenCalledTimes(3);
  });
});

describe("useRafLoop 页面切后台暂停/恢复（visibilitychange）", () => {
  it("hidden=true 暂停停摆；回前台同步补一帧续排", () => {
    const tick = vi.fn();
    render(<Probe tick={tick} />);
    expect(tick).toHaveBeenCalledTimes(1);

    // 切后台：取消排程，帧驱动不再产生 tick
    hiddenValue = true;
    document.dispatchEvent(new Event("visibilitychange"));
    flushFrames();
    expect(tick).toHaveBeenCalledTimes(1);

    // 回前台：先同步补一帧，再续排
    hiddenValue = false;
    document.dispatchEvent(new Event("visibilitychange"));
    expect(tick).toHaveBeenCalledTimes(2);
    flushFrames();
    expect(tick).toHaveBeenCalledTimes(3);
  });

  it("后台期间目标入视口不启动（双条件合取），回前台后才恢复", () => {
    const tick = vi.fn();
    const targetRef = { current: null as HTMLDivElement | null };
    render(<Probe tick={tick} targetRef={targetRef} />);
    const io = FakeIntersectionObserver.instances[0];

    // 切后台暂停
    hiddenValue = true;
    document.dispatchEvent(new Event("visibilitychange"));
    expect(tick).toHaveBeenCalledTimes(1);

    // 后台期间目标重新入视口：不可见守卫拦住，不恢复
    io.emit(true);
    flushFrames();
    expect(tick).toHaveBeenCalledTimes(1);

    // 回前台：恢复运行
    hiddenValue = false;
    document.dispatchEvent(new Event("visibilitychange"));
    expect(tick).toHaveBeenCalledTimes(2);
  });
});

describe("useRafLoop 降级与清理", () => {
  it("reduced-motion：循环不启动（tick 零调用、无排程）", () => {
    vi.spyOn(window, "matchMedia").mockReturnValue({ matches: true } as MediaQueryList);
    const tick = vi.fn();
    render(<Probe tick={tick} targetRef={{ current: null as HTMLDivElement | null }} />);
    expect(tick).not.toHaveBeenCalled();
    expect(frames.size).toBe(0);
  });

  it("卸载清理：停循环（取消已排帧）并断开观察器", () => {
    const tick = vi.fn();
    const targetRef = { current: null as HTMLDivElement | null };
    const { unmount } = render(<Probe tick={tick} targetRef={targetRef} />);
    const io = FakeIntersectionObserver.instances[0];
    expect(frames.size).toBe(1);

    unmount();
    // 已排帧取消 + 观察器断开（无泄漏）
    expect(frames.size).toBe(0);
    expect(io.disconnected).toBe(true);
    flushFrames();
    expect(tick).toHaveBeenCalledTimes(1);
  });
});
