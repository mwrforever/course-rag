"use client";

/**
 * 知识库召回抽屉（2026-08-27 C 端改版，结构对齐参考设计稿图2 右侧来源面板）
 *
 * 展示本轮回答引用的系统知识库召回片段（仅 SearchKnowledgeTool 命中的
 * RetrievalSource——附件局部语料与经历记忆不经此通道，天然满足「只展示系统
 * 知识库召回的片段」）。
 *
 * - 深色头部条：标题「知识库召回」+ 片段计数 + 关闭按钮
 * - 片段列表项：文档图标 + docTitle + headingPath 面包屑 + 相关度百分比 +
 *   片段正文（content 截断预览，存量数据无 content 时降级为占位文案）
 * - 交互：Esc / 遮罩点击 / 关闭按钮三种关闭路径；右侧滑入（transform/opacity，
 *   reduced-motion 静态）
 */
import { FileText, X } from "@phosphor-icons/react";
import { useEffect } from "react";
import type { RetrievalSource } from "@/lib/types";

/** 召回抽屉 props */
export interface RetrievalDrawerProps {
  /** 召回来源列表（null = 抽屉关闭，不渲染） */
  sources: RetrievalSource[] | null;
  /** 关闭回调（关闭按钮 / Esc / 点击遮罩触发） */
  onClose: () => void;
}

/** score 置信百分比（0-100 取整；越界值钳制 [0,1]） */
function clampPercent(score: number): number {
  const clamped = Math.max(0, Math.min(1, score));
  return Math.round(clamped * 100);
}

/**
 * 知识库召回抽屉（sources 非 null 时挂载）
 *
 * @param sources 召回片段列表（空数组也渲染——计数为 0 的空态）
 * @param onClose 关闭回调
 */
export function RetrievalDrawer({ sources, onClose }: RetrievalDrawerProps) {
  // Esc 关闭监听（抽屉开启期间挂载；hook 无条件调用，内部按 sources 判空）
  useEffect(() => {
    if (sources === null) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [sources, onClose]);

  if (sources === null) {
    return null;
  }
  return (
    <div className="fixed inset-0 z-50">
      {/* 遮罩：点击空白关闭（仅 opacity 动画） */}
      <div
        data-testid="retrieval-drawer-overlay"
        aria-hidden
        onClick={onClose}
        className="absolute inset-0 animate-overlay-in bg-overlay motion-reduce:animate-none"
      />
      {/* 抽屉面板：右侧 440px 滑入（transform，reduced-motion 静态） */}
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="知识库召回片段"
        data-testid="retrieval-drawer"
        className="absolute top-0 right-0 flex h-full w-full max-w-[440px] animate-drawer-in flex-col bg-surface shadow-xl motion-reduce:animate-none"
      >
        {/* 深色头部条（对齐图2：深底 + 标题 + 计数徽标 + 关闭） */}
        <header className="flex shrink-0 items-center gap-3 bg-ink px-5 py-4 text-cream">
          <span className="grid size-9 shrink-0 place-items-center rounded-full border border-cream/40">
            <FileText size={16} aria-hidden />
          </span>
          <div className="min-w-0 flex-1">
            <h3 className="font-display text-base font-semibold tracking-wide">知识库召回</h3>
            <p className="text-xs text-cream/65">系统向量检索命中的 {sources.length} 个片段</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭召回抽屉"
            className="grid size-8 shrink-0 place-items-center rounded-full transition-colors hover:bg-cream/10 focus-visible:ring-2 focus-visible:ring-cream/60"
          >
            <X size={15} aria-hidden />
          </button>
        </header>
        {/* 片段列表（按精排顺序 = 相关度降序） */}
        <div className="flex-1 overflow-y-auto p-4">
          {sources.length === 0 ? (
            <p className="px-2 py-8 text-center text-sm text-subtle">本轮回答未引用知识库片段</p>
          ) : (
            <ol className="space-y-3" data-testid="retrieval-drawer-list">
              {sources.map((source, index) => {
                const percent = clampPercent(source.score);
                return (
                  <li
                    key={source.chunkId}
                    data-testid="retrieval-source-item"
                    className="rounded-xl border border-border bg-surface-2 p-3.5"
                  >
                    <div className="flex items-start gap-2.5">
                      {/* 序号徽标：召回排位 */}
                      <span className="grid size-5 shrink-0 place-items-center rounded-md bg-brand-soft text-[11px] font-semibold text-brand-strong tabular-nums">
                        {index + 1}
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium text-text">{source.docTitle}</p>
                        <p className="mt-0.5 truncate text-xs text-muted">{source.headingPath}</p>
                      </div>
                      {/* 相关度：百分比 + 细条（钳制后的精排分数） */}
                      <span className="shrink-0 text-xs font-medium text-brand-strong tabular-nums">
                        {percent}%
                      </span>
                    </div>
                    {/* 片段正文预览（存量数据无 content 降级占位） */}
                    <p className="mt-2.5 border-t border-border/60 pt-2.5 text-[13px] leading-6 whitespace-pre-wrap text-muted">
                      {source.content?.trim() || "（片段内容暂不可用）"}
                    </p>
                  </li>
                );
              })}
            </ol>
          )}
        </div>
      </aside>
    </div>
  );
}
