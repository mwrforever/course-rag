"use client";

/**
 * 对话页共享工作区（/chat 新对话 与 /chat/[sessionId] 历史会话共用，全 CSR）
 *
 * 结构（设计 §1.5.4）：上下文条 40px（返回课程/会话标题/新建对话 + D7 课程名面包屑）
 * → 消息流滚动区（max-w-880 居中、智能吸底滚动）→ 吸底输入区
 * （surface/95 + backdrop-blur + 附件 chips + 发送/停止 morph）。
 *
 * 职责：
 * - useChatStream 全量状态消费；新会话（initialSessionId=null）metadata 到达后
 *   **不替换 URL**（E2E 实证修订 2026-08-24：replace 会重挂载组件致流式状态丢失，
 *   会话定位由 /sessions 与首页最近会话承担，见下文实现注释）
 * - 409/503/网络错误分级 toast（§3.2）；建议提问 chip 点击即发送
 * - 附件全链路：前置校验（超限即拒无网络请求）→ 选中即传（chips 进度环）→
 *   图片 blob URL 预览；blob 生命周期：移除即 revoke、发送后保留供消息内预览、
 *   页面卸载统一 revoke（D12）
 * - 空态：AI 徽标 + 问候（新对话）/「继续提问」（历史会话占位，Task 13 接回显）
 */
import { ArrowLeft, FileText, Paperclip, Plus } from "@phosphor-icons/react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { AiBadge } from "@/components/ai-badge";
import {
  AttachmentChips,
  validateAttachments,
  type PendingAttachment,
} from "@/components/chat/attachment-chips";
import { ChatInput, chatErrorText } from "@/components/chat/chat-input";
import { ChatToast } from "@/components/chat/chat-toast";
import { MessageList, shouldStickToBottom } from "@/components/chat/message-list";
import { SectionError } from "@/components/section-error";
import { useChatStream, type StreamMessage } from "@/hooks/use-chat-stream";
import { uploadAttachments } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import type { AttachmentRecord } from "@/lib/types";

/** 工作区形态：new=新对话（问候空态）/ continue=历史会话（R1 回显 + 继续提问） */
export type ChatVariant = "new" | "continue";

/** 历史回显数据（仅 continue 形态由页面传入；new 形态缺省不渲染） */
export interface HistoryReplay {
  /** 回显加载状态：pending=拉取中 / error=失败可重试 / success=已适配完成 */
  status: "pending" | "error" | "success";
  /** 历史消息（historyAdapter 转换成果；非 success 恒空数组） */
  messages: StreamMessage[];
  /** 重试回调（status=error 时页内横幅 [重试] 触发） */
  retry: () => void;
}

/** 工作区组件 props */
export interface ChatWorkspaceProps {
  /** 初始会话 id（/chat 为 null；/chat/[sessionId] 传 URL 参数） */
  initialSessionId: string | null;
  /** 页面形态（决定空态文案与上下文条标题） */
  variant: ChatVariant;
  /** 上下文条会话标题（缺省按形态取「新对话」/「历史会话」） */
  title?: string;
  /** 历史回显（continue 形态）：与流式消息拼接展示（回显在前、新消息在后） */
  history?: HistoryReplay;
}

/** 建议提问（新对话问候与续会话占位共用，点击即发送） */
const SUGGESTIONS = [
  "什么是 RAG？",
  "帮我总结这门课的重点",
  "怎样高效复习？",
  "讲讲倒排索引的原理",
];

/** toast 展示时长（毫秒，到时自动消失） */
const TOAST_DURATION_MS = 2400;

/** 附件本地 id 递增器（chips 唯一键与移除锚点） */
let attachmentIdSeq = 0;

/**
 * 消息流同形骨架（设计 §1.7 Loading：消息流 → 灰条；Suspense fallback 用）
 */
export function ChatSkeleton() {
  return (
    <div
      data-testid="chat-skeleton"
      className="mx-auto w-full max-w-[880px] space-y-8 px-6 py-8"
      aria-busy="true"
    >
      <div className="flex justify-end">
        <div className="h-16 w-2/5 animate-pulse rounded-2xl bg-surface-2" />
      </div>
      <div className="flex gap-3">
        <div className="size-8 shrink-0 animate-pulse rounded-2xl bg-surface-2" />
        <div className="flex-1 space-y-3">
          <div className="h-20 animate-pulse rounded-2xl bg-surface-2" />
          <div className="h-4 w-3/4 animate-pulse rounded-lg bg-surface-2" />
          <div className="h-4 w-1/2 animate-pulse rounded-lg bg-surface-2" />
        </div>
      </div>
    </div>
  );
}

/**
 * 对话页工作区（新对话全链路 + 续会话「继续提问」占位）
 *
 * @param props 见 ChatWorkspaceProps
 */
