"use client";

/**
 * 用户消息编辑框（M5）：点击编辑后原位替换用户气泡——全宽输入卡 + 取消/发送按钮。
 *
 * 交互契约（spec M5.1；2026-09-03 宽度拍板修订）：
 * - 卡片与消息内容展示区同宽（w-full，对齐底部 ChatInput 卡片形态——非旧版 70% 窄气泡），
 *   textarea 多行自适应（1~5 行）、按钮组贴卡片右下角（取消 ghost / 发送实心）；
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
 * 用户消息编辑框（M5 编辑态行内输入；2026-09-03 全宽卡片形态）
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
    // 全宽输入卡：与内容展示区/底部输入卡同宽同形（rounded-[20px] surface 卡 + focus 边框）
    <div
      data-testid="message-edit-box"
      className="w-full rounded-[20px] border border-border bg-surface shadow-xs transition-[border-color,box-shadow] focus-within:border-brand/50 focus-within:shadow-md"
    >
      <textarea
        autoFocus
        value={value}
        onChange={(event) => setValue(event.target.value)}
        aria-label="编辑问题"
        data-testid="message-edit-input"
        // 行数随内容行数自适应（1~5 行）
        rows={Math.min(5, Math.max(1, value.split("\n").length))}
        className="w-full resize-none bg-transparent px-4 pt-3.5 text-[15px] leading-7 text-text outline-none placeholder:text-subtle"
        onKeyDown={(event) => {
          // Enter 提交（Shift+Enter 保留换行默认行为）；置灰态不提交
          if (event.key === "Enter" && !event.shiftKey && !unchanged && !streaming) {
            event.preventDefault();
            onSubmit(value);
          }
        }}
      />
      {/* 按钮组贴卡片右下角（图 3 参考设计：取消 ghost / 发送实心） */}
      <div className="flex items-center justify-end gap-2 px-3 pb-3">
        <button
          type="button"
          onClick={onCancel}
          data-testid="message-edit-cancel"
          className="rounded-lg border border-border px-3.5 py-1.5 text-sm text-muted transition-colors hover:bg-surface-2 hover:text-text"
        >
          取消
        </button>
        <button
          type="button"
          disabled={unchanged || streaming}
          data-testid="message-edit-submit"
          title={streaming ? "回答生成中，结束后可编辑重发" : undefined}
          onClick={() => onSubmit(value)}
          className="rounded-lg bg-brand px-3.5 py-1.5 text-sm text-white transition-colors hover:bg-brand-strong disabled:cursor-not-allowed disabled:opacity-50"
        >
          发送
        </button>
      </div>
    </div>
  );
}
