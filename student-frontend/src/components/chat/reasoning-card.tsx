"use client";

/**
 * AI 推理过程卡（2026-08-27 C 端改版：替代原 ThinkingCard 的消息内推理入口）
 *
 * 三态复合卡（对齐参考设计稿图2 的「思考过程卡」）：
 * - 阶段进度（STAGE 事件）：附件解析 → 理解问题 → 知识库查询 → 生成回答，
 *   头部实时显示当前阶段中文文案（如「知识库查询中」）；展开后为完成项打勾清单
 * - 思考流（THINKING 事件）：逐行渲染（每行 line-reveal 淡入，流式追加自然逐行浮现）
 * - 知识片段入口（SOURCES 就绪后）：头部「N 个知识片段」pill，点击打开右侧召回抽屉
 *
 * 交互契约（用户拍板）：默认收起；头部点击切换展开；收起态显示最新思考行预览
 * （逐行上滚观感）；生成中头部 spinner 旋转，结束后静态「已深度思考」。
 */
import { BookOpenText, CaretDown, Check, CircleNotch } from "@phosphor-icons/react";
import { useState } from "react";
import type { ChatStage, RetrievalSource } from "@/lib/types";

/** 推理卡组件 props */
export interface ReasoningCardProps {
  /** 阶段进度条目（STAGE 事件按序追加；历史消息恒空数组退化为纯思考卡） */
  stages: ChatStage[];
  /** 思考过程全文（thinking delta 累积） */
  thinking: string;
  /** thinking_end 是否已到达（到达后头部转「已深度思考」静态） */
  thinkingEnded: boolean;
  /** 本条消息是否仍在生成中（流式态判定；历史消息恒 false） */
  active: boolean;
  /** 召回来源（就绪后头部渲染「N 个知识片段」pill；空数组不渲染） */
  sources: RetrievalSource[];
  /** 知识片段 pill 点击回调（打开召回抽屉） */
  onOpenSources?: () => void;
}

/** 收起态预览行最大长度（超长截断；逐行上滚观感取末行） */
const PREVIEW_MAX_LENGTH = 46;

/**
 * 思考文本按行拆分（逐行渲染契约）：去除首尾空白后按换行切分，空行过滤
 *
 * @param thinking 思考全文（流式累积）
 * @returns 非空行数组
 */
export function splitThinkingLines(thinking: string): string[] {
  return thinking
    .split("\n")
    .map((line) => line.trimEnd())
    .filter((line) => line.length > 0);
}

/**
 * 推理过程卡（阶段进度 + 思考流 + 知识片段入口）
 *
 * 默认收起（用户拍板）；生成中头部 = spinner + 当前阶段 label，
 * 结束后 = 「已深度思考」；有来源时头部附「N 个知识片段」pill 开抽屉。
 */
