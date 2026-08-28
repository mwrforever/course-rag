"use client";

/**
 * 附件 chips 与前置校验（设计 §1.5.4 附件规范 + G11 白名单；2026-08-29 扩容：
 * 格式图标映射 + chip 点击预览）
 *
 * 校验契约（镜像后端，超限即拒且不发任何网络请求）：
 * - 一次 ≤10 个文件
 * - 图片（jpg/jpeg/png/gif/webp/bmp）单个 ≤10MB
 * - 文档（pdf/doc/docx/txt/md）单个 ≤50MB
 * - 全部附件合计 ≤100MB
 * - 白名单外类型（非图片/文档扩展名）拒绝
 * 边界语义：恰等于上限放行（≤）。
 *
 * chips 三态：上传中（不确定进度环，后端无进度事件）/ 完成（图片 blob 缩略图
 * 或格式图标 + 字节大小）/ 失败（可移除）。
 * 交互（Task 12）：chip 主体点击 → onPreview 打开预览弹窗（图片 Zoom / pdf iframe /
 * 其他图标卡）；移除钮独立于预览触发器（嵌套按钮拆平级）。
 * 线程安全：纯展示组件 + 纯函数校验，无共享可变状态。
 */
import {
  FileDoc,
  FilePdf,
  FilePpt,
  FileText,
  FileXls,
  Image,
  ImageSquare,
  X,
  type Icon as PhosphorIcon,
} from "@phosphor-icons/react";
import type { AttachmentRecord } from "@/lib/types";

/** 图片白名单扩展名（G11：C 端附件清单，禁止与 B 端互抄） */
export const IMAGE_EXTENSIONS = ["jpg", "jpeg", "png", "gif", "webp", "bmp"] as const;
/** 文档白名单扩展名 */
export const DOC_EXTENSIONS = ["pdf", "doc", "docx", "txt", "md"] as const;
/** 单次附件数量上限 */
export const MAX_ATTACHMENTS = 10;
/** 图片单个大小上限（字节，10MB） */
export const MAX_IMAGE_SIZE = 10 * 1024 * 1024;
/** 文档单个大小上限（字节，50MB） */
export const MAX_DOC_SIZE = 50 * 1024 * 1024;
/** 单会话附件合计上限（字节，100MB） */
export const MAX_TOTAL_SIZE = 100 * 1024 * 1024;

/** 校验结果：ok=false 时 reason 为中文拒因（页面 toast 展示） */
export interface AttachmentValidation {
  ok: boolean;
  reason: string | null;
}

/**
 * 附件本地待传条目（页面持有，随 ChatRequest 发送后转为消息内附件记录）
 */
export interface PendingAttachment {
  /** 本地唯一 id（chip key 与移除锚点） */
  id: string;
  /** 原始文件（校验与合并大小计算依据） */
  file: File;
  /** 上传成功后的附件记录；null=上传中/失败 */
  record: AttachmentRecord | null;
  /** uploading=上传中（进度环）/ done=完成 / error=失败 */
  status: "uploading" | "done" | "error";
  /** 本地 blob URL（图片缩略图预览；文档仅图标）；过期时由页面 revoke */
  blobUrl: string;
}

/**
 * 文件类型归类：MIME 或扩展名命中图片白名单 → image；文档白名单 → document；
 * 两者皆不命中 → null（拒绝）
 *
 * @param file 待归类文件（可能是 httpOnly 之外的任意来源点击选择）
 */
export function classifyFile(file: File): "image" | "document" | null {
  const ext = file.name.toLowerCase().split(".").pop() ?? "";
  if (
    file.type.startsWith("image/") ||
    IMAGE_EXTENSIONS.includes(ext as (typeof IMAGE_EXTENSIONS)[number])
  ) {
    return "image";
  }
  if (DOC_EXTENSIONS.includes(ext as (typeof DOC_EXTENSIONS)[number])) {
    return "document";
  }
  return null;
}

