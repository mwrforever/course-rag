"use client";

/**
 * 链式时间轴节点（2026-08-28 时间线改版，设计稿 .node 复刻）
 *
 * 27px 圆形节点，承载步骤图标与三态视觉：
 * - idle：静态图标（信息展示类步骤，如查询计划）
 * - running：node-pulse box-shadow 脉冲 + 可选动画环两型
 *   （A 虚线雷达环 chain-ring-dash——检索类；B 弧线 conic 环 chain-ring-arc——工具类）
 * - done：pop 弹跳 + 右上 10px 绿点；绿色换色（chain-step--green）由步骤容器类驱动
 *
 * 动画环与绿点常驻渲染、以步骤级类（chain-step--running/done）驱动透明度切换，
 * 与设计稿「环淡入 .45s / 绿点延迟 .2s 弹出」的过渡语义一致。
 * reduced-motion 由 globals.css 全局总开关降级（动画时长 0.01ms）。
 */
import type { ReactNode } from "react";

/** 节点三态：idle=静态图标 / running=进行中 / done=已完成 */
export type ChainNodeState = "idle" | "running" | "done";

/** 运行中动画环类型：dash=虚线雷达环（检索）/ arc=弧线 conic 环（工具）/ none=无环 */
export type ChainRingKind = "dash" | "arc" | "none";

/** 节点组件 props */
export interface ChainNodeProps {
  /** 节点状态（由所属步骤的运行态推导） */
  state: ChainNodeState;
  /** 运行中动画环类型（仅 running 态可见） */
  ring?: ChainRingKind;
  /** 节点图标（13px；调用方给定 Phosphor 图标实例） */
  icon: ReactNode;
  /** 完成态图标（如 Brain → Check；缺省沿用 icon） */
  doneIcon?: ReactNode;
}

/**
 * 时间轴节点（纯展示组件，状态由步骤容器推导传入）
 *
 * @param props 见 ChainNodeProps
 */
export function ChainNode({ state, ring = "none", icon, doneIcon }: ChainNodeProps) {
  const done = state === "done";
  return (
    <span
      data-testid="chain-node"
      aria-hidden
      className={`chain-node ${state === "running" ? "chain-node--pulse" : ""}`}
    >
      {/* 运行中动画环：常驻渲染，透明度由步骤级 chain-step--running 驱动淡入 */}
      {ring === "dash" ? <span className="chain-ring-dash" /> : null}
      {ring === "arc" ? <span className="chain-ring-arc" /> : null}
      {/* 完成态换图标（思考节点 Brain → Check），否则沿用运行/静态图标 */}
      {done && doneIcon ? doneIcon : icon}
      {/* 完成态右上绿点（透明度/缩放由步骤级 chain-step--done 驱动） */}
      {done ? <span className="chain-node-dot" /> : null}
    </span>
  );
}
