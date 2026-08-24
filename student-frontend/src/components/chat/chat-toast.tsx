"use client";

/**
 * 页面级轻量 toast（对话页 409/网络/复制等提示，设计 §3.2）
 *
 * 无新依赖（与 auth-context 登录失效 toast 同思路）：fixed 底部居中，
 * role=status 供读屏即时播报；200ms 淡入（transform/opacity，reduced-motion 静态）。
 * 自动消失由页面持有定时器控制。
 */
export interface ChatToastProps {
  /** 文案；null 不渲染 */
  message: string | null;
}

/**
 * 对话页 toast 呈现组件
 *
 * @param message 提示文案（null=隐藏）
 */
export function ChatToast({ message }: ChatToastProps) {
  if (!message) {
    return null;
  }
  return (
    <div
      role="status"
      data-testid="chat-toast"
      className="pointer-events-none fixed bottom-28 left-1/2 z-50 -translate-x-1/2 animate-fade-in rounded-xl bg-text px-4 py-2.5 text-sm text-surface shadow-lg motion-reduce:animate-none"
    >
      {message}
    </div>
  );
}
