/**
 * 附件预览弹窗测试（Task 12 TDD 先行用例）
 *
 * 覆盖（三类预览形态 + 关闭契约，portal 范式对齐 ConfirmDialog）：
 * - 图片：Zoom 放大包装内的 blob 缩略大图（react-medium-image-zoom）
 * - pdf：iframe 直接内嵌 blob URL
 * - 其他文档（doc/xls/ppt/txt/md）：格式图标卡 + 下载链接（blob URL + download 属性）
 * - Esc / 遮罩点击 / 关闭按钮 → onClose；item=null 不渲染
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AttachmentPreviewDialog } from "./attachment-preview-dialog";
import type { PendingAttachment } from "./attachment-chips";

/** 构造预览条目（blobUrl 固定 mock；file 按用例指定名称与 MIME） */
function makeItem(name: string, type: string): PendingAttachment {
  return {
    id: "att-1",
    file: new File([new Uint8Array(64)], name, { type }),
    record: null,
    status: "done",
    blobUrl: "blob:mock-preview",
  };
}

function renderDialog(item: PendingAttachment | null) {
  return render(<AttachmentPreviewDialog item={item} onClose={() => {}} />);
}

describe("AttachmentPreviewDialog 三类预览形态", () => {
  it("图片：Zoom 包装内渲染 blob 大图（alt=文件名）", () => {
    renderDialog(makeItem("截图.png", "image/png"));
    const img = screen.getByRole("img", { name: /截图\.png/ });
    expect(img).toHaveAttribute("src", "blob:mock-preview");
  });

  it("pdf：iframe 内嵌 blob URL（title=文件名）", () => {
    renderDialog(makeItem("讲义.pdf", "application/pdf"));
    const frame = screen.getByTestId("attachment-preview-pdf");
    expect(frame).toHaveAttribute("src", "blob:mock-preview");
  });

  it("其他文档：格式图标卡 + 下载链接（blob URL + download 属性）", () => {
    renderDialog(makeItem("笔记.docx", "application/octet-stream"));
    const download = screen.getByRole("link", { name: /下载文件/ });
    expect(download).toHaveAttribute("href", "blob:mock-preview");
    expect(download).toHaveAttribute("download", "笔记.docx");
    // 无图片/iframe 预览形态（doc 走图标卡分支）
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(screen.queryByTestId("attachment-preview-pdf")).not.toBeInTheDocument();
  });

  it("弹窗语义：role=dialog + aria-label 携带文件名", () => {
    renderDialog(makeItem("讲义.pdf", "application/pdf"));
    expect(screen.getByRole("dialog", { name: /预览附件：讲义\.pdf/ })).toBeInTheDocument();
  });
});

describe("AttachmentPreviewDialog 关闭契约", () => {
  it("Esc 键：触发 onClose", () => {
    const onClose = vi.fn();
    render(<AttachmentPreviewDialog item={makeItem("图.png", "image/png")} onClose={onClose} />);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("遮罩点击：触发 onClose", () => {
    const onClose = vi.fn();
    render(<AttachmentPreviewDialog item={makeItem("图.png", "image/png")} onClose={onClose} />);
    fireEvent.click(screen.getByTestId("attachment-preview-overlay"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("关闭按钮：触发 onClose", () => {
    const onClose = vi.fn();
    render(<AttachmentPreviewDialog item={makeItem("图.png", "image/png")} onClose={onClose} />);
    fireEvent.click(screen.getByRole("button", { name: /关闭预览/ }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("item=null：不渲染弹窗", () => {
    renderDialog(null);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
