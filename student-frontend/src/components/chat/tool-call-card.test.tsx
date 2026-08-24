/**
 * 工具卡测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 ToolCallCard）：
 * - toolName 人话映射（searchKnowledge → 检索课程知识库，未知名称原样回退）
 * - pending：spinner 工作指示 + 人话标签
 * - success：绿勾 + output 摘要 truncate + 「查看详情」弹开完整 JSON（mono）
 * - error 分支：红叉 + 「执行失败」（后端当前恒 success，类型保留分支）
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ToolCallCard, toolNameLabel } from "./tool-call-card";
import type { StreamTool } from "@/hooks/use-chat-stream";

function makeTool(overrides: Partial<StreamTool> = {}): StreamTool {
  return {
    toolCallId: "tc-1",
    toolName: "searchKnowledge",
    input: { query: "什么是 RAG" },
    status: "pending",
    output: null,
    ...overrides,
  };
}

describe("toolNameLabel 人话映射", () => {
  it("searchKnowledge → 检索课程知识库", () => {
    expect(toolNameLabel("searchKnowledge")).toBe("检索课程知识库");
  });
  it("课程工具映射：listCourses/queryCourseDetail/queryEnrollment", () => {
    expect(toolNameLabel("listCourses")).toBe("查询课程列表");
    expect(toolNameLabel("queryCourseDetail")).toBe("查询课程详情");
    expect(toolNameLabel("queryEnrollment")).toBe("查询报名信息");
  });
  it("未知工具名：原样回退", () => {
    expect(toolNameLabel("futureTool")).toBe("futureTool");
  });
});

describe("ToolCallCard 三态", () => {
  it("pending：spinner + 人话标签（检索课程知识库）", () => {
    render(<ToolCallCard tool={makeTool()} />);
    expect(screen.getByTestId("tool-spinner")).toBeInTheDocument();
    expect(screen.getByText("检索课程知识库")).toBeInTheDocument();
  });

  it("success：绿勾 + output 摘要 truncate + 详情按钮", () => {
    render(
      <ToolCallCard
        tool={makeTool({
          status: "success",
          output: {
            answer:
              "这是一个非常长的检索结果内容用于验证摘要截断逻辑是否生效，重复填充至明显超过八十个字符的截断阈值：".repeat(
                2,
              ),
          },
        })}
      />,
    );
    expect(screen.getByTestId("tool-success")).toBeInTheDocument();
    expect(screen.queryByTestId("tool-spinner")).not.toBeInTheDocument();
    // 摘要为截断形式（≤80 字符 + 省略号），完整 JSON 默认隐藏
    expect(screen.getByTestId("tool-summary")).toHaveTextContent("…");
    expect(screen.getByText(/这是一个非常长的检索结果/)).toBeInTheDocument();
  });

  it("点「查看详情」：弹开完整 JSON（mono），再点收起", () => {
    const output = { hits: [{ chunkId: "c1", score: 0.9 }], total: 1 };
    render(<ToolCallCard tool={makeTool({ status: "success", output })} />);
    fireEvent.click(screen.getByRole("button", { name: "查看详情" }));
    const pre = screen.getByTestId("tool-detail-json");
    expect(pre).toHaveTextContent('"chunkId": "c1"');
    expect(pre.className).toContain("font-mono");
    fireEvent.click(screen.getByRole("button", { name: "收起详情" }));
    expect(screen.queryByTestId("tool-detail-json")).not.toBeInTheDocument();
  });

  it("error：红叉 + 执行失败文案（分支保留，后端当前不可达）", () => {
    render(<ToolCallCard tool={makeTool({ status: "error", output: { error: "工具超时" } })} />);
    expect(screen.getByTestId("tool-error")).toBeInTheDocument();
    expect(screen.getByText("执行失败")).toBeInTheDocument();
  });
});
