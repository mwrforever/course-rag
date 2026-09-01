"use client";

/**
 * 链式时间轴容器（2026-08-28 时间线改版，设计稿 .chain 复刻；2026-08-30 对齐设计稿）
 *
 * 一根渐变竖线串联全部步骤：StreamMessage.timeline 按到达序逐节点渲染——
 * - thinking 节点 → ThinkingStep（Brain → 绿勾，mask 收起展开；按 LLM 调用拆分，
 *   主 agent 每次模型调用一块思考卡）
 * - sources 节点 → 检索步骤（MagnifyingGlass + 虚线雷达环；完成文案「已检索 N 篇相关资料」，
 *   点击打开召回抽屉——来源事件即检索完成事实，到达即为 done 态）
 * - tool 节点 → 工具步骤（Wrench + 弧线 conic 环；pending 跳动点、success 摘要 +
 *   完成态点击打开工具结果抽屉——2026-08-30 工具结果侧栏展示，替代原内嵌 JSON）
 *
 * 运行态推导：末节点且消息流式中视为进行中（thinking 以 ended、tool 以 status
 * 各自内聚判定完成）；历史消息（active=false）全部呈现完成态。
 * 2026-08-30 移除：stage 阶段节点（「正在生成回答」等阶段文案）与 queryPlan 查询计划
 * 节点（「未识别意图」/重写查询清单）——设计稿无对应元素，前端不再渲染。
 */
import { MagnifyingGlass, Wrench } from "@phosphor-icons/react";
import { memo, useCallback } from "react";
import { OpStep, summarizeOutput, toolNameLabel } from "./op-step";
import { ThinkingStep } from "./thinking-step";
import type { RetrievalSource, TimelineNode, TimelineToolNode } from "@/lib/types";

/** 链式时间轴 props */
export interface ChainTimelineProps {
  /** 时间轴节点序列（SSE 到达序 / 历史行 seq 序重建） */
  timeline: TimelineNode[];
  /** 所属消息是否流式进行中（末节点 running 判定；历史消息恒 false） */
  active: boolean;
  /**
   * 本条消息的来源列表（PERF-05：以独立 props 传入而非调用方闭包捕获——
   * reducer delta 分支保留 sources 引用不变，引用稳定可令 memo 在正文 delta
   * 期间整轴跳过重渲染；点击来源步骤时由本组件内部闭包交给 onOpenSources）
   */
  sources: RetrievalSource[];
  /** 打开召回抽屉回调（稳定引用，接收上方 sources 原样透传） */
  onOpenSources: (sources: RetrievalSource[]) => void;
  /** 打开工具结果抽屉回调（tool 步骤完成态点击，2026-08-30 工具结果侧栏展示） */
  onOpenTool: (tool: TimelineToolNode) => void;
}

/**
 * 节点步骤运行态推导：末节点且消息流式中视为进行中；
 * thinking 以 ended、tool 以 status=pending 各自内聚判定（后到的思考/工具
 * 在前序节点未收尾的异常时序下仍能正确呈现各自状态）
 *
 * @param node 时间轴节点
 * @param isLast 是否为末节点
 * @param active 消息是否流式中
 * @returns 该步骤是否进行中
 */
export function isNodeRunning(node: TimelineNode, isLast: boolean, active: boolean): boolean {
  if (!active || !isLast) return false;
  if (node.kind === "thinking") return !node.ended;
  if (node.kind === "tool") return node.status === "pending";
  return true;
}

/**
 * 链式时间轴容器（竖线 + 逐节点步骤；memo 化 Task 14——历史行 timeline 引用稳定，
 * 整轴跳过重渲染；流式行仅变化节点经步骤级 memo 局部更新；PERF-05 修复：
 * sources 以独立 props 传入替代调用方内联闭包，正文 delta 期间本轴 memo 不再被击穿）
 *
 * @param props 见 ChainTimelineProps
 */
export const ChainTimeline = memo(function ChainTimeline({
  timeline,
  active,
  sources,
  onOpenSources,
  onOpenTool,
}: ChainTimelineProps) {
  // 来源步骤点击回调（PERF-05）：依赖仅稳定引用（onOpenSources 调用方 useCallback、
  // sources 数组随事件到达才换引用），保证时间轴自身 memo 不被内部闭包击穿
  const openSources = useCallback(() => onOpenSources(sources), [onOpenSources, sources]);
  const lastIndex = timeline.length - 1;
  return (
    <div data-testid="chain-timeline" className="chain-timeline">
      {timeline.map((node, index) => {
        const running = isNodeRunning(node, index === lastIndex, active);
        switch (node.kind) {
          case "thinking":
            return (
              <ThinkingStep key={`think-${node.stage}-${index}`} node={node} running={running} />
            );
          case "sources":
            // 来源步骤：到达即检索完成（done 态）；点击打开召回抽屉
            return (
              <OpStep
                key={`sources-${index}`}
                testId="sources-step"
                running={running}
                icon={<MagnifyingGlass weight="bold" />}
                ring="dash"
                loadingText="正在检索相关资料"
                doneContent={
                  <>
                    已检索 <em>{node.sources.length}</em> 篇相关资料
                  </>
                }
                onClick={openSources}
              />
            );
          case "tool": {
            const label = toolNameLabel(node.toolName);
            return (
              <OpStep
                key={`tool-${node.toolCallId || "tool"}-${index}`}
                testId="tool-step"
                running={running}
                icon={<Wrench weight="fill" />}
                ring="arc"
                loadingText={`${label} 执行中`}
                doneContent={
                  node.status === "error" ? (
                    <>
                      {label} · <em>执行失败</em>
                    </>
                  ) : (
                    <>
                      {label} · <em>{summarizeOutput(node.output)}</em>
                    </>
                  )
                }
                onClick={() => onOpenTool(node)}
              />
            );
          }
        }
      })}
    </div>
  );
});
