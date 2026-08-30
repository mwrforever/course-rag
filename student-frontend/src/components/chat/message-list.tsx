"use client";

/**
 * 对话消息流（设计 §1.5.4 消息流 + §1.6 动效；2026-08-28 时间线改版；
 * 2026-08-29 Task 14 memo 化：历史行 props 稳定不随流式 delta 重渲染）
 *
 * - 容器 max-w-[840px] 居中，消息间距 space-y-8（页面内滚动区）
 * - 用户消息：右对齐 bubble 气泡，rounded-[18px] rounded-br-[8px]
 *   （形状锁唯一例外）；附件缩略 chips（图片 blob 缩略 / 文档图标），纯文本防 XSS
 * - AI 消息：无气泡整栏（阅读友好），AI 徽标头像 → 模型徽标 → ChainTimeline
 *   （思考/检索/工具按到达序挂链，2026-08-30 对齐设计稿：无阶段/查询计划步骤；
 *   来源步骤点击开召回抽屉、工具步骤点击开工具结果抽屉）→
 *   答案块（左渐变竖线 + 光标）→ 操作栏；召回片段经右侧 RetrievalDrawer 展示
 * - 流式空窗占位：最后一条 AI 消息流式中且时间轴/正文皆空时渲染三点脉冲
 *   （METADATA 已到、首个内容事件 thinking/sources/tool/delta 未到的极短窗口；
 *   后端 METADATA 已前移至附件处理前）
 * - 流式打字光标（1s 循环，仅最后一条 AI 消息且 streaming 时挂载）
 * - 「已停止生成」后缀：hook 已在 text 追加（Task 11 契约），本组件直接渲染唯一一份；
 *   复制时由 FeedbackBar 剥离后缀（carry2）
 * - end 后操作栏 200ms fade-in（transform/opacity，reduced-motion 静态）
 *
 * 渲染性能契约（Task 14）：消息行拆 memo——reducer 不可变更新只改变末条消息对象
 * 身份，历史行 message 引用稳定 → memo 跳过重渲染（含昂贵的 MarkdownView）；
 * 仅末条流式行随 delta 逐帧更新。抽屉开关经 useCallback 稳定引用（避免逐帧新闭包
 * 击穿 memo）。
 */
import { FileText, Sparkle } from "@phosphor-icons/react";
import { memo, useCallback, useState } from "react";
import { ChainTimeline } from "./chain-timeline";
import { FeedbackBar } from "./feedback-bar";
import { MarkdownView } from "./markdown-view";
import { RetrievalDrawer } from "./retrieval-drawer";
import { ToolResultDrawer } from "./tool-result-drawer";
import type { StreamMessage } from "@/hooks/use-chat-stream";
import type { AttachmentRecord, TimelineToolNode } from "@/lib/types";

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
 * 流式空窗三点脉冲（METADATA 已到、首个内容事件 thinking/sources/tool/delta 未到的极短窗口占位）
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

/** 用户消息行 props（memo 依据：message 与 blobUrls 引用稳定即跳过重渲染） */
interface UserMessageRowProps {
  /** 用户消息（reducer 不可变更新保证历史行引用稳定） */
  message: StreamMessage;
  /** 附件记录 url → blob URL 映射（附件发送时才变化） */
  blobUrls: Record<string, string>;
}

/**
 * 用户消息行（memo，Task 14）：右对齐 bubble 气泡 + 附件 chips + 纯文本正文
 */
const UserMessageRow = memo(function UserMessageRow({ message, blobUrls }: UserMessageRowProps) {
  return (
    <div data-testid="user-message" className="flex justify-end">
      <div
        data-testid="user-bubble"
        // 用户气泡：暖白 bubble 底 + 右下角小圆角（site 形状锁唯一例外）
        className="max-w-[70%] rounded-[18px] rounded-br-[8px] bg-bubble px-4 py-2.5"
      >
        {message.attachments.length > 0 ? (
          <UserAttachmentChips attachments={message.attachments} blobUrls={blobUrls} />
        ) : null}
        {/* 用户消息纯文本渲染（防 XSS，不经过 Markdown） */}
        <p className="text-[15px] leading-7 whitespace-pre-wrap break-words">{message.content}</p>
      </div>
    </div>
  );
});

