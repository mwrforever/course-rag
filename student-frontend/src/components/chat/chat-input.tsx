"use client";

/**
 * 对话输入区（设计 §1.5.4 输入区 + §3.2 错误分级 + §1.6 发送/停止 morph；
 * 2026-08-27 改版：粘贴附件 + 内嵌上传按钮；2026-08-29 扩容：附件区卡内顶部
 * 展开 + border-t 细线分隔（设计稿图一形态）+ 受控 value/resetKey）
 *
 * 交互契约：
 * - Enter 发送 / Shift+Enter 换行 / IME 组合态（isComposing）Enter 不发送
 * - 直接粘贴文件（Ctrl+V）：clipboardData.files 非空时接管事件转发上传回调
 *   （纯文本粘贴不受影响）；图片与文档走同一入口（用户拍板：一个按钮一个接口）
 * - attachmentSlot：上传按钮等内容经插槽渲染进输入行左侧（图2 胶囊输入框形态）
 * - attachmentsArea：附件 chips 区渲染进卡内顶部（图一扩容形态）；提供时输入行
 *   挂 border-t 细线与输入区分隔，无附件时收起不占位（Task 12）
 * - 受控模式（value/onValueChange 提供）：外部状态驱动输入框，键入/清空/恢复
 *   均经 onValueChange 上抛；未提供时组件自持内部状态（向后兼容）
 * - resetKey：变化时清空输入（受控=通知父级、非受控=内部清空；Task 13 新建
 *   会话干净态消费，不依赖路由重挂载）
 * - 空输入（trim 后）禁用发送；附件上传中（sendDisabled）同样禁用
 * - 生成中发送键 morph 为停止键（PaperPlaneRight ↔ Square 交叉淡入 + 尺寸弹性，
 *   motion spring 180ms；prefers-reduced-motion 静态切换）
 * - streaming 中 Enter 仍可发送（触发后端 409 并发冲突路径，由错误分级 toast 提示）
 * - 发送失败（上抛）：输入内容恢复，供用户修改重试
 * - textarea 自动增高 ≤6 行（按换行数）
 */
import { PaperPlaneRight, Square } from "@phosphor-icons/react";
import { motion, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState, type ReactNode } from "react";
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
  /** 输入初值（/chat?q= 快速提问预填；仅非受控模式首次挂载生效） */
  initialValue?: string;
  /** 粘贴文件回调（Ctrl+V 粘贴图片/文档时转发；纯文本粘贴不触发） */
  onPasteFiles?: (files: File[]) => void;
  /** 输入行左侧插槽（上传按钮等内容；渲染于 textarea 之前的输入行内） */
  attachmentSlot?: ReactNode;
  /** 附件区插槽（chips 等；提供时渲染于卡内顶部并以 border-t 细线与输入行分隔） */
  attachmentsArea?: ReactNode;
  /** 受控输入值（提供即进入受控模式；Task 13 新建会话 reset 消费） */
  value?: string;
  /** 受控变更回调（受控模式必提供；键入/清空/失败恢复均经此上抛） */
  onValueChange?(value: string): void;
  /** 重置键：变化时清空输入（不依赖路由重挂载的干净态入口） */
  resetKey?: number;
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
 * 组件自持输入文本与 morph 动效（受控模式由外部 value 驱动）；发送成功/失败均
 * 向上传播，失败时恢复输入内容。不受 redux/表单库约束（单消费者，无并发问题）。
 */
export function ChatInput({
  streaming,
  sendDisabled = false,
  onSend,
  onCancel,
  onNotify,
  initialValue,
  onPasteFiles,
  attachmentSlot,
  attachmentsArea,
  value,
  onValueChange,
  resetKey,
}: ChatInputProps) {
  // 受控判定：value 显式提供（含空串）即受控模式
  const isControlled = value !== undefined;
  const [internalValue, setInternalValue] = useState(initialValue ?? "");
  /** 当前输入值（受控=外部 prop，非受控=内部状态） */
  const current = isControlled ? (value as string) : internalValue;
  /** 写入输入值：内部镜像 + 受控上抛（受控模式下父级回写驱动视图） */
  const writeValue = (next: string) => {
    setInternalValue(next);
    onValueChange?.(next);
  };

  // resetKey 变化 → 清空输入（首帧跳过；受控模式经 onValueChange 通知父级回写空串）
  const lastResetKey = useRef(resetKey);
  useEffect(() => {
    if (lastResetKey.current === resetKey) return;
    lastResetKey.current = resetKey;
    setInternalValue("");
    onValueChange?.("");
  }, [resetKey, onValueChange]);

  // 挂载门控（hydration 修复 2026-08-26）：useReducedMotion 服务端 null/客户端首帧 false，
  // 挂载即动画的 initial 首帧不一致会触发 hydration mismatch；首帧渲染最终态保持一致，
  // 交互切换（key 变化重挂载）时动画照常生效
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    setMounted(true);
  }, []);
  // reduced-motion 命中或检测不可用：morph 不挂动画（可访问性优先）
  const reduceMotion = useReducedMotion() ?? true;
  const canSend = current.trim().length > 0 && !sendDisabled;

  /**
   * 粘贴分发：clipboardData 携带文件（截图/复制的图片、拖拷的文档）时接管事件
   * 转发上传回调；纯文本粘贴原样放行（不 preventDefault）
   */
  function handlePaste(event: React.ClipboardEvent<HTMLTextAreaElement>) {
    if (!onPasteFiles) return;
    const files = Array.from(event.clipboardData?.files ?? []);
    if (files.length === 0) return;
    event.preventDefault();
    onPasteFiles(files);
  }

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
    const query = current.trim();
    writeValue("");
    try {
      await onSend(query);
    } catch (error) {
      // 分级 toast（409/503/网络/兜底）；恢复输入内容供修改重试
      onNotify(chatErrorText(error));
      writeValue(query);
    }
  }

  /** 自动增高（≤6 行）：按换行数推导 rows，封顶 6（设计 §1.5.4 textarea 自动增高） */
  const rows = Math.min(6, Math.max(1, current.split("\n").length));
  // 附件区存在性（undefined/null 均视为无附件，不渲染占位与分隔线）
  const hasAttachmentsArea = attachmentsArea !== undefined && attachmentsArea !== null;

  return (
    // 图一扩容形态：附件区在卡内顶部展开，border-t 细线与输入行分隔；无附件时收起
    <div
      data-testid="chat-input-card"
      className="w-full rounded-[20px] border border-border bg-surface shadow-xs transition-[border-color,box-shadow] focus-within:border-brand/50 focus-within:shadow-md"
    >
      {/* 附件区（卡内顶部）：chips 经插槽渲染（上传中进度/缩略图/格式图标/移除） */}
      {hasAttachmentsArea ? (
        <div data-testid="attachment-area" className="flex flex-wrap gap-2 px-3 pt-3">
          {attachmentsArea}
        </div>
      ) : null}
      {/* 输入行：上传按钮 + textarea + 发送/停止（附件区存在时挂 border-t 细线分隔） */}
      <div
        data-testid="chat-input-row"
        className={`flex w-full items-end gap-2 p-2 pl-3 ${hasAttachmentsArea ? "border-t border-border/70" : ""}`}
      >
        {attachmentSlot}
        <textarea
          value={current}
          rows={rows}
          onChange={(event) => writeValue(event.target.value)}
          onKeyDown={handleKeyDown}
          onPaste={handlePaste}
          placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
          aria-label="问题输入框"
          data-testid="chat-textarea"
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
            initial={!mounted || reduceMotion ? false : { opacity: 0, scale: 0.7 }}
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
    </div>
  );
}
