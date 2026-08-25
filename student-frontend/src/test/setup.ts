// Vitest 全局测试环境装配：
// 1) 注入 @testing-library/jest-dom 的 DOM 断言（toBeInTheDocument 等）
// 2) 显式注册 RTL cleanup：本项目未开启 vitest globals，RTL 自动清理不生效，
//    不显式清理会导致跨用例 DOM 累积、查询命中多元素
// 3) 注入 Node 的 TextEncoder/TextDecoder：jsdom 未实现二者（jsdom issue #2524），
//    useChatStream 的 SSE 流式解码（new TextDecoder()）在 jsdom 测试环境依赖此注入
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { TextDecoder, TextEncoder } from "node:util";
import { afterEach } from "vitest";

afterEach(() => {
  cleanup();
});

if (typeof globalThis.TextDecoder === "undefined") {
  Object.defineProperty(globalThis, "TextDecoder", { value: TextDecoder });
  Object.defineProperty(globalThis, "TextEncoder", { value: TextEncoder });
}

// IntersectionObserver 桩：motion whileInView 滚动动效在 jsdom 无真实观察器，
// 注入最小可观察接口（构造不执行回调；组件静态渲染即断言目标，覆盖无碍）
if (typeof globalThis.IntersectionObserver === "undefined") {
  class IntersectionObserverStub {
    readonly root = null;
    readonly rootMargin = "";
    readonly thresholds = [];
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
    takeRecords(): [] {
      return [];
    }
  }
  Object.defineProperty(globalThis, "IntersectionObserver", {
    value: IntersectionObserverStub,
    writable: true,
  });
}

// matchMedia 桩：useReducedMotion 在 jsdom 缺失实现会抛错（matchMedia is not a function）
if (typeof globalThis.matchMedia === "undefined") {
  Object.defineProperty(globalThis, "matchMedia", {
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }),
    writable: true,
  });
}
