"use client";

/**
 * 链式时间轴容器（2026-08-28 时间线改版，设计稿 .chain 复刻）
 *
 * 一根渐变竖线串联全部步骤：StreamMessage.timeline 按到达序逐节点渲染——
 * - stage 节点 → 阶段步骤（OpStep：运行态 shimmer 阶段文案 + 跳动点 + 光带；
 *   图标按阶段键映射：附件 Paperclip / 理解 Lightbulb / 检索 MagnifyingGlass / 生成 PenNib）
 * - queryPlan 节点 → QueryPlanStep（意图标签 + 改写查询清单，静态信息）
 * - thinking 节点 → ThinkingStep（Brain → 绿勾，mask 收起展开）
 * - sources 节点 → 检索步骤（MagnifyingGlass + 虚线雷达环；完成文案「已检索 N 篇相关资料」，
 *   点击打开召回抽屉——来源事件即检索完成事实，到达即为 done 态）
 * - tool 节点 → 工具步骤（Wrench + 弧线 conic 环；pending 跳动点、success 摘要 +
 *   点击展开完整 JSON 详情，工具卡能力并入时间轴）
 *
 * 运行态推导：末节点且消息流式中视为进行中（thinking 以 ended、tool 以 status
 * 各自内聚判定完成）；历史消息（active=false）全部呈现完成态。
 */
import { Lightbulb, MagnifyingGlass, Paperclip, PenNib, Wrench } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { OpStep, summarizeOutput, toolNameLabel } from "./op-step";
import { QueryPlanStep } from "./query-plan-step";
import { ThinkingStep } from "./thinking-step";
import type { ChatStageKey, TimelineNode } from "@/lib/types";

/** 链式时间轴 props */
export interface ChainTimelineProps {
  /** 时间轴节点序列（SSE 到达序 / 历史行 seq 序重建） */
  timeline: TimelineNode[];
  /** 所属消息是否流式进行中（末节点 running 判定；历史消息恒 false） */
  active: boolean;
  /** 打开召回抽屉回调（sources 步骤完成态点击） */
  onOpenSources: () => void;
}

/** 阶段键 → 图标映射（阶段步骤静态图标；检索阶段与来源步骤共用 MagnifyingGlass 语言） */
const STAGE_ICONS: Record<ChatStageKey, ReactNode> = {
  attachments: <Paperclip weight="fill" />,
  understanding: <Lightbulb weight="fill" />,
  retrieving: <MagnifyingGlass weight="bold" />,
  generating: <PenNib weight="fill" />,
};

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
  if (node.kind === "queryPlan") return false;
  return true;
}

/**
 * 链式时间轴容器（竖线 + 逐节点步骤）
 *
 * @param props 见 ChainTimelineProps
 */
export function ChainTimeline({ timeline, active, onOpenSources }: ChainTimelineProps) {
  const lastIndex = timeline.length - 1;
  return (
    <div data-testid="chain-timeline" className="chain-timeline">
      {timeline.map((node, index) => {
        const running = isNodeRunning(node, index === lastIndex, active);
        switch (node.kind) {
          case "stage":
            // 阶段步骤：运行态 = 后端中文文案 shimmer；完成态文案原样（阶段推进回顾）
            return (
              <OpStep
                key={`stage-${node.stage}-${index}`}
                running={running}
                icon={STAGE_ICONS[node.stage]}
                ring="none"
                loadingText={node.label}
                doneContent={node.label}
              />
            );
          case "queryPlan":
            return <QueryPlanStep key={`plan-${index}`} node={node} />;
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
                onClick={onOpenSources}
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
              >
                {/* 工具详情：完整 JSON（mono 13px；完成态点击切换展开） */}
                <pre className="max-h-40 overflow-auto rounded-lg border-t border-border px-3 py-2 font-mono text-[13px] leading-5 whitespace-pre-wrap break-all">
                  {JSON.stringify(node.output, null, 2)}
                </pre>
              </OpStep>
            );
          }
        }
      })}
    </div>
  );
}