/**
 * 前置校验（镜像后端限制，超限即拒，调用方不得发起上传请求）
 *
 * 检查顺序：数量 → 逐文件类型与大小 → 合计大小；首个失败即返回拒因。
 *
 * @param files 本次新增选择的文件
 * @param existing 已选中的既有文件（chips 中全部状态）
 * @returns ok=true 放行；false 携带中文拒因
 */
export function validateAttachments(files: File[], existing: File[]): AttachmentValidation {
  if (files.length === 0) {
    return { ok: true, reason: null };
  }
  // 数量上限：已有 + 新增 超过 10 个即拒
  if (existing.length + files.length > MAX_ATTACHMENTS) {
    return { ok: false, reason: `一次最多上传 ${MAX_ATTACHMENTS} 个文件` };
  }
  for (const file of files) {
    const kind = classifyFile(file);
    if (kind === null) {
      return { ok: false, reason: `不支持的文件类型：${file.name}` };
    }
    // 图片 ≤10MB / 文档 ≤50MB（阈值数值全配置化，常量集中）
    if (kind === "image" && file.size > MAX_IMAGE_SIZE) {
      return {
        ok: false,
        reason: `图片 ${file.name} 不能超过 ${MAX_IMAGE_SIZE / 1024 / 1024}MB`,
      };
    }
    if (kind === "document" && file.size > MAX_DOC_SIZE) {
      return {
        ok: false,
        reason: `文档 ${file.name} 不能超过 ${MAX_DOC_SIZE / 1024 / 1024}MB`,
      };
    }
  }
  // 合计上限：已有 + 新增 超过 100MB 即拒
  const total = [...existing, ...files].reduce((sum, file) => sum + file.size, 0);
  if (total > MAX_TOTAL_SIZE) {
    return {
      ok: false,
      reason: `附件总大小不能超过 ${MAX_TOTAL_SIZE / 1024 / 1024}MB`,
    };
  }
  return { ok: true, reason: null };
}

/** 字节数人类可读格式化（KB/MB 保留 1 位小数，等宽数字展示） */
export function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

// ===== 格式图标映射（Task 12 扩容：chips 与预览弹窗共用） =====

/** 附件格式分类（图标映射依据；xls/ppt 超出上传白名单但保留映射，兼容历史消息渲染） */
export type AttachmentFormatKind = "image" | "pdf" | "doc" | "xls" | "ppt" | "text";

/** 扩展名 → 格式分类映射表（小写匹配；无扩展名/未知扩展名兜底 text） */
const EXTENSION_KINDS: Record<string, AttachmentFormatKind> = {
  jpg: "image",
  jpeg: "image",
  png: "image",
  gif: "image",
  webp: "image",
  bmp: "image",
  pdf: "pdf",
  doc: "doc",
  docx: "doc",
  xls: "xls",
  xlsx: "xls",
  ppt: "ppt",
  pptx: "ppt",
  txt: "text",
  md: "text",
};

/**
 * 附件格式分类：按文件扩展名查映射表
 *
 * @param fileName 文件名（含扩展名；大小写不敏感）
 * @returns 格式分类（未知/缺失扩展名降级 text，FileText 图标兜底）
 */
export function attachmentFormatKind(fileName: string): AttachmentFormatKind {
  const ext = fileName.toLowerCase().split(".").pop() ?? "";
  return EXTENSION_KINDS[ext] ?? "text";
}

/**
 * 格式分类 → Phosphor 图标映射表（Task 12 指定：ImageSquare/FilePdf/FileDoc/
 * FileXls/FilePpt/FileText；chips 文档态与预览弹窗图标卡共用）
 */
export const FORMAT_ICONS: Record<AttachmentFormatKind, PhosphorIcon> = {
  image: ImageSquare,
  pdf: FilePdf,
  doc: FileDoc,
  xls: FileXls,
  ppt: FilePpt,
  text: FileText,
};

