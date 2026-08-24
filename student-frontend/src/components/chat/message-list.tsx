"use client";

/**
 * 对话消息流（设计 §1.5.4 消息流 + §1.6 动效）
 *
 * - 容器 max-w-[880px] 居中，消息间距 space-y-8（页面内滚动区）
 * - 用户消息：右对齐 teal-50（brand-light）气泡，rounded-2xl rounded-br-md
 *   （形状锁唯一例外）；附件缩略 chips（图片 blob 缩略 / 文档图标），纯文本防 XSS
 * - AI 消息：无气泡整栏（阅读友好），AI 徽标头像 + 思考卡/来源卡/正文/工具卡/操作栏
 * - 流式打字光标（1s 循环，仅最后一条 AI 消息且 streaming 时挂载）
 * - 「已停止生成」后缀：hook 已在 text 追加，本组件剥离后按 endedStatus 渲染唯一一份
 * - end 后操作栏 200ms fade-in（transform/opacity，reduced-motion 静态）
 */
import { FileText } from "@phosphor-icons/react";
import { AiBadge } from "@/components/ai-badge";
import { FeedbackBar } from "./feedback-bar";
import { MarkdownView } from "./markdown-view";
import { SourcesList } from "./sources-list";
import { ThinkingCard } from "./thinking-card";
import { ToolCallCard } from "./tool-call-card";
import type { StreamMessage } from "@/hooks/use-chat-stream";
import type { AttachmentRecord } from "@/lib/types";

/** 消息流组件 props */
export interface MessageListProps {
  /** 全部消息（用户 + AI，按时间序） */
  messages: StreamMessage[];
  /** 是否正在生成（打字光标挂载依据） */
  streaming: boolean;
  /** 当前会话 id（操作栏反馈请求体；新会话 metadata 到达后非空） */
  sessionId: string;
  /** 附件记录 url(objectKey) → 本地 blob URL 映射（D12：objectKey 不可直接访问） */
  attachmentBlobUrls: Record<string, string>;
  /** 提示回调（复制/反馈 toast，页面统一呈现） */
  onNotify(message: string): void;
}

/** 智能吸底滚动判定阈值（仅距底 80px 内才跟随，用户上翻阅读时不强制拉动） */
const STICK_THRESHOLD_PX = 80;

/**
 * 智能吸底滚动判定：滚动区距底部 ≤80px 时跟随；否则（用户上翻阅读中）不打扰
 *
 * @param scrollTop 已滚距离
 * @param scrollHeight 内容总高
 * @param clientHeight 可视高
 * @param threshold 吸底判定阈值（默认 80px）
 * @returns 是否应滚动到底部
 */
export function shouldStickToBottom(
  scrollTop: number,
  scrollHeight: number,
  clientHeight: number,
  threshold = STICK_THRESHOLD_PX,
): boolean {
  return scrollHeight - scrollTop - clientHeight <= threshold;
}

/** 用户消息附件 chips（图片 blob 缩略 / 文档图标 + 文件名） */
function UserAttachmentChips({
  attachments,
  blobUrls,
}: {
  attachments: AttachmentRecord[];
  blobUrls: Record<string, string>;
}) {
  return (
    <div className="mb-2 flex flex-wrap gap-2">
      {attachments.map((attachment) => {
        const blob = blobUrls[attachment.url];
        return (
          <span
            key={attachment.url}
            className="flex items-center gap-1.5 rounded-lg bg-white/70 px-2 py-1 text-xs text-muted"
          >
            {attachment.type === "image" && blob ? (
              // 实时会话：本地 blob 缩略预览（D12；历史回显无 blob 时降级图标）
              // eslint-disable-next-line @next/next/no-img-element -- blob: URL 无法走 next/image 优化器，本地预览用原生 img
              <img
                src={blob}
                alt={`附件：${attachment.name}`}
                className="size-7 rounded-md border border-border object-cover"
              />
            ) : (
              <FileText size={13} aria-hidden />
            )}
            <span className="max-w-28 truncate">{attachment.name}</span>
          </span>
        );
      })}
    </div>
  );
}

