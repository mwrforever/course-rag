"use client";

/**
 * 知识库召回抽屉（2026-08-27 C 端改版；2026-08-28 Task 11 对齐设计稿 chunk 卡）
 *
 * 展示本轮回答引用的系统知识库召回片段（仅 SearchKnowledgeTool 命中的
 * RetrievalSource——附件局部语料与经历记忆不经此通道，天然满足「只展示系统
 * 知识库召回的片段」）。
 *
 * - 深色头部条：标题「知识库召回」+ 片段计数 + 关闭按钮
 * - chunk 卡（设计稿 .chunk 复刻）：类型徽标（知识库）+ 相似度百分比 + 标题 +
 *   正文（默认 3 行截断，点击卡片切换展开全文）+ 相似度 meter（4px 金渐变填充，
 *   进场后 width 1s 过渡到目标值）+ 卡片错峰进场（animationDelay 85ms/张）
 * - 交互：Esc / 遮罩点击 / 关闭按钮三种关闭路径（保留）；点击卡片展开/收起正文
 * - 存量数据无 content 时降级占位文案
 */
import { FileText, X } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
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

/** meter 进场延迟基数（设计稿实证：首张 120ms 后开始填充） */
const METER_FILL_DELAY_MS = 120;

/**
 * 知识库召回抽屉（sources 非 null 时挂载）
 *
 * @param sources 召回片段列表（空数组也渲染——计数为 0 的空态）
 * @param onClose 关闭回调
 */
export function RetrievalDrawer({ sources, onClose }: RetrievalDrawerProps) {
  // meter 填充开关：进场后延迟置位，width 经 1s 过渡从 0 长到目标值
  const [meterFilled, setMeterFilled] = useState(false);
  // 展开态卡片集合（按列表下标；点击卡片切换 line-clamp）
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(new Set());

  // meter 进场动画：挂载后 120ms 统一置位（各卡填充经 transitionDelay 错峰）
  useEffect(() => {
    if (sources === null) return;
    const timer = window.setTimeout(() => setMeterFilled(true), METER_FILL_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [sources]);

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

  /** 卡片点击/键盘激活：切换该卡正文展开态（line-clamp 3 行 ↔ 全文） */
  function toggleExpanded(index: number): void {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
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
        {/* 片段列表（按精排顺序 = 相关度降序；卡片错峰进场） */}
        <div className="flex-1 overflow-y-auto p-4">
          {sources.length === 0 ? (
            <p className="px-2 py-8 text-center text-sm text-subtle">本轮回答未引用知识库片段</p>
          ) : (
            <ol className="space-y-3" data-testid="retrieval-drawer-list">
              {sources.map((source, index) => {
                const percent = clampPercent(source.score);
                const isOpen = expanded.has(index);
                return (
                  <li
                    key={source.chunkId}
                    data-testid="retrieval-source-item"
                    role="button"
                    tabIndex={0}
                    aria-expanded={isOpen}
                    onClick={() => toggleExpanded(index)}
                    onKeyDown={(event) => {
                      // 键盘可达性：Enter/Space 与点击同路径切换展开
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        toggleExpanded(index);
                      }
                    }}
                    // 错峰进场：每张卡延迟 85ms（设计稿 chunk 动画实证值）
                    style={{ animationDelay: `${index * 85}ms` }}
                    className={`chunk-card ${isOpen ? "chunk-card--exp" : ""}`}
                  >
                    {/* 顶行：类型徽标（系统知识库来源）+ 面包屑 + 相似度百分比 */}
                    <div className="flex items-center gap-2">
                      <span className="chunk-type shrink-0">知识库</span>
                      <span className="min-w-0 flex-1 truncate text-xs text-muted">
                        {source.headingPath || source.docTitle}
                      </span>
                      <span className="shrink-0 text-[11px] text-faint tabular-nums">
                        相似度 {percent}%
                      </span>
                    </div>
                    {/* 片段标题 */}
                    <p className="mt-2 text-[13.5px] leading-relaxed font-bold text-text">
                      {source.docTitle}
                    </p>
                    {/* 片段正文：默认 3 行截断；展开态解除（存量数据无 content 降级占位） */}
                    <p
                      data-testid="retrieval-source-text"
                      className="chunk-text whitespace-pre-wrap"
                    >
                      {source.content?.trim() || "（片段内容暂不可用）"}
                    </p>
                    {/* 底行：相似度 meter（进场后 width 1s 过渡填充）+ 展开提示 */}
                    <div className="mt-2.5 flex items-center gap-3">
                      <div
                        className="chunk-meter"
                        role="meter"
                        aria-label={`相似度 ${percent}%`}
                        aria-valuemin={0}
                        aria-valuemax={100}
                        aria-valuenow={percent}
                      >
                        {/* 填充延迟随下标错峰（meterFilled 置位后 width 过渡到目标值） */}
                        <i
                          data-testid="retrieval-source-meter"
                          style={{
                            width: meterFilled ? `${percent}%` : "0%",
                            transitionDelay: `${index * 85}ms`,
                          }}
                        />
                      </div>
                      <span className="shrink-0 text-[10.5px] tracking-wider text-faint">
                        {isOpen ? "收起" : "展开全文"}
                      </span>
                    </div>
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
