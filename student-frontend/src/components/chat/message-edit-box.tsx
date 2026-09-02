"use client";

/**
 * 用户消息编辑框（M5）：点击编辑后原位替换用户气泡——输入框 + 发送/取消按钮。
 *
 * 交互契约（spec M5.1）：
 * - 初始值 = 原问题文本；输入与原问题逐字一致或空值 → 发送按钮置灰（前后比对）；
 * - 取消 → 恢复原文展示（由父组件切回气泡渲染）；
 * - Enter 提交 / Shift+Enter 换行；生成中（streaming）发送置灰（编辑须等回答终态）。
 *
 * @author commerce-rag
 */
import { useState } from "react";

/** 编辑框组件 props */
export interface MessageEditBoxProps {
  /** 原问题文本（编辑初始值；非空，由调用方从用户消息 content 传入） */
  initialValue: string;
  /** 会话是否生成中（生成中禁止提交——编辑须等回答终态，spec M5.1） */
  streaming: boolean;
  /** 提交回调（编辑后的新文本；按钮置灰保护下到达时必为非空且与原文不同） */
  onSubmit: (text: string) => void;
  /** 取消编辑（恢复原文展示，由父组件清 editingId） */
  onCancel: () => void;
}

/**
 * 用户消息编辑框（M5 编辑态行内输入）
 *
 * @param props 见 MessageEditBoxProps
 */
export function MessageEditBox({
  initialValue,
  streaming,
  onSubmit,
  onCancel,
}: MessageEditBoxProps) {
  const [value, setValue] = useState(initialValue);
  // 逐字一致或空值 → 禁止提交（spec M5.1「输入与原问题逐字一致 → 发送按钮置灰」）
  const unchanged = value === initialValue || value.trim().length === 0;
  return (
    <div
      data-testid="message-edit-box"
      className="flex max-w-[70%] flex-col gap-2 rounded-[18px] rounded-br-[8px] border border-brand/40 bg-bubble px-4 py-2.5"
    >
      <textarea
        autoFocus
        value={value}
        onChange={(event) => setValue(event.target.value)}
        aria-label="编辑问题"
        data-testid="message-edit-input"
        // 行数随内容行数自适应（1~5 行）
        rows={Math.min(5, Math.max(1, value.split("\n").length))}
        className="w-full resize-none bg-transparent text-[15px] leading-7 text-text outline-none"
        onKeyDown={(event) => {
          // Enter 提交（Shift+Enter 保留换行默认行为）；置灰态不提交
          if (event.key === "Enter" && !event.shiftKey && !unchanged && !streaming) {
            event.preventDefault();
            onSubmit(value);
          }
        }}
      />
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          data-testid="message-edit-cancel"
          className="rounded-lg px-3 py-1.5 text-sm text-muted transition-colors hover:bg-surface-2"
        >
          取消
        </button>
        <button
          type="button"
          disabled={unchanged || streaming}
          data-testid="message-edit-submit"
          title={streaming ? "回答生成中，结束后可编辑重发" : undefined}
          onClick={() => onSubmit(value)}
          className="rounded-lg bg-brand px-3 py-1.5 text-sm text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
        >
          发送
        </button>
      </div>
    </div>
  );
}
