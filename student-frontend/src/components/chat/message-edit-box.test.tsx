/**
 * 用户消息编辑框测试（M5 编辑态交互）
 *
 * 覆盖（spec M5.1）：
 * - 逐字一致 / 空值 → 发送置灰（前后比对）；修改后解禁
 * - 取消恢复原文（编辑框卸载，回调上抛）
 * - 提交回调携带编辑后文本；Enter 提交 / Shift+Enter 换行不提交
 * - 生成中（streaming）发送置灰
 *
 * @author commerce-rag
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MessageEditBox } from "./message-edit-box";

/** 渲染编辑框并返回交互锚点 */
function renderBox(overrides: { initialValue?: string; streaming?: boolean } = {}) {
  const onSubmit = vi.fn();
  const onCancel = vi.fn();
  render(
    <MessageEditBox
      initialValue={overrides.initialValue ?? "原问题"}
      streaming={overrides.streaming ?? false}
      onSubmit={onSubmit}
      onCancel={onCancel}
    />,
  );
  return { onSubmit, onCancel };
}

describe("MessageEditBox 编辑态交互（M5）", () => {
  it("初始值 = 原问题：逐字一致时发送置灰；修改后解禁", () => {
    const { onSubmit } = renderBox({ initialValue: "原问题" });

    const submit = screen.getByTestId("message-edit-submit") as HTMLButtonElement;
    // 逐字一致（初始值未改）→ 置灰（spec「输入与原问题逐字一致 → 发送按钮置灰」）
    expect(submit.disabled).toBe(true);
    expect(screen.getByTestId("message-edit-input")).toHaveValue("原问题");

    fireEvent.change(screen.getByTestId("message-edit-input"), {
      target: { value: "改后的问题" },
    });
    expect(submit.disabled).toBe(false);

    fireEvent.click(submit);
    expect(onSubmit).toHaveBeenCalledWith("改后的问题");
  });

  it("空值 / 纯空白 → 发送置灰（禁止提交空问题）", () => {
    const { onSubmit } = renderBox();

    fireEvent.change(screen.getByTestId("message-edit-input"), { target: { value: "" } });
    expect((screen.getByTestId("message-edit-submit") as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(screen.getByTestId("message-edit-input"), { target: { value: "   " } });
    expect((screen.getByTestId("message-edit-submit") as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByTestId("message-edit-submit"));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("取消 → 恢复原文展示（onCancel 上抛，编辑框由父组件卸载）", () => {
    const { onCancel, onSubmit } = renderBox();

    fireEvent.change(screen.getByTestId("message-edit-input"), { target: { value: "改了一半" } });
    fireEvent.click(screen.getByTestId("message-edit-cancel"));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("Enter 提交编辑后文本；Shift+Enter 保留换行不提交", () => {
    const { onSubmit } = renderBox();

    const input = screen.getByTestId("message-edit-input");
    fireEvent.change(input, { target: { value: "改后的问题" } });
    // Enter（无 Shift）→ 提交
    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
    expect(onSubmit).toHaveBeenCalledWith("改后的问题");

    // Shift+Enter → 换行不提交
    fireEvent.change(input, { target: { value: "带换行的问题" } });
    fireEvent.keyDown(input, { key: "Enter", shiftKey: true });
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("生成中（streaming）发送置灰：编辑须等回答终态", () => {
    const { onSubmit } = renderBox({ streaming: true });

    fireEvent.change(screen.getByTestId("message-edit-input"), { target: { value: "改后的问题" } });
    const submit = screen.getByTestId("message-edit-submit") as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    // Enter 途径同样被置灰守卫拦截
    fireEvent.keyDown(screen.getByTestId("message-edit-input"), { key: "Enter" });
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
