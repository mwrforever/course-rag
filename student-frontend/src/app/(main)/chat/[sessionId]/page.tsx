"use client";

/**
 * 历史会话页 /chat/[sessionId]（设计 §1.5.4 + §六.6 R1 历史回显，全 CSR）
 *
 * 职责：
 * - R1 拉取历史消息 getSessionMessages(sessionId, 1, 200)（升序最旧一页，分页默认 size=200）
 * - 经 historyAdapter 转为对话流视图模型（行还原思考卡/工具卡/来源卡/附件 chips，G8）
 *   → ChatWorkspace 回显；历史加载中/失败态由工作区承接（骨架 / 横幅+重试）
 * - 续会话「继续提问」占位全链路：useChatStream 以 URL 会话 id 初始化（不 replace URL）
 */
import { useQuery } from "@tanstack/react-query";
import { Suspense, useMemo } from "react";
import { useParams } from "next/navigation";
import { ChatSkeleton, ChatWorkspace } from "../chat-workspace";
import { historyAdapter } from "@/lib/history-adapter";
import { getSessionMessages } from "@/lib/api";

/**
 * 历史会话页（历史回显 + 流式续答）
 */
export default function SessionChatPage() {
  const params = useParams<{ sessionId: string }>();
  // R1 历史消息：固定取升序最旧一页 200 条（分页默认 size=200，设计 §六.6）
  const historyQuery = useQuery({
    queryKey: ["session-messages", params.sessionId],
    queryFn: () => getSessionMessages(params.sessionId, 1, 200),
    retry: false,
  });
  // 行还原：StudentMessage[] → StreamMessage[]（USER/ASSISTANT 正文→消息；
  // thinking→思考卡；TOOL_CALL/TOOL_RESULT→工具卡；sources→来源卡；attachments→chips）
  const historyMessages = useMemo(
    () => historyAdapter(historyQuery.data?.records ?? []),
    [historyQuery.data],
  );

  return (
    <Suspense fallback={<ChatSkeleton />}>
      {/* key 随会话 id 变化：切换会话时工作区状态（消息/附件/滚动）整体重置 */}
      <ChatWorkspace
        key={params.sessionId}
        initialSessionId={params.sessionId}
        variant="continue"
        history={{
          status: historyQuery.isPending ? "pending" : historyQuery.isError ? "error" : "success",
          messages: historyMessages,
          retry: () => void historyQuery.refetch(),
        }}
      />
    </Suspense>
  );
}
