"use client";

/**
 * 思考步骤（2026-08-28 时间线改版，设计稿 .step.think 复刻）
 *
 * 头部行原位（27px 与节点行等高，展开不居中漂移）+ 思考内容体 t-body：
 * - 运行态：状态文字「思考中」shimmer 流光；末行菱形 bullet 金呼吸（chain-tl--now）
 * - 完成态（thinking_end 已到 / 消息非流式）：状态文字「思考已完成」、节点 Brain → 绿勾
 * - 收起（默认）：max-height 26px 露最新一行 + 底缘 mask 渐隐；展开：300px 可滚动去 mask，
 *   max-height .55s 过渡（reduced-motion 全局开关降级为瞬时）
 * - 交互契约（用户拍板沿用）：默认收起，头部点击切换展开；收起时滚动体锚定底部
 *   （最新一行可见，逐行上滚观感）
 * - a11y：头部按钮 aria-expanded + 状态文字 aria-live=polite（思考中 → 已完成播报）
 */
import { Brain, CaretDown, Check } from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";
import { ChainNode } from "./chain-node";
import type { TimelineThinkingNode } from "@/lib/types";

/** 思考步骤 props */
export interface ThinkingStepProps {
  /** 思考节点（同 stage 多 delta 合并后的行列表与 ended 标记） */
  node: TimelineThinkingNode;
  /** 是否进行中（thinking 未 ended 且消息流式中；历史消息恒 false） */
  running: boolean;
}

/**
 * 思考行可见过滤：裁剪行尾空白、过滤空白行（流式行列表可能残留空行——
 * delta 按换行拆分时空行是「下一行待写入」占位，终态渲染无意义）
 *
 * @param lines 思考节点行列表（reducer 合并产物）
 * @returns 可见行列表
 */
export function visibleThinkingLines(lines: string[]): string[] {
  return lines.map((line) => line.trimEnd()).filter((line) => line.length > 0);
}

/**
 * 思考步骤组件（头部行原位 + mask 收起展开 + 逐行 reveal）
 *
 * @param props 见 ThinkingStepProps
 */
export function ThinkingStep({ node, running }: ThinkingStepProps) {
  // 折叠开关：默认收起（用户拍板「默认卡片收起」沿用）
  const [open, setOpen] = useState(false);
  // 内容体引用：收起态锚定底部（露最新一行，设计稿 sticky-scroll 语义）
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const lines = visibleThinkingLines(node.lines);
  const done = !running;

  useEffect(() => {
    // 收起时滚动到底部：最新一行落入 26px 可视窗（流式追加自然逐行上滚）
    if (!open && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
  }, [open, lines.length]);

  return (
    <div
      data-testid="thinking-step"
      className={`chain-step ${open ? "chain-step--open" : ""} ${
        running ? "chain-step--running" : "chain-step--done chain-step--green"
      }`}
    >
      <ChainNode
        state={done ? "done" : "running"}
        icon={<Brain weight="fill" />}
        doneIcon={<Check weight="bold" />}
      />
      <div className="chain-body">
        {/* 头部行：状态文字 + chevron（点击切换展开；aria-expanded 供读屏感知） */}
        <button
          type="button"
          className="chain-head"
          aria-expanded={open}
          aria-controls="chain-think-body"
          data-testid="thinking-toggle"
          onClick={() => setOpen(!open)}
        >
          <span className="chain-status" aria-live="polite" data-testid="thinking-status">
            {running ? <span className="shimmer-text">思考中</span> : "思考已完成"}
          </span>
          <CaretDown aria-hidden className="chain-chevron" />
        </button>
        {/* 思考内容体：收起 26px + mask 渐隐露最新一行；展开 300px 滚动
            （展开态由步骤级 chain-step--open 类驱动 max-height/mask 过渡） */}
        <div
          id="chain-think-body"
          ref={bodyRef}
          data-testid="thinking-body"
          className="chain-think-body"
        >
          <div className="chain-think-lines">
            {lines.map((line, index) => (
              <p
                key={index}
                className={`chain-tl ${running && index === lines.length - 1 ? "chain-tl--now" : ""}`}
              >
                {line}
              </p>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
