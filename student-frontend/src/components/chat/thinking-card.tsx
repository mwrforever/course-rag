"use client";

/**
 * AI 思考过程卡（设计 §1.5.4 ThinkingCard + §1.6 动效）
 *
 * - surface-2 底 + 左侧 2px teal-300 竖线；标题「正在思考…」→「已思考」可折叠
 * - 思考中：指示灯旋转 + 全文展开（流式追加由外部传入 thinking 全文）
 * - thinking_end 后自动折叠为一行摘要（末句截断），高度过渡 240ms
 *   （grid-template-rows 0fr→1fr 技术，仅动画高度与透明度；reduced-motion 静态）
 * - 内容 13px/1.6 stone-500，展开后 max-h-200px 内滚动
 */
import { CaretDown, CircleNotch } from "@phosphor-icons/react";
import { useState } from "react";

/** 思考卡组件 props */
export interface ThinkingCardProps {
  /** 思考过程全文（流式 delta 累积后传入） */
  thinking: string;
  /** thinking_end 是否已到达（到达后自动折叠为摘要行） */
  ended: boolean;
}

/** 摘要单行截断长度（超出加省略号） */
const SUMMARY_MAX_LENGTH = 30;

/**
 * 摘要生成：取末句（句末标点切分），超长截断加省略号；空内容返回空串
 *
 * @param text 思考过程全文
 * @returns 一行摘要（无标点时整段截断）
 */
export function summarizeThinking(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) {
    return "";
  }
  // 末句（保留句末标点）：按中英文句末标点向后断言切分，取最后一段；无标点时整段
  const parts = trimmed
    .split(/(?<=[。！？!?])/)
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
  const last = parts[parts.length - 1] ?? trimmed;
  if (last.length <= SUMMARY_MAX_LENGTH) {
    return last;
  }
  return `${last.slice(0, SUMMARY_MAX_LENGTH)}…`;
}

/**
 * AI 思考过程卡（流式追加 + 结束折叠）
 *
 * 折叠状态机：ended 前恒展开（思考中不可收起）；ended 后默认折叠为摘要行，
 * 用户手动 toggle 可展开全文（点击后以用户意图为准）。
 */
export function ThinkingCard({ thinking, ended }: ThinkingCardProps) {
  // 手动折叠开关：null=跟随自动（思考中展开、结束后折叠）
  const [manualOpen, setManualOpen] = useState<boolean | null>(null);
  const open = manualOpen ?? !ended;
  const summary = summarizeThinking(thinking);

  return (
    <div
      className="flex gap-3 rounded-2xl border border-border bg-surface-2 px-4 py-3"
      data-testid="thinking-card"
    >
      {/* 左侧 2px teal-300 竖线（思考中指示 + 视觉分隔） */}
      <span aria-hidden className="w-0.5 shrink-0 rounded-full bg-teal-300" />
      <div className="min-w-0 flex-1">
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setManualOpen(!open)}
          className="flex w-full items-center gap-2 text-left focus-visible:ring-2 focus-visible:ring-brand"
        >
          {/* 思考中指示灯旋转（设计 §1.6 工作指示）；结束后静态占位保证标题行高一致 */}
          {!ended ? (
            <CircleNotch
              data-testid="thinking-spinner"
              size={14}
              weight="bold"
              className="shrink-0 animate-spin text-brand motion-reduce:animate-none"
              aria-hidden
            />
          ) : (
            <span aria-hidden className="size-3.5 shrink-0" />
          )}
          <span className="text-sm font-medium text-text">{ended ? "已思考" : "正在思考…"}</span>
          <CaretDown
            size={14}
            aria-hidden
            className={`ml-auto shrink-0 text-subtle transition-transform ${open ? "" : "-rotate-90"}`}
          />
        </button>
        {/* 折叠容器：grid-rows 0fr/1fr 实现高度过渡（240ms）+ 内容透明度过渡，仅动画 grid 高度与 opacity */}
        <div
          data-testid="thinking-content"
          className={`grid transition-[grid-template-rows] duration-240 ease-in-out motion-reduce:transition-none ${
            open ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
          }`}
        >
          <div className="overflow-hidden">
            {!open && summary ? (
              // 折叠态：一行摘要（末句截断）；阅读器仅播报摘要，全文被容器隐藏
              <p
                data-testid="thinking-summary"
                className="truncate pt-2 text-[13px] leading-6 text-stone-500"
              >
                {summary}
              </p>
            ) : (
              // 展开态：全文，max-h-200px 内滚动；折叠期间 aria-hidden 避免读屏播报隐藏内容
              <p
                aria-hidden={!open}
                className="max-h-50 overflow-y-auto pt-2 text-[13px] leading-6 text-stone-500 whitespace-pre-wrap"
              >
                {thinking}
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
