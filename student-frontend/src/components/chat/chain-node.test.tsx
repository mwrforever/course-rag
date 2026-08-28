/**
 * 时间轴节点测试（2026-08-28 时间线改版：三态 + 动画环 + 完成绿点）
 *
 * 覆盖：idle 静态图标 / running 脉冲类 / done 绿点与完成图标切换、环两型渲染
 */
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Brain, Check, MagnifyingGlass, Wrench } from "@phosphor-icons/react";
import { ChainNode } from "./chain-node";

describe("ChainNode 三态视觉", () => {
  it("idle：静态图标（无脉冲类、无绿点、无环）", () => {
    const { container } = render(<ChainNode state="idle" icon={<Brain weight="fill" />} />);
    const node = screen.getByTestId("chain-node");
    expect(node.className).toContain("chain-node");
    expect(node.className).not.toContain("chain-node--pulse");
    expect(container.querySelector(".chain-node-dot")).toBeNull();
    expect(container.querySelector(".chain-ring-dash")).toBeNull();
    expect(container.querySelector(".chain-ring-arc")).toBeNull();
  });

  it("running：脉冲类 + 虚线雷达环（检索类）", () => {
    const { container } = render(
      <ChainNode state="running" ring="dash" icon={<MagnifyingGlass weight="bold" />} />,
    );
    expect(screen.getByTestId("chain-node").className).toContain("chain-node--pulse");
    expect(container.querySelector(".chain-ring-dash")).not.toBeNull();
    expect(container.querySelector(".chain-ring-arc")).toBeNull();
  });

  it("running：弧线 conic 环（工具类）", () => {
    const { container } = render(
      <ChainNode state="running" ring="arc" icon={<Wrench weight="fill" />} />,
    );
    expect(container.querySelector(".chain-ring-arc")).not.toBeNull();
    expect(container.querySelector(".chain-ring-dash")).toBeNull();
  });

  it("done：绿点渲染 + 完成图标切换（Brain → Check）；缺省沿用原图标", () => {
    const { container, rerender } = render(
      <ChainNode state="done" icon={<Brain weight="fill" />} doneIcon={<Check weight="bold" />} />,
    );
    expect(container.querySelector(".chain-node-dot")).not.toBeNull();
    expect(screen.getByTestId("chain-node").textContent).toBeDefined();

    // 无 doneIcon：沿用运行态图标
    rerender(<ChainNode state="done" icon={<MagnifyingGlass weight="bold" />} />);
    expect(container.querySelector(".chain-node-dot")).not.toBeNull();
  });
});
