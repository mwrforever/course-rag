"use client";

/**
 * 参考来源卡组（设计 §1.5.4 SourcesList）
 *
 * - 标题行「参考来源」+ 来源卡（文档图标 + docTitle + headingPath 双行）
 * - score 置信条 0-1 青色（teal）细条填充，等宽数字百分比；越界值钳制到 [0,1]
 * - 点击来源卡复制引用文本（无跳转目标；来源片段无正文可复制，复制标题+面包屑）
 * - 来源卡组 stagger 滑入（animationDelay 逐卡 60ms，280ms ease-out；
 *   仅动画 transform/opacity，reduced-motion 静态）
 */
import { FileText } from "@phosphor-icons/react";
import { useReducedMotion } from "motion/react";
import type { RetrievalSource } from "@/lib/types";

/** 来源卡组组件 props */
export interface SourcesListProps {
  /** 来源列表（仅 knowledge_question 意图发送；UI 不得假设必有） */
  sources: RetrievalSource[];
  /** 复制成功提示回调（页面 toast） */
  onNotify(message: string): void;
}

/** 逐卡 stagger 间隔（毫秒，与 280ms 入场动画配合） */
const STAGGER_MS = 60;

/** score 置信条刻度取整（0-100 整数百分比） */
function clampPercent(score: number): number {
  // score 为 double，越界（异常数据源）钳制到 [0,1] 保证置信条宽度合法
  const clamped = Math.max(0, Math.min(1, score));
  return Math.round(clamped * 100);
}

/**
 * 来源复制引用文本（无跳转目标时给用户留档的最小引用串）
 *
 * @param source 来源条目
 * @returns 「标题（面包屑）」引用串
 */
export function sourceCopyText(source: RetrievalSource): string {
  return `${source.docTitle}（${source.headingPath}）`;
}

/**
 * 参考来源卡组（正文之前渲染，stagger 滑入）
 *
 * @param sources 来源列表
 * @param onNotify 复制提示回调
 */
export function SourcesList({ sources, onNotify }: SourcesListProps) {
  const reduceMotion = useReducedMotion() ?? true;

  /** 点击来源卡：复制引用文本到剪贴板（无跳转目标）+ 提示 */
  async function copySource(source: RetrievalSource) {
    await navigator.clipboard.writeText(sourceCopyText(source));
    onNotify("已复制来源");
  }

  return (
    <section data-testid="sources-list" className="space-y-2">
      <h3 className="text-sm font-medium text-text">参考来源</h3>
      <ul className="space-y-2">
        {sources.map((source, index) => {
          const percent = clampPercent(source.score);
          return (
            <li
              key={source.chunkId}
              data-testid="source-card"
              // stagger 滑入：逐卡延迟递增；reduced-motion 静态（仅 transform/opacity 动画）
              style={reduceMotion ? undefined : { animationDelay: `${index * STAGGER_MS}ms` }}
              className="animate-source-in motion-reduce:animate-none"
            >
              <button
                type="button"
                aria-label={`复制来源：${source.docTitle}`}
                onClick={() => void copySource(source)}
                className="flex w-full items-center gap-3 rounded-xl border border-border bg-surface px-3 py-2.5 text-left transition-colors hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-brand"
              >
                <FileText size={18} className="shrink-0 text-brand" aria-hidden />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium text-text">
                    {source.docTitle}
                  </span>
                  <span className="block truncate text-xs text-muted">{source.headingPath}</span>
                </span>
                {/* score 置信条：青色细条 + 等宽百分比 */}
                <span className="flex w-24 shrink-0 flex-col items-end gap-1">
                  <span
                    data-testid="score-bar"
                    className="h-1 w-full overflow-hidden rounded-full bg-surface-2"
                  >
                    <span
                      data-testid="score-fill"
                      className="block h-full rounded-full bg-brand"
                      style={{ width: `${percent}%` }}
                    />
                  </span>
                  <span className="text-xs text-subtle tabular-nums">{percent}%</span>
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
