/**
 * 附件 chips 与前置校验测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 附件规范）：
 * - 前置校验镜像后端：≤10 个/次、图片 ≤10MB（jpg/jpeg/png/gif/webp/bmp）、
 *   文档 ≤50MB（pdf/doc/docx/txt/md）、合计 ≤100MB；边界值（恰等于上限）放行，
 *   超限即拒（页面侧不再发网络请求，由页面集成测试断言无 fetch）
 * - chips 三态渲染：上传中（进度环）/ 完成（图片 blob 缩略图或文档图标）/ 失败
 * - 移除回调：onRemove 携带本地 id
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  AttachmentChips,
  FORMAT_ICONS,
  MAX_ATTACHMENTS,
  MAX_DOC_SIZE,
  MAX_IMAGE_SIZE,
  MAX_TOTAL_SIZE,
  attachmentFormatKind,
  validateAttachments,
  type AttachmentFormatKind,
} from "./attachment-chips";
import type { PendingAttachment } from "./attachment-chips";

/** 构造指定大小与类型的 File（jsdom 无真实文件，用 Uint8Array 撑大小） */
function makeFile(name: string, size: number, type = "application/octet-stream"): File {
  return new File([new Uint8Array(size)], name, { type });
}

/** MIME 辅助：图片 / PDF 文档 */
const IMAGE_TYPE = "image/png";
const PDF_TYPE = "application/pdf";

describe("validateAttachments 前置校验（超限即拒，无网络请求前置）", () => {
  it("空选择：直接放行", () => {
    expect(validateAttachments([], []).ok).toBe(true);
  });

  it("正常：单张 1MB 图片与 5MB 文档均放行", () => {
    const image = makeFile("图.png", 1024 * 1024, IMAGE_TYPE);
    const doc = makeFile("笔记.pdf", 5 * 1024 * 1024, PDF_TYPE);
    expect(validateAttachments([image, doc], []).ok).toBe(true);
  });

  it("数量超限：第 11 个文件拒绝（已有 10 个 + 新增 1 个）", () => {
    const existing = Array.from({ length: MAX_ATTACHMENTS }, (_, i) =>
      makeFile(`已传-${i}.txt`, 10, "text/plain"),
    );
    const incoming = makeFile("多一个.txt", 10, "text/plain");
    const result = validateAttachments([incoming], existing);
    expect(result.ok).toBe(false);
    expect(result.reason).toContain("10");
  });

  it("图片超 10MB：拒绝并指出文件名与上限", () => {
    const huge = makeFile("大图.png", MAX_IMAGE_SIZE + 1, IMAGE_TYPE);
    const result = validateAttachments([huge], []);
    expect(result.ok).toBe(false);
    expect(result.reason).toContain("大图.png");
    expect(result.reason).toContain("10MB");
  });

  it("图片恰 10MB：边界放行", () => {
    expect(validateAttachments([makeFile("边界.png", MAX_IMAGE_SIZE, IMAGE_TYPE)], []).ok).toBe(
      true,
    );
  });

  it("文档超 50MB：拒绝并指出文件名与上限", () => {
    const huge = makeFile("大文档.pdf", MAX_DOC_SIZE + 1, PDF_TYPE);
    const result = validateAttachments([huge], []);
    expect(result.ok).toBe(false);
    expect(result.reason).toContain("大文档.pdf");
    expect(result.reason).toContain("50MB");
  });

  it("文档恰 50MB：边界放行", () => {
    expect(validateAttachments([makeFile("边界.pdf", MAX_DOC_SIZE, PDF_TYPE)], []).ok).toBe(true);
  });

  it("合计超 100MB：已有 40MB + 新增 31MB+31MB 拒绝", () => {
    const existing = [makeFile("已传.pdf", 40 * 1024 * 1024, PDF_TYPE)];
    const incoming = [
      makeFile("新增一.pdf", 31 * 1024 * 1024, PDF_TYPE),
      makeFile("新增二.pdf", 31 * 1024 * 1024, PDF_TYPE),
    ];
    const result = validateAttachments(incoming, existing);
    expect(result.ok).toBe(false);
    expect(result.reason).toContain("100MB");
  });

  it("合计恰 100MB：边界放行", () => {
    const existing = [makeFile("已传.pdf", 40 * 1024 * 1024, PDF_TYPE)];
    const incoming = [
      makeFile("新增一.pdf", 31 * 1024 * 1024, PDF_TYPE),
      makeFile("新增二.pdf", MAX_TOTAL_SIZE - 40 * 1024 * 1024 - 31 * 1024 * 1024, PDF_TYPE),
    ];
    expect(validateAttachments(incoming, existing).ok).toBe(true);
  });

  it("不支持的文件类型（.exe）：拒绝", () => {
    const result = validateAttachments([makeFile("病毒.exe", 10, "application/x-msdownload")], []);
    expect(result.ok).toBe(false);
    expect(result.reason).toContain("不支持");
  });

  it("空扩展名 + 普通 MIME（text/plain）：按文档白名单放行", () => {
    expect(validateAttachments([makeFile("说明.txt", 10, "text/plain")], []).ok).toBe(true);
  });
});

