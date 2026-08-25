"use client";

/**
 * 对话输入区（设计 §1.5.4 输入区 + §3.2 错误分级 + §1.6 发送/停止 morph）
 *
 * 交互契约：
 * - Enter 发送 / Shift+Enter 换行 / IME 组合态（isComposing）Enter 不发送
 * - 空输入（trim 后）禁用发送；附件上传中（sendDisabled）同样禁用
 * - 生成中发送键 morph 为停止键（PaperPlaneRight ↔ Square 交叉淡入 + 尺寸弹性，
 *   motion spring 180ms；prefers-reduced-motion 静态切换）
 * - streaming 中 Enter 仍可发送（触发后端 409 并发冲突路径，由错误分级 toast 提示）
 * - 发送失败（上抛）：输入内容恢复，供用户修改重试
 * - textarea 自动增高 ≤6 行（按换行数）
 */
import { PaperPlaneRight, Square } from "@phosphor-icons/react";
import { motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { ApiError, NetworkError } from "@/lib/api";

/** 输入区组件 props */
export interface ChatInputProps {
  /** 是否生成中（决定发送/停止 morph 与 Enter 语义） */
  streaming: boolean;
  /** 附件上传中：发送禁用（避免带半上传附件提交） */
  sendDisabled?: boolean;
  /** 发送回调（上抛异常由本组件分级 toast）；发送成功后输入框清空 */
  onSend(query: string): Promise<void>;
  /** 停止生成回调（POST cancel，终态后 409 由 hook 静默） */
  onCancel(): void;
  /** 提示回调（409/503/网络/复制等 toast 文案，页面统一呈现） */
  onNotify(message: string): void;
}

/** 发送/停止图标切换的弹簧参数（设计 §1.6：180ms spring） */
const MORPH_TRANSITION = { type: "spring", stiffness: 500, damping: 35, duration: 0.18 } as const;

/**
 * 发送异常分级文案（设计 §3.2 错误处理分级；页面建议提问 chip 与输入区共用）
 *
 * @param error send 上抛的异常（ApiError/NetworkError/其它）
 * @returns 中文 toast 文案
 */
export function chatErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    // 409 会话并发冲突（同一会话正在生成）；503 服务暂不可用
    if (error.code === 409) return "当前会话正在回答中";
    if (error.code === 503) return "服务暂时不可用，请稍后重试";
  }
  if (error instanceof NetworkError) {
    return "网络连接失败，请检查网络";
  }
  return "发送失败，请稍后重试";
}

/**
 * 对话输入区组件
 *
 * 组件自持输入文本与 morph 动效；发送成功/失败均向上传播，失败时恢复输入内容。
 * 不受 redux/表单库约束，纯本地 state（单消费者，无并发问题）。
 */
export function ChatInput({
  streaming,
  sendDisabled = false,
  onSend,
  onCancel,
  onNotify,
}: ChatInputProps) {
  const [value, setValue] = useState("");
  // reduced-motion 命中或检测不可用：morph 不挂动画（可访问性优先）
  const reduceMotion = useReducedMotion() ?? true;
  const canSend = value.trim().length > 0 && !sendDisabled;

  /** Enter/Shift+Enter/IME 组合态键盘分发（换行走原生 textarea 行为） */
  function handleKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter") return;
    // 中文输入法候选确认 Enter（isComposing）不触发发送
    // 注：React 合成事件未透出 isComposing（实测 undefined），须读 nativeEvent
    if (event.nativeEvent.isComposing) return;
    if (event.shiftKey) return;
    event.preventDefault();
    void submit();
  }

  /** 提交发送：清空输入 → 调 onSend；失败分级 toast 并恢复输入内容 */
  async function submit() {
    if (!canSend) return;
    const query = value.trim();
    setValue("");
    try {
      await onSend(query);
    } catch (error) {
      // 分级 toast（409/503/网络/兜底）；恢复输入内容供修改重试
      onNotify(chatErrorText(error));
      setValue(query);
    }
  }

  /** 自动增高（≤6 行）：按换行数推导 rows，封顶 6（设计 §1.5.4 textarea 自动增高） */
  const rows = Math.min(6, Math.max(1, value.split("\n").length));

  return (
    // kimi 输入区形态：20px 大圆角编辑器，聚焦边框加深 + 柔和投影（UI 重构 2026-08-25）
    <div className="flex w-full items-end gap-2 rounded-[20px] border border-border bg-surface p-2 pl-4 shadow-xs transition-[border-color,box-shadow] focus-within:border-brand/50 focus-within:shadow-md">
      <textarea
        value={value}
        rows={rows}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
        aria-label="问题输入框"
        className="max-h-40 min-h-10 flex-1 resize-none bg-transparent px-1 py-2 text-[15px] leading-6 text-text outline-none placeholder:text-subtle"
      />
      <button
        type="button"
        aria-label={streaming ? "停止生成" : "发送"}
        disabled={streaming ? false : !canSend}
        onClick={() => (streaming ? onCancel() : void submit())}
        className={`grid size-10 shrink-0 place-items-center rounded-full text-white transition-all focus-visible:ring-2 focus-visible:ring-brand active:scale-95 ${
          streaming
            ? "bg-text hover:opacity-90"
            : canSend
              ? "bg-brand hover:bg-brand-strong"
              : "cursor-not-allowed bg-surface-2 text-faint"
        }`}
      >
        <motion.span
          key={streaming ? "stop" : "send"}
          initial={reduceMotion ? false : { opacity: 0, scale: 0.7 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={reduceMotion ? undefined : MORPH_TRANSITION}
          className="grid place-items-center"
        >
          {/* 发送/停止图标交叉淡入（关键帧由 motion key 切换触发） */}
          {streaming ? (
            <Square size={15} weight="fill" aria-hidden />
          ) : (
            <PaperPlaneRight size={16} weight="fill" aria-hidden />
          )}
        </motion.span>
      </button>
    </div>
  );
}
