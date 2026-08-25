"use client";

/**
 * 课程助手流式状态共享 Context（(chat) 路由组内全局）
 *
 * 职责：把工作区（ChatWorkspace）的流式生成状态广播给布局级组件（如 ChatSidebar），
 * 供其做交互守卫——流式进行中禁止 Ctrl+K/新建对话跳转（导航会重挂载工作区、
 * 丢失进行中的流视图，服务端 run 继续但客户端无法回连，见 chat-workspace 实证注释）。
 *
 * 结构：(chat)/layout.tsx 挂 Provider → ChatWorkspace 经 useSetChatStreaming 上报
 * state.streaming → ChatSidebar 经 useChatStreaming 读取守卫。
 */
import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

/** Context 值：isStreaming=任一工作区正在流式生成；setStreaming=工作区上报开关 */
interface ChatStreamingContextValue {
  isStreaming: boolean;
  setStreaming: (streaming: boolean) => void;
}

/** 缺省值：非 Provider 树内（单测直挂侧栏等）按非流式处理，行为与重构前一致 */
const ChatStreamingContext = createContext<ChatStreamingContextValue>({
  isStreaming: false,
  setStreaming: () => {},
});

/**
 * 流式状态 Provider（(chat) 布局壳挂载）
 *
 * @param children 布局子树（侧栏 + 工作区路由出口）
 */
export function ChatStreamingProvider({ children }: { children: ReactNode }) {
  const [isStreaming, setIsStreaming] = useState(false);
  // setStreaming 引用稳定（useState setter 天然稳定），避免消费方 effect 反复重订阅
  const value = useMemo<ChatStreamingContextValue>(
    () => ({ isStreaming, setStreaming: setIsStreaming }),
    [isStreaming],
  );
  return <ChatStreamingContext.Provider value={value}>{children}</ChatStreamingContext.Provider>;
}

/** 读取流式状态（守卫方：ChatSidebar 快捷键） */
export function useChatStreaming(): boolean {
  return useContext(ChatStreamingContext).isStreaming;
}

/** 上报流式状态（生产方：ChatWorkspace 同步 hook 状态；卸载时上报 false） */
export function useSetChatStreaming(): (streaming: boolean) => void {
  return useContext(ChatStreamingContext).setStreaming;
}