export function ReasoningCard({
  stages,
  thinking,
  thinkingEnded,
  active,
  sources,
  onOpenSources,
}: ReasoningCardProps) {
  // 折叠开关：默认收起（用户拍板「默认卡片收起」）
  const [open, setOpen] = useState(false);
  const lines = splitThinkingLines(thinking);
  const running = active && !thinkingEnded;
  // 当前阶段 = 最后一条（后端按阶段边界推送，末条即进行中）；无阶段无思考的
  // 流式空窗（METADATA 刚到、STAGE 未到）显示「正在准备…」
  const currentStage = stages.at(-1);
  const headerLabel = running ? (currentStage?.label ?? "正在准备…") : "已深度思考";
  // 收起态逐行预览：取最后一行（流式追加时逐行上滚）；无思考内容时空串
  const previewLine = lines.at(-1) ?? "";

  return (
    <div
      data-testid="reasoning-card"
      className="rounded-xl border border-border bg-surface transition-colors hover:border-border-strong"
    >
      <div className="flex items-center gap-1 px-4 py-2.5">
        {/* 头部：spinner/静态点 + 阶段文案 + 展开箭头（点击切换展开） */}
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
          data-testid="reasoning-toggle"
          className="flex min-w-0 flex-1 items-center gap-2 rounded-lg py-0.5 text-left focus-visible:ring-2 focus-visible:ring-brand"
        >
          {running ? (
            <CircleNotch
              data-testid="reasoning-spinner"
              size={14}
              weight="bold"
              className="shrink-0 animate-spin text-brand motion-reduce:animate-none"
              aria-hidden
            />
          ) : (
            <span aria-hidden className="size-3.5 shrink-0 rounded-full bg-brand-soft" />
          )}
          <span
            data-testid="reasoning-label"
            className={`truncate text-sm ${running ? "font-medium text-text" : "text-muted"}`}
          >
            {headerLabel}
          </span>
          <CaretDown
            size={14}
            aria-hidden
            className={`shrink-0 text-subtle transition-transform ${open ? "" : "-rotate-90"}`}
          />
        </button>
        {/* 知识片段入口：SOURCES 就绪后渲染（检索完成即可查看召回内容） */}
        {sources.length > 0 && onOpenSources ? (
          <button
            type="button"
            onClick={onOpenSources}
            data-testid="reasoning-sources-pill"
            className="flex shrink-0 items-center gap-1.5 rounded-full border border-brand/30 bg-brand-light px-2.5 py-1 text-xs font-medium text-brand-strong transition-colors hover:border-brand/50 hover:bg-brand-soft focus-visible:ring-2 focus-visible:ring-brand"
          >
            <BookOpenText size={12} aria-hidden />
            {sources.length} 个知识片段
          </button>
        ) : null}
      </div>
      {/* 折叠容器：grid-rows 0fr/1fr 高度过渡（300ms，仅 grid 高度；reduced-motion 静态） */}
      <div
        className={`grid transition-[grid-template-rows] duration-300 ease-in-out motion-reduce:transition-none ${
          open ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
        }`}
      >
        <div className="overflow-hidden">
          <div data-testid="reasoning-content" className="space-y-3 px-4 pt-1 pb-3.5">
            {/* 阶段清单：已完成项打勾，进行中项 spinner（展开时可见的进度回顾） */}
            {stages.length > 0 ? (
              <ol className="space-y-1.5" aria-label="回答进度">
                {stages.map((stage, index) => {
                  const isCurrent = running && index === stages.length - 1;
                  return (
                    <li key={stage.stage} className="flex items-center gap-2 text-[13px]">
                      {isCurrent ? (
                        <CircleNotch
                          size={12}
                          weight="bold"
                          className="shrink-0 animate-spin text-brand motion-reduce:animate-none"
                          aria-hidden
                        />
                      ) : (
                        <Check
                          size={12}
                          weight="bold"
                          className="shrink-0 text-success"
                          aria-hidden
                        />
                      )}
                      <span className={isCurrent ? "text-text" : "text-subtle"}>{stage.label}</span>
                    </li>
                  );
                })}
              </ol>
            ) : null}
            {/* 思考流：逐行渲染（新增行动画淡入；max-h 内滚动） */}
            {lines.length > 0 ? (
              <div
                aria-hidden={!open}
                className="max-h-50 space-y-1 overflow-y-auto text-[13px] leading-6 text-muted"
              >
                {lines.map((line, index) => (
                  <p key={index} className="animate-line-reveal motion-reduce:animate-none">
                    {line}
                  </p>
                ))}
              </div>
            ) : null}
          </div>
        </div>
      </div>
      {/* 收起态逐行预览：最新一行截断（流式追加时逐行上滚观感）；展开时不渲染 */}
      {!open && previewLine ? (
        <p
          data-testid="reasoning-preview"
          className="truncate px-4 pb-2.5 text-[13px] leading-6 text-subtle"
        >
          {previewLine.length > PREVIEW_MAX_LENGTH
            ? `${previewLine.slice(0, PREVIEW_MAX_LENGTH)}…`
            : previewLine}
        </p>
      ) : null}
    </div>
  );
}
