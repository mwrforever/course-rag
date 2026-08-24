"use client";

/**
 * 历史会话页 /chat/[sessionId]（设计 §1.5.4，全 CSR）
 *
 * 本期职责：续会话「继续提问」占位全链路（useChatStream 以 URL 会话 id 初始化，
 * 发送走既有会话；不 replace URL）。Task 13 接入历史消息回显
 * （R1 /student/sessions/{id}/messages）。
 */
import { Suspense } from "react";
import { useParams } from "next/navigation";
import { ChatSkeleton, ChatWorkspace } from "../chat-workspace";

/**
 * 历史会话页（继续提问占位 + 流式续答）
 */
export default function SessionChatPage() {
  const params = useParams<{ sessionId: string }>();
  return (
    <Suspense fallback={<ChatSkeleton />}>
      {/* key 随会话 id 变化：切换会话时工作区状态（消息/附件/滚动）整体重置 */}
      <ChatWorkspace
        key={params.sessionId}
        initialSessionId={params.sessionId}
        variant="continue"
      />
    </Suspense>
  );
}
