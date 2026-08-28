"use client";

/**
 * 查询计划步骤（2026-08-28 时间线改版：QUERY_PLAN 事件/历史 query_plan 行的可视化）
 *
 * 需求解析结果的信息展示步骤（设计稿无直接对应元素，按链式语言自洽设计）：
 * - 静态节点（Compass 图标，idle 态——计划即事实，无进行中/完成视觉）
 * - 意图标签（knowledge_question=知识问答 / chat=闲聊 / unknown=未识别）金棕胶囊
 * - 改写查询清单：菱形 bullet 行（与思考行同语言）；课程名过滤条件以胶囊附注
 *
 * @param node 查询计划节点（intent/rewritten/courseNames）
 */
import { Compass } from "@phosphor-icons/react";
import { memo } from "react";
import { ChainNode } from "./chain-node";
import type { TimelineQueryPlanNode } from "@/lib/types";

/** 查询计划步骤 props */
export interface QueryPlanStepProps {
  /** 查询计划节点 */
  node: TimelineQueryPlanNode;
}

/** 意图 code → 中文标签（与后端 IntentType.code() 小写规范名对齐；未知值原样回退） */
const INTENT_LABELS: Record<string, string> = {
  knowledge_question: "知识问答",
  chat: "闲聊",
  unknown: "未识别意图",
};

/**
 * 意图标签映射（未收录的意图 code 原样回退，保证不空白）
 *
 * @param intent 意图 code 小写规范名
 * @returns 中文人话标签
 */
export function intentLabel(intent: string): string {
  return INTENT_LABELS[intent] ?? intent;
}

/**
 * 查询计划步骤（意图标签 + 改写查询列表；memo 化 Task 14——node 引用稳定即跳过）
 *
 * @param props 见 QueryPlanStepProps
 */
export const QueryPlanStep = memo(function QueryPlanStep({ node }: QueryPlanStepProps) {
  return (
    <div data-testid="query-plan-step" className="chain-step">
      <ChainNode state="idle" icon={<Compass weight="fill" />} />
      <div className="chain-body">
        <div className="chain-op-row">
          {/* 意图标签：金棕胶囊（信息展示，无交互） */}
          <span className="chain-plan-tag" data-testid="query-plan-intent">
            {intentLabel(node.intent)}
          </span>
          {/* 改写查询清单：首条以椭圆截断展示（多改写场景展开列于下方） */}
          {node.rewritten.length > 0 ? (
            <span className="chain-op-text" data-testid="query-plan-rewritten-first">
              {node.rewritten[0]}
            </span>
          ) : null}
        </div>
        {/* 其余改写查询 + 课程过滤条件（多行信息，逐行菱形 bullet） */}
        {node.rewritten.length > 1 || node.courseNames.length > 0 ? (
          <div className="chain-think-lines" data-testid="query-plan-detail">
            {node.rewritten.slice(1).map((query, index) => (
              <p key={index} className="chain-tl">
                {query}
              </p>
            ))}
            {node.courseNames.length > 0 ? (
              <p className="chain-tl">课程范围：{node.courseNames.join("、")}</p>
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  );
});