export function ChatWorkspace({ initialSessionId, variant, title, history }: ChatWorkspaceProps) {
  const searchParams = useSearchParams();
  const { user } = useAuth();
  const { state, send, cancel, reconnect, reset } = useChatStream(initialSessionId);

  // ── 轻量 toast（页面级状态，定时自动消失；卸载清理定时器）──
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const notify = useCallback((message: string) => {
    setToast(message);
    if (toastTimer.current !== null) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), TOAST_DURATION_MS);
  }, []);
  useEffect(
    () => () => {
      if (toastTimer.current !== null) clearTimeout(toastTimer.current);
    },
    [],
  );

  // ── 附件状态：pending chips + record.url → blob URL 映射（D12 本地预览）──
  const [pendings, setPendings] = useState<PendingAttachment[]>([]);
  const [blobUrls, setBlobUrls] = useState<Record<string, string>>({});
  const blobUrlsRef = useRef(blobUrls);
  useEffect(() => {
    blobUrlsRef.current = blobUrls;
  }, [blobUrls]);

  // ── 新会话 metadata 到达后的 URL 处理（E2E 实证修订 2026-08-24）──
  // 原实现 router.replace('/chat/{sessionId}) 在真实导航下会重挂载本组件，
  // useChatStream 状态（进行中的流/已渲染消息）整体丢失——E2E（route-mock 真实导航）
  // 抓出后改为：新对话**不替换 URL**，sessionId 仅留存在组件状态中供后续能力消费。
  // 会话定位能力由 /sessions 列表与首页最近会话承担，对话页 URL 无功能价值。
  // （设计文档 §1.5.4 metadata 行随本决策修订）

  // ── blob 生命周期收尾：页面卸载统一 revoke（发送后保留供消息内预览的 blob）──
  useEffect(
    () => () => {
      Object.values(blobUrlsRef.current).forEach((url) => URL.revokeObjectURL(url));
    },
    [],
  );

  // ── 智能吸底滚动：仅距底 80px 内跟随（用户上翻阅读不打扰）──
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const lastMessage = state.messages.at(-1);
  // 流式输出变化锚点：最后一条 AI 消息的正文+思考长度变化即触发检查
  const streamAnchor =
    lastMessage?.role === "assistant"
      ? `${lastMessage.text.length}-${lastMessage.thinking.length}`
      : "idle";
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    // jsdom 等测试环境无 scrollTo，退化直接赋 scrollTop（真实浏览器 smooth 跟随）
    if (shouldStickToBottom(el.scrollTop, el.scrollHeight, el.clientHeight)) {
      if (typeof el.scrollTo === "function") {
        el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
      } else {
        el.scrollTop = el.scrollHeight;
      }
    }
  }, [streamAnchor, state.messages.length, state.streaming]);

  /** 移除附件 chip：revoke blob 并从记录映射清除（已发送消息内的映射保留不动） */
  const removeAttachment = useCallback(
    (id: string) => {
      const target = pendings.find((item) => item.id === id);
      if (!target) return;
      URL.revokeObjectURL(target.blobUrl);
      if (target.record) {
        const next = { ...blobUrlsRef.current };
        delete next[target.record.url];
        blobUrlsRef.current = next;
        setBlobUrls(next);
      }
      setPendings((prev) => prev.filter((item) => item.id !== id));
    },
    [pendings],
  );

  /**
   * 附件选择处理：前置校验（超限即拒，不发网络请求）→ 建 blob 预览 → 选中即传
   *
   * @param files 文件输入框选中的新文件
   */
  async function handleFiles(files: FileList | null) {
    if (!files || files.length === 0) return;
    const incoming = Array.from(files);
    // 前置校验镜像后端（≤10 个/图 10MB/文档 50MB/合计 100MB），超限即拒
    const validation = validateAttachments(
      incoming,
      pendings.map((item) => item.file),
    );
    if (!validation.ok) {
      notify(validation.reason as string);
      return;
    }
    // 本地 blob 预览（D12：上传返回 url 是 objectKey，展示必须本地 blob）
    const fresh: PendingAttachment[] = incoming.map((file) => ({
      id: `att-${(attachmentIdSeq += 1)}`,
      file,
      record: null,
      status: "uploading",
      blobUrl: URL.createObjectURL(file),
    }));
    setPendings((prev) => [...prev, ...fresh]);
    try {
      // 选中即传 POST /student/chat/attachments（multipart）
      const records = await uploadAttachments(incoming);
      // 空数据兜底（异常响应体容错）：无记录时条目保持上传中态
      const list = Array.isArray(records) ? records : [];
      // 按请求顺序配对返回记录（multipart 顺序契约）；记录缺失的条目留在上传中态
      setPendings((prev) =>
        prev.map((item) => {
          const index = fresh.findIndex((freshItem) => freshItem.id === item.id);
          const record = index >= 0 && index < list.length ? list[index] : null;
          return record ? { ...item, record, status: "done" } : item;
        }),
      );
    } catch {
      // 上传失败：条目标记失败（可移除重选），提示重试
      setPendings((prev) =>
        prev.map((item) => (item.status === "uploading" ? { ...item, status: "error" } : item)),
      );
      notify("附件上传失败，请重试");
    }
  }

  /** 发送统一入口（输入区与建议 chip 共用）：成功清空 chips，失败向上抛分级 */
  async function sendQuery(query: string, attachmentsRecord: AttachmentRecord[]) {
    await send(query, attachmentsRecord);
    // 发送成功：chips 清空；blob 保留供消息内附件预览（卸载时统一 revoke）
    const kept: Record<string, string> = {};
    for (const item of pendings) {
      if (item.record && item.status === "done") kept[item.record.url] = item.blobUrl;
    }
    if (Object.keys(kept).length > 0) {
      blobUrlsRef.current = { ...blobUrlsRef.current, ...kept };
      setBlobUrls({ ...blobUrlsRef.current });
    }
    setPendings([]);
  }

  /** 输入区发送（带 chips 附件记录；异常由 ChatInput 分级 toast） */
  function handleSend(query: string) {
    const records = pendings
      .filter((item) => item.status === "done" && item.record)
      .map((item) => item.record as AttachmentRecord);
    return sendQuery(query, records);
  }

  /** 建议提问 chip 点击即发送（错误分级 toast 与输入区一致） */
  function handleSuggestion(query: string) {
    const records = pendings
      .filter((item) => item.status === "done" && item.record)
      .map((item) => item.record as AttachmentRecord);
    void sendQuery(query, records).catch((error: unknown) => notify(chatErrorText(error)));
  }

  // ── 渲染状态：上下文条标题 / 空态文案 / 历史回显 ──
  const contextTitle = title ?? (variant === "new" ? "新对话" : "历史会话");
  // carry3：课程入口携带 courseId（返回按钮直达课程工作台）；课程名仍由 course 参数承担面包屑
  const courseId = searchParams.get("courseId");
  const backHref = courseId ? `/courses/${courseId}` : "/courses";
  const courseName = searchParams.get("course");
  const greeting =
    variant === "new" ? `你好，${user?.displayName ?? "同学"}，想了解什么？` : "继续提问";
  const greetingSub =
    variant === "new"
      ? "基于课程知识库回答，问题可带附件，来源随时可追溯"
      : "接着聊，AI 助教记得上一轮的回答";

  // 历史回显（continue）：成功态消息与流式消息拼接（回显在前、新消息在后）
  const historyMessages = history?.status === "success" ? history.messages : [];
  const displayMessages = [...historyMessages, ...state.messages];
  const isEmpty = displayMessages.length === 0;

  return (
    <div className="flex h-[calc(100dvh-4rem)] flex-col" data-testid="chat-workspace">
      {/* 上下文条 40px：← 返回课程 · 会话标题 · 新建对话（D7 课程名面包屑） */}
      <div className="flex h-10 shrink-0 items-center gap-2 border-b border-border bg-surface px-6 text-sm">
        <Link
          href={backHref}
          className="flex items-center gap-1 text-muted transition-colors hover:text-brand-strong"
        >
          <ArrowLeft size={14} aria-hidden />
          返回课程
        </Link>
        {courseName ? (
          // D7 上下文条：query 携带课程名时展示面包屑（纯前端，不动 ChatRequest 契约）
          <span
            data-testid="course-breadcrumb"
            className="max-w-40 truncate rounded-full bg-brand-soft px-2.5 py-0.5 text-xs text-brand-strong"
          >
            {courseName}
          </span>
        ) : null}
        <span aria-hidden className="h-4 w-px bg-border" />
        <span className="min-w-0 truncate font-medium text-text" data-testid="context-title">
          {contextTitle}
        </span>
        <Link
          href="/chat"
          className="ml-auto flex shrink-0 items-center gap-1 rounded-xl px-2.5 py-1 text-xs text-muted transition-colors hover:bg-surface-2 hover:text-brand-strong"
        >
          <Plus size={13} aria-hidden />
          新建对话
        </Link>
      </div>

      {/* 消息流滚动区（智能吸底滚动） */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto" data-testid="message-scroll">
        {history && history.status === "pending" && displayMessages.length === 0 ? (
          // 历史回显加载中：消息区骨架（与 Suspense fallback 同形）
          <ChatSkeleton />
        ) : history && history.status === "error" && displayMessages.length === 0 ? (
          // 历史回显失败：页内横幅 + 重试（设计 §1.7 Error）
          <div className="mx-auto w-full max-w-[880px] px-6 py-8">
            <SectionError onRetry={history.retry} />
          </div>
        ) : isEmpty ? (
          // 空态：AI 徽标 + 问候/继续提问 + 建议提问 chip（设计 §1.7 Empty）
          <div className="flex h-full flex-col items-center justify-center gap-5 px-6 text-center">
            <AiBadge />
            <div className="space-y-1">
              <h2 className="text-xl font-semibold text-text">{greeting}</h2>
              <p className="text-sm text-muted">{greetingSub}</p>
            </div>
            <div className="flex max-w-2xl flex-wrap justify-center gap-2">
              {SUGGESTIONS.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  data-testid="suggestion-chip"
                  onClick={() => handleSuggestion(suggestion)}
                  className="rounded-full border border-border bg-surface px-4 py-2 text-sm text-muted transition-colors hover:border-brand/40 hover:bg-brand-light hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
                >
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <MessageList
            messages={displayMessages}
            streaming={state.streaming}
            sessionId={state.sessionId ?? ""}
            attachmentBlobUrls={blobUrls}
            onNotify={notify}
          />
        )}

        {/* 消息尾错误横幅（设计 §1.5.4 error 事件）：分级操作——retryable=重试（手动重连）
             replay_failed=重新提问（清空对话引导重问）；auth 由全局登出流承接，仅展示文案 */}
        {state.error ? (
          <div
            role="alert"
            data-testid="stream-error-banner"
            className="mx-auto w-full max-w-[880px] px-6 pt-4"
          >
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3">
              <p className="min-w-0 flex-1 text-sm text-danger">{state.error.message}</p>
              {state.error.kind === "retryable" ? (
                <button
                  type="button"
                  onClick={() => void reconnect()}
                  className="shrink-0 rounded-lg border border-danger/30 bg-surface px-3 py-1.5 text-sm font-medium text-danger transition-colors hover:bg-danger/10 focus-visible:ring-2 focus-visible:ring-danger"
                >
                  重试
                </button>
              ) : null}
              {state.error.kind === "replay_failed" ? (
                <button
                  type="button"
                  onClick={() => reset()}
                  className="shrink-0 rounded-lg border border-danger/30 bg-surface px-3 py-1.5 text-sm font-medium text-danger transition-colors hover:bg-danger/10 focus-visible:ring-2 focus-visible:ring-danger"
                >
                  重新提问
                </button>
              ) : null}
            </div>
          </div>
        ) : null}
      </div>

      {/* 吸底输入区：surface/95 + backdrop-blur + 顶部 1px 边框 */}
      <div className="shrink-0 border-t border-border bg-surface/95 backdrop-blur">
        <div className="mx-auto w-full max-w-[880px] px-6 py-4">
          {pendings.length > 0 ? (
            <AttachmentChips items={pendings} onRemove={removeAttachment} />
          ) : null}
          <div className="flex items-end gap-2">
            {/* ＋附件：图片/文档双入口（G11 白名单各自独立 accept） */}
            <div className="relative flex shrink-0 items-center">
              <label
                htmlFor="attachment-image-input"
                title="上传图片"
                aria-label="上传图片"
                className="grid size-10 cursor-pointer place-items-center rounded-xl border border-border bg-surface text-muted transition-colors hover:bg-surface-2 hover:text-brand-strong"
              >
                <Paperclip size={17} aria-hidden />
              </label>
              <input
                id="attachment-image-input"
                data-testid="file-input-image"
                type="file"
                accept="image/jpeg,image/png,image/gif,image/webp,image/bmp"
                multiple
                className="sr-only"
                onChange={(event) => {
                  void handleFiles(event.target.files);
                  // 清空已选，允许同文件重复选择
                  event.target.value = "";
                }}
              />
              <label
                htmlFor="attachment-doc-input"
                title="上传文档"
                aria-label="上传文档"
                className="grid size-10 cursor-pointer place-items-center rounded-xl border border-border bg-surface text-muted transition-colors hover:bg-surface-2 hover:text-brand-strong"
              >
                <FileText size={17} aria-hidden />
              </label>
              <input
                id="attachment-doc-input"
                data-testid="file-input-doc"
                type="file"
                accept=".pdf,.doc,.docx,.txt,.md"
                multiple
                className="sr-only"
                onChange={(event) => {
                  void handleFiles(event.target.files);
                  event.target.value = "";
                }}
              />
            </div>
            <ChatInput
              streaming={state.streaming}
              sendDisabled={pendings.some((item) => item.status === "uploading")}
              onSend={handleSend}
              onCancel={() => void cancel()}
              onNotify={notify}
            />
          </div>
          {/* 底部免责（设计 §1.5.4 输入区 Caption） */}
          <p className="mt-2 text-center text-xs text-subtle">AI 生成内容仅供参考</p>
        </div>
      </div>

      <ChatToast message={toast} />
    </div>
  );
}
