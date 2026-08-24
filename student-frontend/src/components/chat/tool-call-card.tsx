"use client";

/**
 * 工具调用卡（设计 §1.5.4 ToolCallCard）
 *
 * - inline-flex surface-2 rounded-xl px-3 py-2；toolName 人话映射
 *   （searchKnowledge → 检索课程知识库等；未知名称原样回退）
 * - pending：spinner 工作指示 + 人话标签
 * - success：绿勾 + output 摘要 truncate（≤80 字符）+「查看详情」弹开完整 JSON（mono 13px）
 * - error 红叉态：后端 tool_result.status 恒 success（工具失败以 run 级 ERROR 事件呈现），
 *   分支按类型保留最小渲染（执行失败文案）
 */
import { CheckCircle, CircleNotch, XCircle } from "@phosphor-icons/react";
import { useState } from "react";
import type { StreamTool } from "@/hooks/use-chat-stream";

/** 工具卡组件 props */
export interface ToolCallCardProps {
  /** 工具卡视图模型（tool_call/tool_result 配对后由 hook 产出） */
  tool: StreamTool;
}

/** output 摘要截断长度（字符） */
const SUMMARY_MAX_LENGTH = 80;

/**
 * 工具名 → 人话标签映射（实际 @Tool 方法名：searchKnowledge/listCourses/queryCourseDetail/queryEnrollment）
 */
const TOOL_NAME_LABELS: Record<string, string> = {
  searchKnowledge: "检索课程知识库",
  listCourses: "查询课程列表",
  queryCourseDetail: "查询课程详情",
  queryEnrollment: "查询报名信息",
};

/**
 * 工具名人话映射（未收录的工具名原样回退，保证不空白）
 *
 * @param toolName 后端 SSE tool_call 事件的 toolName
 * @returns 中文人话标签
 */
export function toolNameLabel(toolName: string): string {
  return TOOL_NAME_LABELS[toolName] ?? toolName;
}

/**
 * output 摘要：JSON 序列化后截断（完整内容在「查看详情」内展示）
 *
 * @param output 工具执行结果原文（任意 JSON 结构）
 * @returns 截断摘要（非 JSON 可序列化值按 String() 兜底）
 */
function summarizeOutput(output: unknown): string {
  let text: string;
  try {
    text = JSON.stringify(output);
  } catch {
    text = String(output);
  }
  if (text === undefined || text === "undefined") {
    return "（无输出）";
  }
  return text.length > SUMMARY_MAX_LENGTH ? `${text.slice(0, SUMMARY_MAX_LENGTH)}…` : text;
}

/**
 * 工具调用卡（pending/success/error 三态，popover 式详情展开）
 *
 * @param tool 工具卡视图模型
 */
export function ToolCallCard({ tool }: ToolCallCardProps) {
  const [detailOpen, setDetailOpen] = useState(false);
  const label = toolNameLabel(tool.toolName);

  return (
    <div
      data-testid="tool-card"
      className="max-w-full rounded-xl border border-border bg-surface-2"
    >
      <div className="inline-flex max-w-full items-center gap-2 px-3 py-2 text-sm">
        {tool.status === "pending" ? (
          // pending：spinner 工作指示（设计 §1.6 思考中行同款旋转）
          <CircleNotch
            data-testid="tool-spinner"
            size={15}
            weight="bold"
            className="shrink-0 animate-spin text-brand motion-reduce:animate-none"
            aria-hidden
          />
        ) : tool.status === "error" ? (
          // error 红叉态：后端当前恒 success（工具失败以 run 级 ERROR 事件呈现），分支保留最小渲染
          <XCircle
            data-testid="tool-error"
            size={16}
            weight="fill"
            className="shrink-0 text-danger"
            aria-hidden
          />
        ) : (
          // success：绿勾
          <CheckCircle
            data-testid="tool-success"
            size={16}
            weight="fill"
            className="shrink-0 text-success"
            aria-hidden
          />
        )}
        <span className="font-medium text-text">{label}</span>
        {tool.status === "pending" ? (
          <span className="text-xs text-muted">执行中</span>
        ) : (
          <>
            {tool.status === "error" ? (
              <span className="text-xs text-danger">执行失败</span>
            ) : (
              <span data-testid="tool-summary" className="max-w-64 truncate text-xs text-muted">
                {summarizeOutput(tool.output)}
              </span>
            )}
            {/* 详情展开：完整 JSON（mono 13px），点击 toggle 展示/收起 */}
            <button
              type="button"
              aria-label={detailOpen ? "收起详情" : "查看详情"}
              onClick={() => setDetailOpen(!detailOpen)}
              className="shrink-0 rounded-lg px-1.5 py-0.5 text-xs text-brand transition-colors hover:bg-surface focus-visible:ring-2 focus-visible:ring-brand"
            >
              {detailOpen ? "收起详情" : "查看详情"}
            </button>
          </>
        )}
      </div>
      {detailOpen && tool.status !== "pending" ? (
        <pre
          data-testid="tool-detail-json"
          className="max-h-40 overflow-auto border-t border-border px-3 py-2 font-mono text-[13px] leading-5 whitespace-pre-wrap break-all"
        >
          {JSON.stringify(tool.output, null, 2)}
        </pre>
      ) : null}
    </div>
  );
}
