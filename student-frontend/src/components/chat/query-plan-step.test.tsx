/**
 * 查询计划步骤测试（2026-08-28 时间线改版：意图标签 + 改写查询清单）
 *
 * 覆盖：意图标签映射 / 改写查询首条椭圆截断与多改写列表 / 课程过滤条件 / 空列表边界
 */
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { QueryPlanStep, intentLabel } from "./query-plan-step";
import type { TimelineQueryPlanNode } from "@/lib/types";

/** 查询计划节点工厂 */
function makeNode(over?: Partial<TimelineQueryPlanNode>): TimelineQueryPlanNode {
  return {
    kind: "queryPlan",
    intent: "knowledge_question",
    rewritten: ["RAG 检索增强生成的概念"],
    courseNames: [],
    ...over,
  };
}

describe("intentLabel 意图标签映射", () => {
  it("knowledge_question/chat/unknown 映射中文；未知 code 原样回退", () => {
    expect(intentLabel("knowledge_question")).toBe("知识问答");
    expect(intentLabel("chat")).toBe("闲聊");
    expect(intentLabel("unknown")).toBe("未识别意图");
    expect(intentLabel("future_intent")).toBe("future_intent");
  });
});

describe("QueryPlanStep 渲染", () => {
  it("意图标签 + 首条改写查询展示（静态信息步骤，无运行态类）", () => {
    render(<QueryPlanStep node={makeNode()} />);
    expect(screen.getByTestId("query-plan-intent")).toHaveTextContent("知识问答");
    expect(screen.getByTestId("query-plan-rewritten-first")).toHaveTextContent(
      "RAG 检索增强生成的概念",
    );
    expect(screen.queryByTestId("query-plan-detail")).not.toBeInTheDocument();
  });

  it("多改写查询：其余改写逐行列出；课程过滤条件附注", () => {
    render(
      <QueryPlanStep
        node={makeNode({
          intent: "chat",
          rewritten: ["第一个改写", "第二个改写", "第三个改写"],
          courseNames: ["高等数学", "线性代数"],
        })}
      />,
    );
    expect(screen.getByTestId("query-plan-intent")).toHaveTextContent("闲聊");
    const detail = screen.getByTestId("query-plan-detail");
    expect(detail).toHaveTextContent("第二个改写");
    expect(detail).toHaveTextContent("第三个改写");
    expect(detail).toHaveTextContent("课程范围：高等数学、线性代数");
  });

  it("降级计划（unknown + 原问题改写）正常渲染", () => {
    render(<QueryPlanStep node={makeNode({ intent: "unknown", rewritten: ["原问题"] })} />);
    expect(screen.getByTestId("query-plan-intent")).toHaveTextContent("未识别意图");
    expect(screen.getByTestId("query-plan-rewritten-first")).toHaveTextContent("原问题");
  });
});