describe("AttachmentChips 三态渲染", () => {
  /** 构造 pending 条目（blob URL 用固定 mock 值） */
  function makeItem(overrides: Partial<PendingAttachment>): PendingAttachment {
    return {
      id: "att-1",
      file: makeFile("图.png", 1024, IMAGE_TYPE),
      record: null,
      status: "uploading",
      blobUrl: "blob:mock-1",
      ...overrides,
    };
  }

  it("上传中：进度环 + 文件名 + 上传中文案", () => {
    render(<AttachmentChips items={[makeItem({})]} onRemove={() => {}} />);
    expect(screen.getByTestId("attachment-ring")).toBeInTheDocument();
    expect(screen.getByText("图.png")).toBeInTheDocument();
    expect(screen.getByText(/上传中/)).toBeInTheDocument();
  });

  it("完成图片：blob 缩略图（本地预览）+ 文件名", () => {
    const item = makeItem({
      status: "done",
      record: { type: "image", url: "obj/1.png", name: "图.png", size: "1024" },
    });
    render(<AttachmentChips items={[item]} onRemove={() => {}} />);
    const img = screen.getByRole("img", { name: /图\.png/ });
    expect(img).toHaveAttribute("src", "blob:mock-1");
  });

  it("完成文档：文档图标 + 文件名 + 字节大小（tabular-nums）", () => {
    const item = makeItem({
      status: "done",
      file: makeFile("笔记.pdf", 2048, PDF_TYPE),
      record: { type: "document", url: "obj/1.pdf", name: "笔记.pdf", size: "2048" },
    });
    render(<AttachmentChips items={[item]} onRemove={() => {}} />);
    expect(screen.getByText("笔记.pdf")).toBeInTheDocument();
    expect(screen.getByText(/2.0/)).toBeInTheDocument();
  });

  it("失败态：上传失败文案 + 可移除", () => {
    render(<AttachmentChips items={[makeItem({ status: "error" })]} onRemove={() => {}} />);
    expect(screen.getByText(/上传失败/)).toBeInTheDocument();
  });

  it("点移除：onRemove 携带本地 id", () => {
    const onRemove = vi.fn();
    render(<AttachmentChips items={[makeItem({})]} onRemove={onRemove} />);
    fireEvent.click(screen.getByRole("button", { name: /移除/ }));
    expect(onRemove).toHaveBeenCalledWith("att-1");
  });
});

describe("附件格式图标映射（Task 12 扩容）", () => {
  it("attachmentFormatKind：扩展名 → 格式分类全覆盖（含白名单兜底）", () => {
    // 图片白名单全扩展名
    for (const ext of ["jpg", "jpeg", "png", "gif", "webp", "bmp"]) {
      expect(attachmentFormatKind(`图.${ext}`)).toBe("image");
    }
    // 文档格式逐类映射
    expect(attachmentFormatKind("讲义.pdf")).toBe("pdf");
    expect(attachmentFormatKind("笔记.doc")).toBe("doc");
    expect(attachmentFormatKind("笔记.docx")).toBe("doc");
    expect(attachmentFormatKind("成绩单.xls")).toBe("xls");
    expect(attachmentFormatKind("成绩单.xlsx")).toBe("xls");
    expect(attachmentFormatKind("课件.ppt")).toBe("ppt");
    expect(attachmentFormatKind("课件.pptx")).toBe("ppt");
    expect(attachmentFormatKind("说明.txt")).toBe("text");
    expect(attachmentFormatKind("说明.md")).toBe("text");
    // 无扩展名 / 未知扩展名兜底为 text（FileText 图标）
    expect(attachmentFormatKind("无扩展名")).toBe("text");
  });

  it("FORMAT_ICONS：六类格式各配置一枚 Phosphor 图标（映射表完整性）", () => {
    const kinds: AttachmentFormatKind[] = ["image", "pdf", "doc", "xls", "ppt", "text"];
    for (const kind of kinds) {
      expect(FORMAT_ICONS[kind]).toBeDefined();
    }
  });

  it("chip 点击（非移除钮）→ onPreview 携带整条目（预览弹窗入口）", () => {
    const onPreview = vi.fn();
    const item: PendingAttachment = {
      id: "att-1",
      file: makeFile("讲义.pdf", 2048, PDF_TYPE),
      record: { type: "document", url: "obj/1.pdf", name: "讲义.pdf", size: "2048" },
      status: "done",
      blobUrl: "blob:mock-1",
    };
    render(<AttachmentChips items={[item]} onRemove={() => {}} onPreview={onPreview} />);
    fireEvent.click(screen.getByRole("button", { name: /预览附件：讲义\.pdf/ }));
    expect(onPreview).toHaveBeenCalledWith(item);
  });
});
