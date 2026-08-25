"use client";

import { TreeStructure } from "@phosphor-icons/react";
import type { MaterialChunk } from "@/lib/types";

/** 资料分片卡 props */
export interface ChunkItemProps {
  /** 资料分片（J2） */
  chunk: MaterialChunk;
  /** 点击「查看上下文」回调（携带当前分片，供上层打开 J4 抽屉） */
  onViewContext: (chunk: MaterialChunk) => void;
}

/**
 * 拆分标题导航路径为面包屑段
 *
 * 兼容后端两种分隔风格：ETL 组装用「 > 」（如 "Ch3 > 3.2"），
 * VO 注释约定用「 / 」，统一拆分为段并去除空段。
 *
 * @param path 标题导航路径（多级标题）
 * @returns 面包屑段落数组
 */
export function splitHeadingPath(path: string): string[] {
  return path
    .split("/")
    .flatMap((segment) => segment.split(" > "))
    .map((segment) => segment.trim())
    .filter(Boolean);
}

/** 面包屑组件 props */
export interface ChunkBreadcrumbProps {
  /** 标题导航路径（null/空串不渲染） */
  path: string | null;
  /** 附加类名（间距微调） */
  className?: string;
}

/**
 * 资料分片面包屑（设计 §1.5.3：stone-500 小字）
 *
 * 将 headingPath 拆分渲染，段间以「 / 」分隔；路径缺失时不渲染整行。
 */
export function ChunkBreadcrumb({ path, className = "" }: ChunkBreadcrumbProps) {
  if (!path) {
    return null;
  }
  const segments = splitHeadingPath(path);
  if (segments.length === 0) {
    return null;
  }
  return (
    <p data-testid="chunk-breadcrumb" className={`text-xs text-muted ${className}`}>
      {segments.map((segment, index) => (
        <span key={`${index}-${segment}`}>
          {index > 0 ? (
            <span aria-hidden className="mx-1 text-subtle">
              /
            </span>
          ) : null}
          {segment}
        </span>
      ))}
    </p>
  );
}

/** 计算页码区间 badge 文案：同页显示单页，无页码（null）为 null */
function pageBadgeText(chunk: MaterialChunk): string | null {
  if (chunk.startPage == null) {
    return null;
  }
  if (chunk.endPage == null || chunk.endPage === chunk.startPage) {
    return `第 ${chunk.startPage} 页`;
  }
  return `第 ${chunk.startPage}-${chunk.endPage} 页`;
}

/**
 * 资料分片卡（设计 §1.5.3）
 *
 * 结构：headingPath 面包屑（stone-500 小字）+ 内容 3 行截断
 * + 页码区间 badge（startPage-endPage，等宽字体承载数字）+ [查看上下文]。
 * 动效（设计 §1.6）：hover 阴影抬升，过渡收窄为 transform/opacity
 * （铁律），reduced-motion 无过渡。
 */
export function ChunkItem({ chunk, onViewContext }: ChunkItemProps) {
  const badge = pageBadgeText(chunk);
  return (
    <article className="rounded-2xl border border-border bg-surface p-4 shadow-sm transition-[transform,opacity] duration-200 motion-reduce:transition-none hover:shadow-md hover:shadow-brand/10">
      <div className="flex items-start justify-between gap-3">
        <ChunkBreadcrumb path={chunk.headingPath} className="mt-0.5" />
        {badge ? (
          <span className="shrink-0 rounded-full border border-border bg-surface-2 px-2.5 py-0.5 font-mono text-xs text-muted tabular-nums">
            {badge}
          </span>
        ) : null}
      </div>
      {/* 内容 3 行截断（长分片折叠，点击查看上下文展开语境） */}
      <p className="mt-2 line-clamp-3 text-[15px] leading-relaxed text-text">{chunk.content}</p>
      <div className="mt-3">
        <button
          type="button"
          onClick={() => onViewContext(chunk)}
          className="inline-flex items-center gap-1.5 rounded-xl border border-brand/30 bg-surface px-3 py-1.5 text-sm font-medium text-brand-strong transition-colors hover:bg-brand-light focus-visible:ring-2 focus-visible:ring-brand"
        >
          <TreeStructure size={15} aria-hidden />
          查看上下文
        </button>
      </div>
    </article>
  );
}
