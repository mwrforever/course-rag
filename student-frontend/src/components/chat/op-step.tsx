"use client";

/**
 * 检索/工具步骤（2026-08-28 时间线改版，设计稿 .step.op 复刻）
 *
 * 单行内容（与节点行 27px 等高对齐）+ 不定进度光带：
 * - 运行态：状态文字 shimmer 流光 + 三跳动点（错峰 .15s/.3s）+ 顶部 2px 光带横扫
 *   （indet 1.5s，34% 宽内条 translateX -110% → 420%）
 * - 完成态：跳动点移除、光带淡出隐藏、完成文案（可含 <em> 强调）+ 箭头右滑入
 * - 交互：未完成点击 shake 抖动提示（完成后才可点击）；完成点击触发回调
 *   （检索步骤开召回抽屉）或展开内嵌详情（工具步骤的完整 JSON）
 * - 工具卡能力并入（原 ToolCallCard 下线）：toolNameLabel 人话映射 + output 摘要截断
 *
 * reduced-motion 由 globals.css 全局总开关降级（抖动/光带/跳动点全部瞬时化）。
 */
import { CaretRight } from "@phosphor-icons/react";
import { memo, useState, type ReactNode } from "react";
import { ChainNode } from "./chain-node";

/** 检索/工具步骤 props */
export interface OpStepProps {
  /** 是否进行中（运行态视觉与点击拦截的判定依据） */
  running: boolean;
  /** 节点图标（13px Phosphor 图标实例） */
  icon: ReactNode;
  /** 运行中动画环类型：dash=虚线雷达环（检索）/ arc=弧线 conic 环（工具）/ none=无环（阶段步骤） */
  ring: "dash" | "arc" | "none";
  /** 运行态文案（shimmer 流光展示，如「正在检索相关资料」） */
  loadingText: string;
  /** 完成态内容（可含 <em> 强调数字，如「已检索 3 篇相关资料」） */
  doneContent: ReactNode;
  /** 完成态点击回调（如打开召回抽屉）；与 children 二选一 */
  onClick?: () => void;
  /** 完成态点击展开的内嵌详情（工具步骤的完整 JSON）；与 onClick 二选一 */
  children?: ReactNode;
  /** 步骤 testid（缺省 op-step；sources/tool 步骤传专用 id 供测试与 E2E 定位） */
  testId?: string;
}

/** 抖动提示动画时长（step-shake 400ms 后自动移除类，便于再次触发） */
const SHAKE_MS = 400;

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
 * output 摘要：JSON 序列化后截断（完整内容在详情展开内展示）
 *
 * @param output 工具执行结果原文（任意 JSON 结构）
 * @returns 截断摘要（非 JSON 可序列化值按 String() 兜底）
 */
export function summarizeOutput(output: unknown): string {
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
 * 检索/工具步骤（单行内容 + 光带 + 完成箭头；未完成点击 shake、完成点击动作）
 *
 * memo 化（PERF-05 顺带补齐）：时间轴容器重渲染时 props 未变的步骤跳过重新执行，
 * 与 ThinkingStep 的步骤级 memo 口径对齐。
 *
 * @param props 见 OpStepProps
 */
export const OpStep = memo(function OpStep({
  running,
  icon,
  ring,
  loadingText,
  doneContent,
  onClick,
  children,
  testId = "op-step",
}: OpStepProps) {
  // 未完成点击的 shake 抖动提示（400ms 后自动摘除，可重复触发）
  const [shaking, setShaking] = useState(false);
  // 内嵌详情展开（children 提供时：完成态点击切换）
  const [detailOpen, setDetailOpen] = useState(false);
  const done = !running;
  const clickable = done && (onClick !== undefined || children !== undefined);

  /** 步骤点击分流：未完成 shake 提示；完成态触发回调或切换详情 */
  function handleActivate(): void {
    if (!done) {
      // 未完成点击：shake 抖动提示（完成后才可交互，设计稿语义）
      setShaking(true);
      window.setTimeout(() => setShaking(false), SHAKE_MS);
      return;
    }
    if (onClick) {
      onClick();
      return;
    }
    if (children) {
      setDetailOpen(!detailOpen);
    }
  }

  return (
    <div
      data-testid={testId}
      role={clickable ? "button" : undefined}
      tabIndex={clickable ? 0 : undefined}
      aria-disabled={clickable ? undefined : done ? undefined : true}
      onClick={handleActivate}
      onKeyDown={(event) => {
        // 键盘可达性：Enter/Space 触发与点击同路径（role=button 的默认行为补齐）
        if (clickable && (event.key === "Enter" || event.key === " ")) {
          event.preventDefault();
          handleActivate();
        }
      }}
      className={`chain-step ${running ? "chain-step--running" : "chain-step--done"} ${
        clickable ? "chain-step--clickable" : ""
      } ${shaking ? "chain-step--shake" : ""}`}
    >
      {/* 不定进度光带：完成态淡出隐藏（CSS 步骤类驱动） */}
      <div className="chain-lightbar" aria-hidden>
        <i />
      </div>
      <ChainNode state={done ? "done" : "running"} ring={ring} icon={icon} />
      <div className="chain-body">
        <div className="chain-op-row">
          {running ? (
            <>
              {/* 运行态：shimmer 流光文案 + 三跳动点（错峰入场） */}
              <span className="chain-op-text">
                <span className="shimmer-text">{loadingText}</span>
              </span>
              <span className="chain-dots" aria-hidden>
                <i />
                <i />
                <i />
              </span>
            </>
          ) : (
            <>
              {/* 完成态：完成文案（可含 em 强调）+ 摘要 + 箭头右滑入 */}
              <span className="chain-op-text" data-testid="op-step-text">
                {doneContent}
              </span>
              <span className="chain-op-go" aria-hidden>
                <CaretRight weight="bold" />
              </span>
            </>
          )}
        </div>
        {/* 内嵌详情（工具步骤完整 JSON；完成态点击切换展开） */}
        {children && detailOpen && done ? <div data-testid="op-step-detail">{children}</div> : null}
      </div>
    </div>
  );
});
