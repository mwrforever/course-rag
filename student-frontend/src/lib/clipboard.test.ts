/**
 * 剪贴板复制工具测试（BUG-27 核心降级链：安全上下文 / API 失败 / 均不可用）
 *
 * 覆盖三级路径：
 * - clipboard API 可用且成功 → true（不走 execCommand）
 * - clipboard API 抛错或不可用（非安全上下文）→ execCommand 中转 textarea 降级
 * - 两条路径均失败（execCommand 返回 false / 抛异常）→ false，且中转节点不残留
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { copyToClipboard } from "./clipboard";

/** 当前用例的 clipboard 属性形态恢复值（jsdom 原生无 clipboard） */
const hadClipboard = "clipboard" in navigator;

/** 注入可控 navigator.clipboard（value=undefined 模拟非安全上下文不可用） */
function stubClipboard(writeText: ReturnType<typeof vi.fn> | undefined) {
  Object.defineProperty(navigator, "clipboard", {
    value: writeText === undefined ? undefined : { writeText },
    configurable: true,
  });
}

/** 注入可控 document.execCommand（jsdom 未实现；fn 缺省=属性不存在） */
function stubExecCommand(fn: (() => boolean) | undefined) {
  if (fn === undefined) {
    delete (document as unknown as Record<string, unknown>).execCommand;
  } else {
    Object.defineProperty(document, "execCommand", { value: fn, configurable: true });
  }
}

afterEach(() => {
  // 恢复 jsdom 原生形态（无 clipboard / 无 execCommand），避免跨用例污染
  if (hadClipboard) {
    delete (navigator as unknown as Record<string, unknown>).clipboard;
  }
  delete (document as unknown as Record<string, unknown>).execCommand;
});

describe("copyToClipboard 降级链", () => {
  it("clipboard API 可用且成功 → true 且不走 execCommand", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    const execCommand = vi.fn(() => true);
    stubClipboard(writeText);
    stubExecCommand(execCommand);
    await expect(copyToClipboard("文本")).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledWith("文本");
    expect(execCommand).not.toHaveBeenCalled();
  });

  it("clipboard API 抛错（权限拒绝/失焦）→ 回退 execCommand 成功 → true", async () => {
    const writeText = vi.fn().mockRejectedValue(new Error("NotAllowedError"));
    const execCommand = vi.fn(() => true);
    stubClipboard(writeText);
    stubExecCommand(execCommand);
    await expect(copyToClipboard("文本")).resolves.toBe(true);
    expect(execCommand).toHaveBeenCalledWith("copy");
  });

  it("clipboard 不可用（非安全上下文 undefined）→ execCommand 经 textarea 中转复制原文", async () => {
    stubClipboard(undefined);
    let textareaValue = "";
    const execCommand = vi.fn(() => {
      // 中转 textarea 已挂载且值为待复制文本（fixed 视口外定位）
      const textarea = document.querySelector("body > textarea") as HTMLTextAreaElement | null;
      textareaValue = textarea?.value ?? "";
      return true;
    });
    stubExecCommand(execCommand);
    await expect(copyToClipboard("降级文本")).resolves.toBe(true);
    expect(textareaValue).toBe("降级文本");
  });

  it("两条路径均失败（execCommand 返回 false）→ false", async () => {
    stubClipboard(undefined);
    stubExecCommand(() => false);
    await expect(copyToClipboard("文本")).resolves.toBe(false);
  });

  it("execCommand 不存在（极端环境）→ false 且中转 textarea 不残留 DOM", async () => {
    stubClipboard(undefined);
    stubExecCommand(undefined);
    await expect(copyToClipboard("文本")).resolves.toBe(false);
    expect(document.querySelectorAll("body > textarea")).toHaveLength(0);
  });
});
