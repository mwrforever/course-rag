"use client";

import { X } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
import { ChunkBreadcrumb } from "./chunk-item";
import { getChunkContext } from "@/lib/api";
import type { ChunkBrief, MaterialChunk } from "@/lib/types";

/** 分片上下文抽屉 props */
export interface ChunkContextDrawerProps {
  /** 当前查看上下文的资料分片（null = 抽屉关闭，不渲染） */
  chunk: MaterialChunk | null;
  /** 关闭回调（关闭按钮 / Esc / 点击遮罩触发） */
  onClose: () => void;
}

/** 时间线节点卡 props */
interface ContextNodeCardProps {
  /** 节点标签（父章节/当前分片/上一分片/下一分片） */
  label: string;
  /** 关联分片简报（ChunkBrief 或完整分片） */
  brief: ChunkBrief;
  /** 是否高亮（当前分片：teal 边 + brand-light 底） */
  highlight?: boolean;
}

/**
 * 上下文时间线节点卡：左侧圆点 + 标签 + 面包屑 + 内容 2 行截断
 *
 * @param label 节点标签
 * @param brief 关联分片简报
 * @param highlight 当前分片高亮标记（圆点与卡片样式切换 teal）
 */
function ContextNodeCard({ label, brief, highlight = false }: ContextNodeCardProps) {
  return (
    <li className="relative pl-5">
      {/* 时间线节点圆点（当前分片 teal 实心，其余 stone 描边） */}
      <span
        aria-hidden
        className={`absolute left-0 top-2.5 size-3 rounded-full border-2 ${
          highlight ? "border-brand bg-brand" : "border-border bg-surface-2"
        }`}
      />
      <div
        data-testid={highlight ? "drawer-current-card" : undefined}
        className={`rounded-xl border p-3.5 ${
          highlight ? "border-brand/40 bg-brand-light" : "border-border bg-surface"
        }`}
      >
        <span className={`text-xs font-medium ${highlight ? "text-brand-strong" : "text-subtle"}`}>
          {label}
        </span>
        <ChunkBreadcrumb path={brief.headingPath} className="mt-1" />
        <p className="mt-1.5 line-clamp-2 text-sm leading-relaxed text-muted">{brief.content}</p>
      </div>
    </li>
  );
}

/**
 * 分片上下文抽屉面板（当前分片非空时挂载）
 *
 * 结构（设计 §1.5.3）：当前分片高亮卡居中，父章节卡在上、prev/next 分片卡在下，
 * 时间线式连线；空关联项（恒 null）不渲染节点。J4 上下文经 getChunkContext 拉取。
 *
 * @param chunk 当前分片（高亮卡内容直接取自分片本体，无需等待上下文接口）
 * @param onClose 关闭回调
 */
function ChunkContextPanel({ chunk, onClose }: { chunk: MaterialChunk; onClose: () => void }) {
  const contextQuery = useQuery({
    queryKey: ["chunk-context", chunk.id],
    queryFn: () => getChunkContext(chunk.id),
  });
  const context = contextQuery.data;
  const hasNeighbors = Boolean(context && (context.parent || context.prev || context.next));

  return (
    <div className="fixed inset-0 z-50">
      {/* 遮罩：点击空白关闭；动画只做 opacity（动效铁律 transform/opacity） */}
      <div
        data-testid="drawer-overlay"
        aria-hidden
        onClick={onClose}
        className="absolute inset-0 animate-overlay-in bg-overlay motion-reduce:animate-none"
      />
      {/* 抽屉面板：480px 右侧滑入（transform/opacity，reduced-motion 静态） */}
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="分片上下文"
        className="absolute top-0 right-0 flex h-full w-full max-w-[480px] animate-drawer-in flex-col bg-surface shadow-xl motion-reduce:animate-none"
      >
        <header className="flex items-center justify-between border-b border-border px-5 py-4">
          <h3 className="font-display text-lg font-semibold text-text">分片上下文</h3>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="grid size-9 place-items-center rounded-xl text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
          >
            <X size={18} aria-hidden />
          </button>
        </header>
        <div className="flex-1 overflow-y-auto p-5">
          {contextQuery.isPending ? (
            <>
              {/* 当前分片高亮卡：内容来自分片本体，等待期间也可读 */}
              <ContextNodeCard label="当前分片" brief={chunk} highlight />
              <div data-testid="drawer-skeleton" className="mt-4 space-y-3" aria-busy="true">
                <div className="h-16 animate-pulse rounded-xl bg-surface-2" />
                <div className="h-16 animate-pulse rounded-xl bg-surface-2" />
                <div className="h-16 animate-pulse rounded-xl bg-surface-2" />
              </div>
            </>
          ) : contextQuery.isError ? (
            <>
              <ContextNodeCard label="当前分片" brief={chunk} highlight />
              <div className="mt-4 flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3">
                <p className="text-sm text-danger">上下文加载失败，请重试</p>
                <button
                  type="button"
                  onClick={() => void contextQuery.refetch()}
                  className="shrink-0 rounded-xl border border-danger/30 bg-surface px-3 py-1.5 text-sm font-medium text-danger transition-colors hover:bg-danger/10 focus-visible:ring-2 focus-visible:ring-danger"
                >
                  重试
                </button>
              </div>
            </>
          ) : context ? (
            hasNeighbors ? (
              /* 时间线：父章节在上 → 当前分片居中 → prev/next 在下，竖线连接 */
              <div className="relative mt-2">
                <span aria-hidden className="absolute top-4 bottom-4 left-[5px] w-px bg-border" />
                <ol className="space-y-4" data-testid="drawer-timeline">
                  {context.parent ? (
                    <ContextNodeCard label="父章节" brief={context.parent} />
                  ) : null}
                  <ContextNodeCard label="当前分片" brief={chunk} highlight />
                  {context.prev ? <ContextNodeCard label="上一分片" brief={context.prev} /> : null}
                  {context.next ? <ContextNodeCard label="下一分片" brief={context.next} /> : null}
                </ol>
              </div>
            ) : (
              <>
                <ContextNodeCard label="当前分片" brief={chunk} highlight />
                <p className="mt-6 text-center text-sm text-subtle">该分片暂无上下文关联</p>
              </>
            )
          ) : null}
        </div>
      </aside>
    </div>
  );
}

/**
 * 分片上下文抽屉（设计 §1.5.3 / J4）
 *
 * chunk 为 null 时不渲染；开启期间监听 Esc 键关闭（可访问性 §3.4）。
 * 面板滑入动效走 CSS 关键帧（仅 transform/opacity），reduced-motion 降级静态。
 *
 * @param chunk 当前查看上下文的资料分片（null = 关闭）
 * @param onClose 关闭回调
 */
export function ChunkContextDrawer({ chunk, onClose }: ChunkContextDrawerProps) {
  // Esc 关闭监听：抽屉开启期间挂载（hook 无条件调用，内部按 chunk 判空）
  useEffect(() => {
    if (!chunk) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [chunk, onClose]);

  if (!chunk) {
    return null;
  }
  return <ChunkContextPanel chunk={chunk} onClose={onClose} />;
}