/** AI 消息行 props（memo 依据：message/streaming/isLast 与稳定回调） */
interface AssistantMessageRowProps {
  /** AI 消息（流式 delta 只更新末条对象身份，历史行引用稳定） */
  message: StreamMessage;
  /** 是否正在生成（全局流式态，run 级变化） */
  streaming: boolean;
  /** 本条是否为消息流末条（打字光标与时间轴 running 态判定） */
  isLast: boolean;
  /** 当前会话 id（操作栏反馈请求体） */
  sessionId: string;
  /** 提示回调（复制/反馈 toast，页面统一呈现；useCallback 稳定引用） */
  onNotify(message: string): void;
  /** 打开召回抽屉（稳定引用：行内构造来源闭包，避免逐帧新闭包击穿 memo） */
  onOpenSources(sources: StreamMessage["sources"]): void;
  /** 打开工具结果抽屉（稳定引用；2026-08-30 工具结果侧栏展示） */
  onOpenTool(tool: TimelineToolNode): void;
}

/**
 * AI 消息行（memo，Task 14）：徽标头像 → 模型徽标 → ChainTimeline → 答案块 → 操作栏
 *
 * 「已停止生成」后缀由 hook 在 CANCELLED 终态追加进 text（Task 11 契约），本组件
 * 直接渲染正文即可，UI 侧不再重复拼接。
 */
const AssistantMessageRow = memo(function AssistantMessageRow({
  message,
  streaming,
  isLast,
  sessionId,
  onNotify,
  onOpenSources,
  onOpenTool,
}: AssistantMessageRowProps) {
  const bodyText = message.text;
  // 本条消息是否为流式中的最后一条（时间轴末步骤 running 态判定）
  const isStreamingMessage = streaming && isLast;
  // 打字光标归属：末条 AI 消息且流式进行中
  const showCursor = isStreamingMessage;
  // 流式空窗：时间轴与正文皆空（METADATA 刚到的窗口）
  const awaitingFirstSignal =
    isStreamingMessage && message.timeline.length === 0 && message.text.length === 0;

  return (
    <div data-testid="assistant-message" className="flex gap-3">
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
        {/* 流式空窗三点脉冲（首个内容事件未到的极短窗口；thinking/sources/tool 到达后
            由链式时间轴接管、delta 到达后由正文渲染接管） */}
        {awaitingFirstSignal ? <StreamingDots /> : null}
        {/* 链式时间轴：思考/检索/工具按到达序挂链（2026-08-30 对齐设计稿：无阶段/查询
            计划步骤；来源步骤点击开召回抽屉、工具步骤点击开工具结果抽屉） */}
        {message.timeline.length > 0 ? (
          <ChainTimeline
            timeline={message.timeline}
            active={isStreamingMessage}
            onOpenSources={() => onOpenSources(message.sources)}
            onOpenTool={onOpenTool}
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
});

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
  // 工具结果抽屉状态：打开时持有该工具节点（2026-08-30 工具结果侧栏展示）
  const [drawerTool, setDrawerTool] = useState<TimelineToolNode | null>(null);
  // 抽屉开关稳定引用（Task 14：逐帧新闭包会击穿消息行 memo）
  const openDrawer = useCallback(
    (sources: StreamMessage["sources"]) => setDrawerSources(sources),
    [],
  );
  const closeDrawer = useCallback(() => setDrawerSources(null), []);
  const openToolDrawer = useCallback((tool: TimelineToolNode) => setDrawerTool(tool), []);
  const closeToolDrawer = useCallback(() => setDrawerTool(null), []);

  // 末条消息定位（打字光标与 running 态判定；身份比对，引用稳定）
  const last = messages.at(-1);

  return (
    <div data-testid="message-flow" className="mx-auto w-full max-w-[840px] space-y-8 px-6 py-8">
      {messages.map((message) =>
        message.role === "user" ? (
          <UserMessageRow key={message.id} message={message} blobUrls={attachmentBlobUrls} />
        ) : (
          <AssistantMessageRow
            key={message.id}
            message={message}
            streaming={streaming}
            isLast={message === last}
            sessionId={sessionId}
            onNotify={onNotify}
            onOpenSources={openDrawer}
            onOpenTool={openToolDrawer}
          />
        ),
      )}
      {/* 知识库召回抽屉（打开时挂载；来源列表快照传入） */}
      <RetrievalDrawer sources={drawerSources} onClose={closeDrawer} />
      {/* 工具结果抽屉（打开时挂载；工具节点快照传入，2026-08-30 工具结果侧栏展示） */}
      <ToolResultDrawer tool={drawerTool} onClose={closeToolDrawer} />
    </div>
  );
}
