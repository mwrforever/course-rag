"use client";

/**
 * 对话消息流（设计 §1.5.4 消息流 + §1.6 动效；2026-08-28 时间线改版）
 *
 * - 容器 max-w-[840px] 居中，消息间距 space-y-8（页面内滚动区）
 * - 用户消息：右对齐 bubble 气泡，rounded-[18px] rounded-br-[8px]
 *   （形状锁唯一例外）；附件缩略 chips（图片 blob 缩略 / 文档图标），纯文本防 XSS
 * - AI 消息：无气泡整栏（阅读友好），AI 徽标头像 → 模型徽标 → ChainTimeline
 *   （阶段/思考/查询计划/检索/工具按到达序挂链，工具卡并入时间轴）→
 *   答案块（左渐变竖线 + 光标）→ 操作栏；召回片段经右侧 RetrievalDrawer 展示
 * - 流式空窗占位：最后一条 AI 消息流式中且时间轴/正文皆空时渲染三点脉冲
 *   （METADATA 已到、首个阶段事件未到的极短窗口；后端 METADATA 已前移至附件处理前）
 * - 流式打字光标（1s 循环，仅最后一条 AI 消息且 streaming 时挂载）
 * - 「已停止生成」后缀：hook 已在 text 追加（Task 11 契约），本组件直接渲染唯一一份；
 *   复制时由 FeedbackBar 剥离后缀（carry2）
 * - end 后操作栏 200ms fade-in（transform/opacity，reduced-motion 静态）
 */
import { FileText, Sparkle } from "@phosphor-icons/react";
import { useState } from "react";
import { ChainTimeline } from "./chain-timeline";
import { FeedbackBar } from "./feedback-bar";
import { MarkdownView } from "./markdown-view";
import { RetrievalDrawer } from "./retrieval-drawer";
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
 * 流式空窗三点脉冲（METADATA 已到、STAGE/思考/正文未到的极短窗口占位）
 */
function StreamingDots() {
  return (
    <span
      data-testid="streaming-dots"
      aria-label="正在准备"
      className="inline-flex items-center gap-1 py-1"
    >
      {[0, 1, 2].map((index) => (
        <span
          key={index}
          aria-hidden
          className="size-1.5 animate-streaming-dot rounded-full bg-brand motion-reduce:animate-none"
          style={{ animationDelay: `${index * 0.18}s` }}
        />
      ))}
    </span>
  );
}

/**
 * 对话消息流（用户气泡 + AI 整栏复合块 + 召回抽屉）
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
  // 召回抽屉状态：打开时持有该消息的来源列表（一次只开一条消息的抽屉）
  const [drawerSources, setDrawerSources] = useState<StreamMessage["sources"] | null>(null);

  // 打字光标归属：最后一条为 AI 消息且流式进行中
  const last = messages.at(-1);
  const showCursor = streaming && last?.role === "assistant";

  return (
    <div data-testid="message-flow" className="mx-auto w-full max-w-[840px] space-y-8 px-6 py-8">
      {messages.map((message) => {
        if (message.role === "user") {
          return (
            <div key={message.id} data-testid="user-message" className="flex justify-end">
              <div
                data-testid="user-bubble"
                // 用户气泡：暖白 bubble 底 + 右下角小圆角（site 形状锁唯一例外）
                className="max-w-[70%] rounded-[18px] rounded-br-[8px] bg-bubble px-4 py-2.5"
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
        // 本条消息是否为流式中的最后一条（时间轴末步骤 running 态判定）
        const isStreamingMessage = streaming && message === last;
        // 流式空窗：时间轴与正文皆空（METADATA 刚到的窗口）
        const awaitingFirstSignal =
          isStreamingMessage && message.timeline.length === 0 && message.text.length === 0;

        return (
          <div key={message.id} data-testid="assistant-message" className="flex gap-3">
            {/* AI 徽标头像：静态渐变徽章（对话流式期间每条消息一个头像，
                无限呼吸动画实例会叠加重绘成本，卡顿治理 2026-08-26 改静态；
                品牌呼吸浮标仅保留首页 Hero / 空态等装饰场景） */}
            <span
              aria-hidden
              className="bg-gradient-ai grid size-8 shrink-0 place-items-center rounded-full text-white shadow-sm shadow-brand/30"
            >
              <Sparkle size={15} weight="fill" />
            </span>
            <div className="min-w-0 flex-1 space-y-3">
              {/* 模型徽标：metadata 到达后展示（设计 M10：正常路径必渲染，降级回放无 metadata 时不渲染） */}
              {message.model ? (
                <div className="flex items-center gap-2">
                  <span
                    data-testid="model-badge"
                    className="rounded-full bg-brand-soft px-2 py-0.5 text-xs font-medium text-brand-strong"
                  >
                    {message.model}
                  </span>
                </div>
              ) : null}
              {/* 流式空窗三点脉冲（阶段事件未到的极短窗口；STAGE 到达后由链式时间轴接管） */}
              {awaitingFirstSignal ? <StreamingDots /> : null}
              {/* 链式时间轴：阶段/思考/查询计划/检索/工具按到达序挂链
                  （工具卡并入时间轴，来源步骤点击开召回抽屉） */}
              {message.timeline.length > 0 ? (
                <ChainTimeline
                  timeline={message.timeline}
                  active={isStreamingMessage}
                  onOpenSources={() => setDrawerSources(message.sources)}
                />
              ) : null}
              {/* 答案块：左渐变竖线引导 + Markdown 正文 + 流式光标 */}
              {bodyText || showCursor ? (
                <div className="chain-answer">
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
              {/* 操作栏：end 后浮现（200ms fade-in，transform/opacity，reduced-motion 静态） */}
              {message.endStatus !== null ? (
                <div className="animate-fade-in motion-reduce:animate-none">
                  <FeedbackBar
                    sessionId={sessionId}
                    messageId={message.messageId}
                    hasSources={message.sources.length > 0}
                    text={bodyText}
                    intentType={message.intentType}
                    onNotify={onNotify}
                  />
                </div>
              ) : null}
            </div>
          </div>
        );
      })}
      {/* 知识库召回抽屉（打开时挂载；来源列表快照传入） */}
      <RetrievalDrawer sources={drawerSources} onClose={() => setDrawerSources(null)} />
    </div>
  );
}
