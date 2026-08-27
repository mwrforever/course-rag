"use client";

/**
 * 课程助手流式状态共享 Context（(chat) 路由组内全局）
 *
 * 职责：把工作区（ChatWorkspace）的流式生成状态广播给布局级组件（如 ChatSidebar）：
 * - isStreaming：交互守卫——流式进行中禁止 Ctrl+K/新建对话跳转（导航会重挂载工作区、
 *   丢失进行中的流视图，服务端 run 继续但客户端无法回连，见 chat-workspace 实证注释）
 * - streamingSessionId：生成中会话定位（2026-08-27）——侧栏对应会话行渲染生成中
 *   动画（脉冲点 + 标题渐显），新会话 metadata 落位前为 null（行未入列表，无目标可标）
 *
 * 结构：(chat)/layout.tsx 挂 Provider → ChatWorkspace 经 useSetChatStreaming 上报
 * {streaming, sessionId} → ChatSidebar 经 useChatStreaming / useChatStreamingSessionId 读取。
 */
import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

/** Context 值：上报侧 = 工作区（setChatStreaming），消费侧 = 侧栏（两读取器） */
interface ChatStreamingContextValue {
  /** 任一工作区正在流式生成 */
  isStreaming: boolean;
  /** 生成中的会话 id（新会话未落位/未生成时为 null） */
  streamingSessionId: string | null;
  /** 工作区上报开关：streaming 置位 + 当前会话归属（缺省按 null 定位，streaming=false 时同步清空） */
  setChatStreaming: (streaming: boolean, sessionId?: string | null) => void;
}

/** 缺省值：非 Provider 树内（单测直挂侧栏等）按非流式处理，行为与重构前一致 */
const ChatStreamingContext = createContext<ChatStreamingContextValue>({
  isStreaming: false,
  streamingSessionId: null,
  setChatStreaming: () => {},
});

/**
 * 流式状态 Provider（(chat) 布局壳挂载）
 *
 * @param children 布局子树（侧栏 + 工作区路由出口）
 */
export function ChatStreamingProvider({ children }: { children: ReactNode }) {
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamingSessionId, setStreamingSessionId] = useState<string | null>(null);
  // setter 引用稳定（useState setter 天然稳定），避免消费方 effect 反复重订阅
  const value = useMemo<ChatStreamingContextValue>(
    () => ({
      isStreaming,
      streamingSessionId,
      setChatStreaming: (streaming, sessionId) => {
        setIsStreaming(streaming);
        // 停止生成即清定位（终态会话不再挂生成中动画）；会话切换由工作区重挂载时上报
        setStreamingSessionId(streaming ? (sessionId ?? null) : null);
      },
    }),
    [isStreaming, streamingSessionId],
  );
  return <ChatStreamingContext.Provider value={value}>{children}</ChatStreamingContext.Provider>;
}

/** 读取流式状态（守卫方：ChatSidebar 快捷键） */
export function useChatStreaming(): boolean {
  return useContext(ChatStreamingContext).isStreaming;
}

/** 读取生成中会话 id（展示方：侧栏会话行生成中动画，2026-08-27） */
export function useChatStreamingSessionId(): string | null {
  return useContext(ChatStreamingContext).streamingSessionId;
}

/**
 * 上报流式状态（生产方：ChatWorkspace 同步 hook 状态；卸载时上报 false）
 *
 * 兼容保留：旧签名 setStreaming(boolean) 等价于 setChatStreaming(streaming, null)
 * ——新会话 metadata 落位前无会话 id 可定位，侧栏仅守卫不标行。
 */
export function useSetChatStreaming(): (streaming: boolean, sessionId?: string | null) => void {
  return useContext(ChatStreamingContext).setChatStreaming;
}
