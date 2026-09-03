"use client";

/**
 * 课程助手流式状态共享 Context（(chat) 路由组内全局）
 *
 * 职责：把各工作区（ChatWorkspace）的流式生成会话标记广播给布局级组件（如 ChatSidebar）：
 * - streamingSessionIds：生成中会话 id 集合（2026-09-03 多会话并发修订：单值 → 集合）——
 *   侧栏对应会话行渲染生成中动画（脉冲点 + 标题渐显）。多会话并发下可有多个会话同时在
 *   服务端执行 run，侧栏逐会话渲染标记
 * - 标记生命周期（2026-09-03 用户拍板：切走/新建不再丢标记）：
 *   <ul>
 *     <li>置位：工作区 streaming 且会话归属已落位（send 后 / resume 续流中）</li>
 *     <li>清除：本会话 run 到达本地终态（endedStatus）——完成/停止/失败即清；
 *         断流 error 不清（run 仍在服务端执行，标记由侧栏轮询兜底校正）</li>
 *     <li>保留：切到其它会话 / 流式中新建对话（工作区 detach+reset）——旧会话 run
 *         在服务端继续执行（断连不取消），标记保留至侧栏轮询核实 run 结束或重访校正</li>
 *   </ul>
 * - 侧栏自愈：ChatSidebar 对标记集合周期性（30s）核对 GET active-run，run 已结束即清
 *   标记（用户不再切回的会话标记不会滞留）；重访会话页 active-run 查询落定无活跃 run
 *   亦即时清除
 * - newChatSeq/requestNewChat（2026-08-29 Task 13 新建对话干净态）：侧栏新建按钮在
 *   /chat 同路由时不能靠导航重挂载工作区（会丢滚动位置与进行中渲染态），经信号计数器
 *   驱动工作区 reset（清消息/附件/输入 + 会话归属）；工作区消费 newChatSeq 自增执行
 *
 * 结构：(chat)/layout.tsx 挂 Provider → ChatWorkspace 经 useMarkChatStreaming/
 * useUnmarkChatStreaming 上报 → ChatSidebar 经 useChatStreamingSessionIds 读取 +
 * useUnmarkChatStreaming 自愈清理；新建对话：ChatSidebar 经 useRequestNewChat 发出 →
 * ChatWorkspace 经 useChatNewChatSeq 消费。
 */
import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

/** Context 值：上报侧 = 工作区（标记/清除），消费侧 = 侧栏（集合读取 + 自愈清理） */
interface ChatStreamingContextValue {
  /** 生成中的会话 id 集合（多会话并发下可多个；无则空集合） */
  streamingSessionIds: ReadonlySet<string>;
  /** 标记会话生成中（工作区 streaming 且会话归属落位时调用；重复标记幂等） */
  markChatStreaming: (sessionId: string) => void;
  /** 清除会话生成中标记（本地终态 / 侧栏轮询核实 run 已结束 / 重访校正时调用） */
  unmarkChatStreaming: (sessionId: string) => void;
  /** 新建对话信号计数（每次 requestNewChat 自增；工作区比对消费执行干净态 reset） */
  newChatSeq: number;
  /** 发出新建对话信号（侧栏新建按钮；/chat 同路由不重挂载经此驱动） */
  requestNewChat: () => void;
}

/** 缺省值：非 Provider 树内（单测直挂侧栏等）按无生成中会话处理，行为与重构前一致 */
const ChatStreamingContext = createContext<ChatStreamingContextValue>({
  streamingSessionIds: new Set<string>(),
  markChatStreaming: () => {},
  unmarkChatStreaming: () => {},
  newChatSeq: 0,
  requestNewChat: () => {},
});

/**
 * 流式状态 Provider（(chat) 布局壳挂载）
 *
 * @param children 布局子树（侧栏 + 工作区路由出口）
 */
export function ChatStreamingProvider({ children }: { children: ReactNode }) {
  const [streamingSessionIds, setStreamingSessionIds] = useState<ReadonlySet<string>>(
    () => new Set<string>(),
  );
  // 新建对话信号计数（自增即信号；工作区比对前后值判定新信号到达）
  const [newChatSeq, setNewChatSeq] = useState(0);
  // setter 引用稳定（useState setter 天然稳定），避免消费方 effect 反复重订阅
  const value = useMemo<ChatStreamingContextValue>(
    () => ({
      streamingSessionIds,
      markChatStreaming: (sessionId: string) => {
        // 幂等：已在集合中原状态返回（保持引用稳定，消费方 memo 不被击穿）
        setStreamingSessionIds((prev) =>
          prev.has(sessionId) ? prev : new Set(prev).add(sessionId),
        );
      },
      unmarkChatStreaming: (sessionId: string) => {
        // 幂等：不在集合中原状态返回
        setStreamingSessionIds((prev) => {
          if (!prev.has(sessionId)) return prev;
          const next = new Set(prev);
          next.delete(sessionId);
          return next;
        });
      },
      newChatSeq,
      requestNewChat: () => setNewChatSeq((seq) => seq + 1),
    }),
    [streamingSessionIds, newChatSeq],
  );
  return <ChatStreamingContext.Provider value={value}>{children}</ChatStreamingContext.Provider>;
}

/** 读取生成中会话 id 集合（展示方：侧栏会话行生成中动画，2026-09-03 集合化） */
export function useChatStreamingSessionIds(): ReadonlySet<string> {
  return useContext(ChatStreamingContext).streamingSessionIds;
}

/** 标记会话生成中（生产方：ChatWorkspace streaming 且会话归属落位时调用） */
export function useMarkChatStreaming(): (sessionId: string) => void {
  return useContext(ChatStreamingContext).markChatStreaming;
}

/** 清除会话生成中标记（生产方：工作区本地终态；消费方：侧栏轮询自愈/重访校正） */
export function useUnmarkChatStreaming(): (sessionId: string) => void {
  return useContext(ChatStreamingContext).unmarkChatStreaming;
}

/** 读取新建对话信号计数（消费方：ChatWorkspace 比对自增执行干净态 reset） */
export function useChatNewChatSeq(): number {
  return useContext(ChatStreamingContext).newChatSeq;
}

/** 发出新建对话信号（生产方：ChatSidebar 新建按钮 /chat 同路由路径） */
export function useRequestNewChat(): () => void {
  return useContext(ChatStreamingContext).requestNewChat;
}
