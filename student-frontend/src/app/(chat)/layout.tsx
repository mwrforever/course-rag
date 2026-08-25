"use client";

import { ChatSidebar } from "@/components/chat/chat-sidebar";
import { QueryProvider } from "@/lib/query-provider";

/**
 * 课程助手对话路由组壳（UI 重构 2026-08-25：kimi 式全局左侧栏）
 *
 * 仅包裹 /chat 与 /chat/[sessionId]：左侧栏（品牌/新建对话/会话历史/用户区）+ 右侧满高工作区，
 * 与 (main) 路由组（顶部导航首页/课堂/会话/个人中心）互斥——kimi 式布局只用于课程助手（用户拍板）。
 * QueryProvider 随 (main) 组剥离开后在此补挂，保证对话页服务端状态缓存可用。
 */
export default function ChatLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <QueryProvider>
      <div className="flex h-dvh overflow-hidden">
        <ChatSidebar />
        <div className="flex min-w-0 flex-1 flex-col">{children}</div>
      </div>
    </QueryProvider>
  );
}