/** 附件 chips 组件 props */
export interface AttachmentChipsProps {
  /** 待传/已传/失败条目列表（全部状态） */
  items: PendingAttachment[];
  /** 移除回调（携带本地 id，页面负责 revoke blob URL） */
  onRemove(id: string): void;
  /** chip 点击预览回调（携带整条目；预览弹窗由页面挂载，Task 12） */
  onPreview?(item: PendingAttachment): void;
}

/**
 * 附件 chips 行（上传中进度环 / 图片缩略图 / 格式图标 + 移除；chip 主体点击预览）
 *
 * @param items 条目列表（含上传中与失败态）
 * @param onRemove 移除回调
 * @param onPreview 预览回调（可选；不提供时 chip 主体不可点击）
 */
export function AttachmentChips({ items, onRemove, onPreview }: AttachmentChipsProps) {
  return (
    <div className="flex flex-wrap gap-2" data-testid="attachment-chips">
      {items.map((item) => {
        // 文档完成态格式图标（pdf/doc/xls/ppt/txt/md 分类映射；Task 12）
        const FormatIcon = FORMAT_ICONS[attachmentFormatKind(item.file.name)];
        return (
          <div
            key={item.id}
            data-testid="attachment-chip"
            className="flex items-center gap-2 rounded-xl border border-border bg-surface px-2.5 py-1.5 text-sm"
          >
            {/* chip 主体 = 预览触发器（与移除钮平级，避免嵌套按钮） */}
            <button
              type="button"
              aria-label={`预览附件：${item.file.name}`}
              data-testid="attachment-chip-preview"
              onClick={() => onPreview?.(item)}
              className="flex min-w-0 items-center gap-2 rounded-lg text-left focus-visible:ring-2 focus-visible:ring-brand"
            >
              {item.status === "uploading" ? (
                // 上传中：不确定进度环（后端无进度事件，环形旋转表示进行中）
                <span
                  data-testid="attachment-ring"
                  aria-label="上传中"
                  className="size-4 shrink-0 animate-spin rounded-full border-2 border-brand/25 border-t-brand motion-reduce:animate-none"
                />
              ) : item.status === "done" && item.record?.type === "image" ? (
                // 完成图片：本地 blob 缩略图（记录 url 为 objectKey 不可直接访问，D12）
                // eslint-disable-next-line @next/next/no-img-element -- blob: URL 无法走 next/image 优化器，本地预览用原生 img
                <img
                  src={item.blobUrl}
                  alt={`缩略图：${item.file.name}`}
                  className="size-9 shrink-0 rounded-lg border border-border object-cover"
                />
              ) : item.status === "error" ? (
                // eslint-disable-next-line jsx-a11y/alt-text -- Phosphor 的 Image 图标组件被规则误判（非原生 img 元素）
                <Image size={16} className="shrink-0 text-danger" aria-hidden />
              ) : (
                // 完成文档：按扩展名映射格式图标（pdf/doc/xls/ppt/text 分类）
                <FormatIcon size={16} className="shrink-0 text-muted" aria-hidden />
              )}
              <span className="min-w-0">
                <span className="block max-w-40 truncate text-xs font-medium text-text">
                  {item.file.name}
                </span>
                <span className="block text-xs text-subtle tabular-nums">
                  {/* 三态释义：上传中 / 失败 / 大小 */}
                  {item.status === "uploading"
                    ? "上传中"
                    : item.status === "error"
                      ? "上传失败"
                      : formatBytes(item.file.size)}
                </span>
              </span>
            </button>
            <button
              type="button"
              aria-label={`移除附件：${item.file.name}`}
              onClick={() => onRemove(item.id)}
              className="grid size-6 shrink-0 place-items-center rounded-lg text-subtle transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
            >
              <X size={12} aria-hidden />
            </button>
          </div>
        );
      })}
    </div>
  );
}
