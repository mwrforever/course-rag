"use client";

/**
 * 附件预览弹窗（Task 12 扩容：图片 Zoom 放大 / pdf iframe 内嵌 / 其他格式图标卡）
 *
 * 三类预览形态（按扩展名分类，与 chips 格式图标映射同源）：
 * - image：react-medium-image-zoom 包装 blob 大图（点击放大/滚轮缩放，React 19 兼容已实证）
 * - pdf：iframe 直接内嵌 blob URL（浏览器原生 PDF 查看器）
 * - 其他文档：格式图标卡 + 下载链接（blob URL + download 属性，本地落盘查看）
 *
 * 关闭契约（对齐 ConfirmDialog portal 范式）：Esc / 遮罩点击 / 关闭按钮 → onClose；
 * createPortal 挂 document.body（调用方处 sticky/backdrop-blur/transform 容器，
 * fixed 子元素包含块会被收窄，portal 保证 fixed inset-0 恒相对视口）；
 * mounted 挂载态兜底 SSR 无 document（hydration 首帧不渲染，交互触发打开无此问题）。
 */
import { X } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import Zoom from "react-medium-image-zoom";
import "react-medium-image-zoom/dist/styles.css";
import {
  FORMAT_ICONS,
  attachmentFormatKind,
  formatBytes,
  type PendingAttachment,
} from "./attachment-chips";

/** 附件预览弹窗 props */
export interface AttachmentPreviewDialogProps {
  /** 预览目标条目（null=关闭不渲染） */
  item: PendingAttachment | null;
  /** 关闭回调（Esc/遮罩/关闭按钮共同出口） */
  onClose(): void;
}

/**
 * 附件预览弹窗（三类形态 + portal 关闭契约）
 *
 * @param props 见 AttachmentPreviewDialogProps
 */
export function AttachmentPreviewDialog({ item, onClose }: AttachmentPreviewDialogProps) {
  // 客户端挂载标记：SSR/hydration 首帧不渲染（portal 依赖 document.body）
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  // Esc 关闭（可访问性：键盘直达关闭出口）
  useEffect(() => {
    if (!item) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [item, onClose]);

  if (!item || !mounted) {
    return null;
  }

  // 格式分类（与 chips 图标映射同源：image/pdf/doc/xls/ppt/text）
  const kind = attachmentFormatKind(item.file.name);
  const FormatIcon = FORMAT_ICONS[kind];

  return createPortal(
    <div className="fixed inset-0 z-50">
      <div
        data-testid="attachment-preview-overlay"
        aria-hidden
        onClick={onClose}
        className="absolute inset-0 animate-overlay-in bg-overlay motion-reduce:animate-none"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`预览附件：${item.file.name}`}
        className="absolute top-1/2 left-1/2 flex max-h-[85vh] w-full max-w-3xl -translate-x-1/2 -translate-y-1/2 animate-drawer-in flex-col rounded-2xl border border-border bg-surface p-4 shadow-xl motion-reduce:animate-none"
      >
        {/* 头部：文件名 + 大小 + 关闭按钮 */}
        <div className="flex shrink-0 items-center gap-3">
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-text">{item.file.name}</p>
            <p className="text-xs text-subtle tabular-nums">{formatBytes(item.file.size)}</p>
          </div>
          <button
            type="button"
            aria-label="关闭预览"
            onClick={onClose}
            className="grid size-8 shrink-0 place-items-center rounded-lg text-subtle transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
          >
            <X size={16} aria-hidden />
          </button>
        </div>
        {/* 预览体：三类形态互斥渲染 */}
        <div className="mt-3 min-h-0 flex-1 overflow-auto">
          {kind === "image" ? (
            // 图片：Zoom 包装 blob 大图（点击进入放大态，滚轮/按钮缩放）
            <Zoom>
              {/* eslint-disable-next-line @next/next/no-img-element -- blob: URL 无法走 next/image 优化器，本地预览用原生 img */}
              <img
                src={item.blobUrl}
                alt={item.file.name}
                data-testid="attachment-preview-image"
                className="max-h-[70vh] w-auto max-w-full rounded-lg border border-border object-contain"
              />
            </Zoom>
          ) : kind === "pdf" ? (
            // pdf：iframe 内嵌 blob URL（浏览器原生 PDF 查看器，无需额外依赖）
            <iframe
              src={item.blobUrl}
              title={item.file.name}
              data-testid="attachment-preview-pdf"
              className="h-[70vh] w-full rounded-lg border border-border bg-surface-2"
            />
          ) : (
            // 其他文档：格式图标卡 + 下载链接（doc/xls/ppt 等浏览器不可直接预览，落盘查看）
            <div
              data-testid="attachment-preview-card"
              className="flex flex-col items-center gap-4 rounded-xl border border-border bg-surface-2 px-6 py-10 text-center"
            >
              <FormatIcon size={48} weight="light" className="text-brand" aria-hidden />
              <div className="space-y-1">
                <p className="text-sm font-medium text-text">{item.file.name}</p>
                <p className="text-xs text-subtle">此格式暂不支持在线预览</p>
              </div>
              <a
                href={item.blobUrl}
                download={item.file.name}
                data-testid="attachment-preview-download"
                className="rounded-xl bg-brand px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
              >
                下载文件
              </a>
            </div>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}