/**
 * 对话消息流（用户气泡 + AI 整栏复合块）
 *
 * @param props 见 MessageListProps
 */
export function MessageList({
  messages,
  streaming,
  sessionId,
  attachmentBlobUrls,
  onNotify,
}: MessageListProps) {
  // 打字光标归属：最后一条为 AI 消息且流式进行中
  const last = messages.at(-1);
  const showCursor = streaming && last?.role === "assistant";

  return (
    <div data-testid="message-flow" className="mx-auto w-full max-w-[880px] space-y-8 px-6 py-8">
      {messages.map((message) => {
        if (message.role === "user") {
          return (
            <div key={message.id} data-testid="user-message" className="flex justify-end">
              <div
                data-testid="user-bubble"
                // 用户气泡：brand-light 底 + 右下角 6px 圆角（形状锁唯一例外，设计 §1.4）
                className="max-w-[70%] rounded-2xl rounded-br-md bg-brand-light px-4 py-2.5"
              >
                {message.attachments.length > 0 ? (
                  <UserAttachmentChips
                    attachments={message.attachments}
                    blobUrls={attachmentBlobUrls}
                  />
                ) : null}
                {/* 用户消息纯文本渲染（防 XSS，不经过 Markdown） */}
                <p className="text-[15px] leading-7 whitespace-pre-wrap break-words">
                  {message.content}
                </p>
              </div>
            </div>
          );
        }

        // AI 消息：无气泡整栏排版（长文阅读友好，视觉重心在内容）
        // 「已停止生成」后缀由 hook 在 CANCELLED 终态追加进 text（Task 11 契约），
        // 本组件直接渲染正文即可，UI 侧不再重复拼接
        const bodyText = message.text;

        return (
          <div key={message.id} data-testid="assistant-message" className="flex gap-3">
            {/* AI 徽标头像（复用 Task 8 组件，小尺寸变体） */}
            <AiBadge className="!size-8" />
            <div className="min-w-0 flex-1 space-y-3">
              {message.thinking ? (
                <ThinkingCard thinking={message.thinking} ended={message.thinkingEnded} />
              ) : null}
              {/* 来源卡组置于正文之前（仅 knowledge_question 意图有） */}
              {message.sources.length > 0 ? (
                <SourcesList sources={message.sources} onNotify={onNotify} />
              ) : null}
              {bodyText || showCursor ? (
                <div className="space-y-3">
                  {bodyText ? <MarkdownView content={bodyText} onNotify={onNotify} /> : null}
                  {/* 流式打字光标（1s 循环；仅动画 opacity，reduced-motion 静态） */}
                  {showCursor ? (
                    <span
                      data-testid="typing-cursor"
                      aria-hidden
                      className="inline-block h-4 w-[2px] translate-y-0.5 animate-cursor-blink rounded-full bg-brand motion-reduce:animate-none"
                    />
                  ) : null}
                </div>
              ) : null}
              {/* 工具卡组（横向排列） */}
              {message.tools.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                  {message.tools.map((tool, index) => (
                    <ToolCallCard key={`${tool.toolCallId || "tool"}-${index}`} tool={tool} />
                  ))}
                </div>
              ) : null}
              {/* 操作栏：end 后浮现（200ms fade-in，transform/opacity，reduced-motion 静态） */}
              {message.endStatus !== null ? (
                <div className="animate-fade-in motion-reduce:animate-none">
                  <FeedbackBar
                    sessionId={sessionId}
                    messageId={message.messageId}
                    hasSources={message.sources.length > 0}
                    text={bodyText}
                    onNotify={onNotify}
                  />
                </div>
              ) : null}
            </div>
          </div>
        );
      })}
    </div>
  );
}
