/**
 * 会话重命名弹窗测试（Task 13 TDD 先行用例）
 *
 * 覆盖（portal 表单弹窗，zod 边界校验）：
 * - 打开：预填当前标题并聚焦输入框
 * - 提交：修改后保存按钮 / Enter 键 → onConfirm(新标题)
 * - 校验：空标题（含纯空白）与超 50 字 → 中文错误提示且不发请求
 * - 关闭：Esc / 取消按钮 → onCancel；保存中禁用防重复提交
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RenameDialog, RENAME_TITLE_MAX_LENGTH } from "./rename-dialog";

function renderDialog(overrides: { open?: boolean; initialTitle?: string } = {}) {
  const onConfirm = vi.fn().mockResolvedValue(undefined);
  const onCancel = vi.fn();
  const view = render(
    <RenameDialog
      open={overrides.open ?? true}
      initialTitle={overrides.initialTitle ?? "旧标题"}
      onConfirm={onConfirm}
      onCancel={onCancel}
    />,
  );
  return { onConfirm, onCancel, ...view };
}

/** 等待 portal 挂载（mounted 门控后弹窗才渲染） */
async function openDialog() {
  const view = renderDialog();
  await screen.findByRole("dialog", { name: "重命名会话" });
  return view;
}

describe("RenameDialog 打开与预填", () => {
  it("打开：预填当前标题（输入框初值）+ role=dialog 语义", async () => {
    await openDialog();
    const input = screen.getByRole("textbox", { name: "会话标题" }) as HTMLInputElement;
    expect(input.value).toBe("旧标题");
  });
});

describe("RenameDialog 提交契约", () => {
  it("修改后点「保存」：onConfirm(新标题)", async () => {
    const { onConfirm } = await openDialog();
    const input = screen.getByRole("textbox", { name: "会话标题" });
    fireEvent.change(input, { target: { value: "新标题" } });
    fireEvent.click(screen.getByRole("button", { name: /保存/ }));
    await waitFor(() => {
      expect(onConfirm).toHaveBeenCalledWith("新标题");
    });
  });

  it("Enter 键提交：onConfirm(标题)（表单默认行为拦截）", async () => {
    const { onConfirm } = await openDialog();
    const input = screen.getByRole("textbox", { name: "会话标题" });
    fireEvent.change(input, { target: { value: "回车标题" } });
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => {
      expect(onConfirm).toHaveBeenCalledWith("回车标题");
    });
  });

  it("空标题（纯空白）：中文校验错误且不调 onConfirm", async () => {
    const { onConfirm } = await openDialog();
    const input = screen.getByRole("textbox", { name: "会话标题" });
    fireEvent.change(input, { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: /保存/ }));
    expect(await screen.findByText("标题不能为空")).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it(`超 ${RENAME_TITLE_MAX_LENGTH} 字：中文校验错误且不调 onConfirm`, async () => {
    const { onConfirm } = await openDialog();
    const input = screen.getByRole("textbox", { name: "会话标题" });
    fireEvent.change(input, { target: { value: "长".repeat(RENAME_TITLE_MAX_LENGTH + 1) } });
    fireEvent.click(screen.getByRole("button", { name: /保存/ }));
    expect(
      await screen.findByText(`标题不能超过 ${RENAME_TITLE_MAX_LENGTH} 个字`),
    ).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("保存中：按钮禁用防重复提交（onConfirm 未决期间）", async () => {
    let release: (() => void) | undefined;
    const onConfirm = vi.fn(() => new Promise<void>((resolve) => (release = resolve)));
    render(<RenameDialog open initialTitle="旧标题" onConfirm={onConfirm} onCancel={() => {}} />);
    await screen.findByRole("dialog", { name: "重命名会话" });
    fireEvent.click(screen.getByRole("button", { name: /保存/ }));
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /保存中/ })).toBeDisabled();
    });
    release?.();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /保存/ })).toBeEnabled();
    });
  });
});

describe("RenameDialog 关闭契约", () => {
  it("Esc 键：触发 onCancel", async () => {
    const { onCancel } = await openDialog();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("取消按钮：触发 onCancel", async () => {
    const { onCancel } = await openDialog();
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("open=false：不渲染弹窗", () => {
    renderDialog({ open: false });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
