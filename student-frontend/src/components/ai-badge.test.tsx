/**
 * AiBadge AI 助教人格化徽标测试（Task 8 TDD 先行用例）
 *
 * 覆盖：徽标渲染（几何渐变容器 + Sparkle 图标）；reduced-motion 静态降级
 * （检测命中 reduce 或检测不可用返回 null 时，不挂任何动画 props，呼吸动画完全关闭）。
 *
 * 说明：motion/react 以假实现注入（vi.mock 工厂），可控 reduced 状态并记录
 * motion.span 收到的动画 props，从而确定性地断言「静态降级」与「呼吸动画挂载」。
 */
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/** motion 假实现状态：reduced 可控（false/true/null）+ 记录每次 motion.span 收到的 props */
const motionMock = vi.hoisted(() => ({
  reduce: false as boolean | null,
  received: [] as Array<Record<string, unknown>>,
}));

vi.mock("motion/react", async () => {
  const React = await import("react");
  return {
    useReducedMotion: () => motionMock.reduce,
    motion: {
      span: (props: Record<string, unknown>) => {
        motionMock.received.push(props);
        // 动画 props 只作断言素材，不落到真实 DOM（jsdom 无动画驱动，避免未知属性告警）
        const { children, animate, transition, ...rest } = props;
        void animate;
        void transition;
        return React.createElement(
          "span",
          rest as React.HTMLAttributes<HTMLSpanElement>,
          children as React.ReactNode,
        );
      },
    },
  };
});

import { AiBadge } from "./ai-badge";

beforeEach(() => {
  motionMock.reduce = false;
  motionMock.received = [];
});

afterEach(() => {
  motionMock.reduce = false;
  motionMock.received = [];
});

describe("AiBadge AI 助教人格化徽标", () => {
  it("渲染徽标容器与 Sparkle 图标", () => {
    render(<AiBadge />);
    const badge = screen.getByTestId("ai-badge");
    expect(badge).toHaveClass("rounded-2xl");
    expect(badge.querySelector("svg")).not.toBeNull();
  });

  it("正常态（无 reduced-motion）：挂载 6s 缓慢呼吸动画", () => {
    motionMock.reduce = false;
    render(<AiBadge />);
    const props = motionMock.received.at(-1) as Record<string, unknown>;
    expect(props.animate).toEqual({ y: [0, -4, 0] });
    expect(props.transition).toMatchObject({ duration: 6, repeat: Infinity });
  });

  it("reduced-motion 命中：完全静态，不挂动画", () => {
    motionMock.reduce = true;
    render(<AiBadge />);
    const props = motionMock.received.at(-1) as Record<string, unknown>;
    expect(props.animate).toBeUndefined();
    expect(props.transition).toBeUndefined();
  });

  it("检测不可用（null）：按静态降级处理", () => {
    motionMock.reduce = null;
    render(<AiBadge />);
    const props = motionMock.received.at(-1) as Record<string, unknown>;
    expect(props.animate).toBeUndefined();
    expect(props.transition).toBeUndefined();
  });
});
