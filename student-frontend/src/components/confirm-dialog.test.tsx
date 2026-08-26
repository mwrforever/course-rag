/**
 * 通用确认弹窗测试（会话删除/登出二次确认载体）
 *
 * 覆盖：打开渲染（title/description/按钮）、Esc 与遮罩关闭、确认回调、loading 禁用、
 * danger 变体、关闭态不渲染。
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ConfirmDialog } from "./confirm-dialog";

describe("ConfirmDialog", () => {
  it("打开：渲染标题/说明/确认与取消按钮（默认品牌变体）", () => {
    render(
      <ConfirmDialog
        open
        title="删除会话"
        description="确定删除「会话一」吗？"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByRole("dialog", { name: "删除会话" })).toBeInTheDocument();
    expect(screen.getByText("确定删除「会话一」吗？")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "确认" })).toHaveClass("bg-brand");
    expect(screen.getByRole("button", { name: "取消" })).toBeInTheDocument();
  });

  it("danger 变体：确认按钮危险色 + 自定义文案", () => {
    render(
      <ConfirmDialog
        open
        title="退出登录"
        description="确定退出吗？"
        danger
        confirmText="退出"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: "退出" })).toHaveClass("bg-danger");
  });

  it("关闭态：不渲染任何内容", () => {
    render(
      <ConfirmDialog
        open={false}
        title="删除会话"
        description="x"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("确认：点击调用 onConfirm", () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog
        open
        title="删除会话"
        description="x"
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "确认" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it("取消：按钮/Esc/遮罩点击均调用 onCancel", () => {
    const onCancel = vi.fn();
    render(
      <ConfirmDialog
        open
        title="删除会话"
        description="x"
        onConfirm={vi.fn()}
        onCancel={onCancel}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByTestId("confirm-overlay"));
    expect(onCancel).toHaveBeenCalledTimes(3);
  });

  it("loading：确认与取消按钮禁用（防重复提交）", () => {
    render(
      <ConfirmDialog
        open
        title="删除会话"
        description="x"
        loading
        confirmText="删除"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: /删除中/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: "取消" })).toBeDisabled();
  });
});
