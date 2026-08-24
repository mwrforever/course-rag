"use client";

/**
 * 新对话页 /chat（设计 §1.5.4，全 CSR）
 *
 * sessionId=null 发起；metadata 事件到达后由 ChatWorkspace 内部
 * router.replace('/chat/{sessionId}) 落 URL（仅一次）。
 * Suspense 包裹：useSearchParams 需要边界（Next 15 CSR 预渲染约束），
 * fallback 为消息流同形骨架（设计 §1.7 Loading）。
 */
import { Suspense } from "react";
import { ChatSkeleton, ChatWorkspace } from "./chat-workspace";

/**
 * 新对话页（问候空态 + 输入全链路）
 */
export default function NewChatPage() {
  return (
    <Suspense fallback={<ChatSkeleton />}>
      <ChatWorkspace initialSessionId={null} variant="new" />
    </Suspense>
  );
}
