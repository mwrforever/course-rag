"use client";

/**
 * 对话页共享工作区（/chat 新对话 与 /chat/[sessionId] 历史会话共用，全 CSR）
 *
 * 结构（设计 §1.5.4）：上下文条 48px/h-12（返回课程/会话标题 + D7 课程名面包屑；
 * 2026-08-29 Task 13 顶栏「新建对话」移除，新建入口收敛到侧栏按钮）
 * → 消息流滚动区（max-w-840 居中、智能吸底滚动）→ 吸底输入区
 * （bg-bg/80 + backdrop-blur + 附件 chips 内嵌输入卡 + 发送/停止 morph）。
 *
 * 职责：
 * - useChatStream 全量状态消费；新会话（initialSessionId=null）metadata 到达后
 *   **不替换 URL**（E2E 实证修订 2026-08-24：replace 会重挂载组件致流式状态丢失，
 *   会话定位由 /sessions 与首页最近会话承担，见下文实现注释）
 * - 流式状态上报 (chat) 布局 Context（侧栏 Ctrl+K 守卫）；会话归属落位即失效
 *   侧栏历史缓存（布局常驻 QueryClient 长活，不失效则新会话不进侧栏）
 * - 409/503/网络错误分级 toast（§3.2）；建议提问 chip 点击即发送
 * - 附件全链路：前置校验（超限即拒无网络请求）→ 选中即传（chips 进度环）→
 *   图片 blob URL 预览；blob 生命周期：移除即 revoke、发送后保留供消息内预览、
 *   发送成功的失败 chips 随清理 revoke、页面卸载统一 revoke（D12；BUG-20）
 * - 空态：AI 徽标 + 问候（新对话）/「继续提问」（历史会话占位，Task 13 接回显）
 */
import { ArrowLeft, Paperclip } from "@phosphor-icons/react";
import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { AiBadge } from "@/components/ai-badge";
import {
  AttachmentChips,
  classifyFile,
  validateAttachments,
  type PendingAttachment,
} from "@/components/chat/attachment-chips";
import { AttachmentPreviewDialog } from "@/components/chat/attachment-preview-dialog";
import { ChatInput, chatErrorText } from "@/components/chat/chat-input";
import { ChatToast } from "@/components/chat/chat-toast";
import { SIDEBAR_SESSIONS_QUERY_KEY } from "@/components/chat/chat-sidebar";
import { useChatNewChatSeq, useSetChatStreaming } from "@/components/chat/chat-streaming-context";
import { MessageList, shouldStickToBottom } from "@/components/chat/message-list";
import { SectionError } from "@/components/section-error";
import { useChatStream, type StreamMessage } from "@/hooks/use-chat-stream";
import { uploadAttachments } from "@/lib/api";
import { createAttachmentThumbUrl } from "@/lib/attachment-thumb";
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
  /**
   * 活跃 run 续流锚点（2026-09-01 多会话并发）：切回仍有 run 在生成的会话时，
   * 页面经 GET active-run 拿到 runId 传入，工作区全量回放续流恢复实时视图。
   */
  resumeRunId?: string | null;
  /**
   * 续流占位标记（M6.4）：活跃 run 存在且历史为空（回放尚未送达任何帧）时，
   * 消息区渲染「正在继续生成…」占位，避免续流窗口期看起来像坏了；
   * resume 失败静默降级为普通空态（占位随 active-run 查询结果消失）。
   */
  resumingPlaceholder?: boolean;
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
      className="mx-auto w-full max-w-[840px] space-y-8 px-6 py-8"
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
export function ChatWorkspace({
  initialSessionId,
  variant,
  title,
  history,
  resumeRunId,
  resumingPlaceholder,
}: ChatWorkspaceProps) {
  const searchParams = useSearchParams();
  const { user } = useAuth();
  // 快速提问预填：首页快问框提交带 ?q=，仅新对话页生效（预填不自动发送，避免误发）
  const quickQuery = searchParams.get("q");
  const queryClient = useQueryClient();
  const setStreaming = useSetChatStreaming();
  // 新建对话信号（Task 13）：侧栏 /chat 同路由按钮经 Context 发出，本组件消费执行干净态
  const newChatSeq = useChatNewChatSeq();
  const { state, send, cancel, reconnect, reset, resume, detach, replay } =
    useChatStream(initialSessionId);

  // ── 受控输入（Task 13）：工作区持有输入值，新建信号经 resetKey 驱动清空 ──
  // 初值取 /chat?q= 快速提问预填（仅新对话页；预填不自动发送，避免误发）
  const [inputValue, setInputValue] = useState(variant === "new" ? (quickQuery ?? "") : "");
  const [inputResetKey, setInputResetKey] = useState(0);

  // ── 流式状态上报 (chat) 布局 Context：侧栏据守卫 Ctrl+K/新建对话（防跳转丢流），
  // 并携带会话 id 供侧栏对应会话行渲染生成中动画（2026-08-27）──
  useEffect(() => {
    setStreaming(state.streaming, state.sessionId);
    // 卸载/导航离开时复位，避免侧栏残留流式守卫态
    return () => setStreaming(false);
  }, [state.streaming, state.sessionId, setStreaming]);

  // ── 会话归属落位即失效侧栏历史缓存 ──
  // (chat) 组布局常驻 → QueryClient 长活，侧栏查询无 refetch 触发点；
  // 新会话在 metadata 到达（sessionId 落位）后必须主动失效，才会出现在侧栏历史里。
  // 续会话进入（initialSessionId 非空）同样触发一次，顺带刷新侧栏排序。
  useEffect(() => {
    if (state.sessionId) {
      void queryClient.invalidateQueries({ queryKey: SIDEBAR_SESSIONS_QUERY_KEY });
    }
  }, [state.sessionId, queryClient]);

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
  // 最新 pendings 镜像（ref）：sendQuery 的 await 结算须读最新快照——闭包中的
  // pendings 是发送时的旧值，await 期间用户新选的附件会丢失（BUG-20）
  const pendingsRef = useRef(pendings);
  useEffect(() => {
    pendingsRef.current = pendings;
  }, [pendings]);
  const [blobUrls, setBlobUrls] = useState<Record<string, string>>({});
  // ── 附件预览弹窗（Task 12）：chip 点击打开，Esc/遮罩关闭（null=关闭）──
  const [previewItem, setPreviewItem] = useState<PendingAttachment | null>(null);
  // ── 拖拽上传态（Task 12）：文件拖入工作区点亮高亮层，释放触发上传 ──
  const [dragActive, setDragActive] = useState(false);
  const blobUrlsRef = useRef(blobUrls);
  useEffect(() => {
    blobUrlsRef.current = blobUrls;
  }, [blobUrls]);

  // ── 新会话 metadata 到达后的 URL 处理（E2E 实证修订 2026-08-24）──
  // 原实现 router.replace('/chat/{sessionId}) 在真实导航下会重挂载本组件，
  // useChatStream 状态（进行中的流/已渲染消息）整体丢失：E2E（route-mock 真实导航）
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

  // ── 新建对话信号消费（Task 13）：侧栏按钮 seq 自增 → 干净态 ──
  // 2026-09-01 多会话并发：先 detach 旧流（停消费循环，旧会话 run 在服务端继续执行、
  // 事件留 ring 供切回时 resume 续流），再清流式/附件/输入（clearSession=true 连会话
  // 归属清空，下次发送建新会话）；URL 不变不重挂载
  const lastNewChatSeq = useRef(newChatSeq);
  useEffect(() => {
    if (lastNewChatSeq.current === newChatSeq) return;
    lastNewChatSeq.current = newChatSeq;
    detach();
    reset(true);
    // 附件干净态：pending chips（原图与缩略均 revoke）与消息内 blob 映射全部 revoke 后清空
    pendings.forEach((item) => {
      URL.revokeObjectURL(item.blobUrl);
      if (item.thumbUrl) {
        URL.revokeObjectURL(item.thumbUrl);
      }
    });
    Object.values(blobUrlsRef.current).forEach((url) => URL.revokeObjectURL(url));
    blobUrlsRef.current = {};
    setBlobUrls({});
    setPendings([]);
    setPreviewItem(null);
    // 输入干净态：resetKey 自增驱动 ChatInput 清空
    setInputResetKey((key) => key + 1);
    // pendings 入 deps 仅取最新值：seq 未变时守卫直接返回，无重复执行
  }, [newChatSeq, reset, detach, pendings]);

  // ── 活跃 run 续流（2026-09-01 多会话并发）：切回正在生成的会话 → 全量回放恢复实时视图 ──
  // 页面端 active-run 查询命中（该会话仍有 QUEUED/ACTIVE run）后传入 runId；幂等：
  // 同 runId 只 resume 一次（ref 守卫），已流式/已终态由 hook 内部守卫兜底
  const lastResumeRunId = useRef<string | null>(null);
  useEffect(() => {
    if (!resumeRunId || resumeRunId === lastResumeRunId.current) return;
    lastResumeRunId.current = resumeRunId;
    void resume(resumeRunId);
  }, [resumeRunId, resume]);

  // ── 智能吸底滚动：仅距底 80px 内跟随（用户上翻阅读不打扰）──
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const lastMessage = state.messages.at(-1);
  // 流式输出变化锚点：最后一条 AI 消息的正文+时间轴规模变化即触发检查
  // （时间轴以「节点数 + 末思考节点行数」计——思考行流式追加时逐行驱动吸底检查）
  const lastNode = lastMessage?.role === "assistant" ? lastMessage.timeline.at(-1) : undefined;
  const lastThinkingLines = lastNode?.kind === "thinking" ? lastNode.lines.length : 0;
  const streamAnchor =
    lastMessage?.role === "assistant"
      ? `${lastMessage.text.length}-${lastMessage.timeline.length}-${lastThinkingLines}`
      : "idle";
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    // jsdom 等测试环境无 scrollTo，退化直接赋 scrollTop（真实浏览器瞬时吸底——
    // 高频流式 delta 下 smooth 动画与渲染叠加掉帧，卡顿治理 2026-08-26 改 auto）
    if (shouldStickToBottom(el.scrollTop, el.scrollHeight, el.clientHeight)) {
      if (typeof el.scrollTo === "function") {
        el.scrollTo({ top: el.scrollHeight, behavior: "auto" });
      } else {
        el.scrollTop = el.scrollHeight;
      }
    }
  }, [streamAnchor, state.messages.length, state.streaming]);

  /** 移除附件 chip：revoke blob（原图与缩略均回收）并从记录映射清除（已发送消息内的映射保留不动） */
  const removeAttachment = useCallback(
    (id: string) => {
      const target = pendings.find((item) => item.id === id);
      if (!target) return;
      URL.revokeObjectURL(target.blobUrl);
      // PERF-18：缩略 blob 一并回收（未生成时为 undefined 跳过）
      if (target.thumbUrl) {
        URL.revokeObjectURL(target.thumbUrl);
      }
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

  /** 拖拽经过：拦截浏览器默认打开行为并点亮高亮层（仅文件类拖拽） */
  function handleDragOver(event: React.DragEvent<HTMLDivElement>) {
    // 非文件拖拽（页内文本选择等）不接管，保持原生行为
    if (!event.dataTransfer.types.includes("Files")) return;
    event.preventDefault();
    setDragActive(true);
  }

  /** 拖离容器：高亮层熄灭（子元素间移动不误判，relatedTarget 仍在容器内时忽略） */
  function handleDragLeave(event: React.DragEvent<HTMLDivElement>) {
    if (event.currentTarget.contains(event.relatedTarget as Node | null)) return;
    setDragActive(false);
  }

  /** 拖拽释放：取 dataTransfer.files 走与文件选择同一上传链路（前置校验即拒共用） */
  function handleDrop(event: React.DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setDragActive(false);
    void handleFiles(event.dataTransfer?.files ?? null);
  }

  /**
   * 附件选择处理：前置校验（超限即拒，不发网络请求）→ 建 blob 预览 → 选中即传
   *
   * @param files 新文件集合（输入框选择的 FileList 或直接粘贴的 File[]，同一链路）
   */
  async function handleFiles(files: FileList | File[] | null) {
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
    // 本地 blob 预览（D12：上传返回 url 是 objectKey，展示必须本地 blob）；
    // PERF-18：图片附件另异步生成 96px 缩略 blob（chips 36px/消息行 28px 用缩略，
    // 相机原图不再整图驻留解码；预览弹窗保留原图 blobUrl，生成期间以原图瞬时占位）
    const fresh: PendingAttachment[] = incoming.map((file) => ({
      id: `att-${(attachmentIdSeq += 1)}`,
      file,
      record: null,
      status: "uploading",
      blobUrl: URL.createObjectURL(file),
    }));
    setPendings((prev) => [...prev, ...fresh]);
    // PERF-18：图片附件异步生成缩略（非图片不动；失败返回 null 以原图兜底）
    for (const item of fresh) {
      if (classifyFile(item.file) !== "image") continue;
      void createAttachmentThumbUrl(item.file).then((thumbUrl) => {
        if (!thumbUrl) return;
        setPendings((prev) => {
          // 条目已被移除：缩略 URL 未被引用，即刻回收防泄漏
          if (!prev.some((pending) => pending.id === item.id)) {
            URL.revokeObjectURL(thumbUrl);
            return prev;
          }
          return prev.map((pending) =>
            pending.id === item.id ? { ...pending, thumbUrl } : pending,
          );
        });
      });
    }
    // 本批上传中条目 id 集合：进度回调只更新本批 chips（多批并发上传不互相覆盖进度）
    const freshIds = new Set(fresh.map((item) => item.id));
    try {
      // 选中即传 POST /student/chat/attachments（multipart 单请求不变；
      // PERF-10a：XHR 进度回调驱动本批 chips 确定进度环）
      const records = await uploadAttachments(incoming, (percent) => {
        setPendings((prev) =>
          prev.map((item) =>
            item.status === "uploading" && freshIds.has(item.id)
              ? { ...item, progress: percent }
              : item,
          ),
        );
      });
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

  /** 发送统一入口（输入区与建议 chip 共用）：成功后按发送时快照结算 chips，失败向上抛分级 */
  async function sendQuery(query: string, attachmentsRecord: AttachmentRecord[]) {
    // 发送时在场快照与已提交记录集合：await 期间新增的 chips 不属于本次发送（BUG-20）
    const sentIds = new Set(pendings.map((item) => item.id));
    const sentUrls = new Set(attachmentsRecord.map((record) => record.url));
    await send(query, attachmentsRecord);
    // 发送成功：以最新快照结算（闭包 pendings 在 await 期间已过期）——
    // - 已随消息提交的 chips：缩略（无则原图）迁入消息预览映射后移除（卸载时统一 revoke；
    //   PERF-18：缩略接管渲染后原图 blob 不再被引用，即刻回收）
    // - 发送时已失败的 chips：revoke blob（原图与缩略）后移除（不随清理泄漏）
    // - await 期间新增的 chips 与发送时仍在传中的 chips：保留，不连带清空
    const latest = pendingsRef.current;
    const kept: Record<string, string> = {};
    for (const item of latest) {
      if (item.record && sentUrls.has(item.record.url)) {
        kept[item.record.url] = item.thumbUrl ?? item.blobUrl;
        // 缩略接管消息行渲染：原图 blob 无人引用即刻回收（预览弹窗若开着已加载帧不受影响）
        if (item.thumbUrl) {
          URL.revokeObjectURL(item.blobUrl);
        }
      } else if (sentIds.has(item.id) && item.status === "error") {
        URL.revokeObjectURL(item.blobUrl);
        if (item.thumbUrl) {
          URL.revokeObjectURL(item.thumbUrl);
        }
      }
    }
    if (Object.keys(kept).length > 0) {
      blobUrlsRef.current = { ...blobUrlsRef.current, ...kept };
      setBlobUrls({ ...blobUrlsRef.current });
    }
    setPendings(
      latest.filter(
        (item) =>
          !(item.record && sentUrls.has(item.record.url)) &&
          !(sentIds.has(item.id) && item.status === "error"),
      ),
    );
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

  // ── M5 编辑/重新生成提交流：本地回滚由 hook.replay 在响应 200 后执行；
  // 失败 toast 不动本地（服务端软删未发生，两侧一致无需恢复）──
  // replay 成功后失效历史查询（refetch 对齐服务端软删——replay_rollback 只作用于流式
  // state.messages，历史回显侧（history props）的消息须由 refetch 移除软删行）
  const handleEdit = useCallback(
    async (message: StreamMessage, newText: string, targetRunId: string) => {
      try {
        await replay("EDIT", newText, targetRunId);
        if (state.sessionId) {
          void queryClient.invalidateQueries({ queryKey: ["session-messages", state.sessionId] });
        }
      } catch (error) {
        notify(chatErrorText(error));
      }
    },
    [replay, notify, state.sessionId, queryClient],
  );
  const handleRegenerate = useCallback(
    async (runId: string) => {
      try {
        await replay("REGENERATE", null, runId);
        if (state.sessionId) {
          void queryClient.invalidateQueries({ queryKey: ["session-messages", state.sessionId] });
        }
      } catch (error) {
        notify(chatErrorText(error));
      }
    },
    [replay, notify, state.sessionId, queryClient],
  );

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
    <div
      className="relative flex min-h-0 flex-1 flex-col"
      data-testid="chat-workspace"
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {/* 上下文条：kimi 对话页页头（← 返回课程 · 课程名 chip · 会话标题 · 新建对话） */}
      <div className="flex h-12 shrink-0 items-center gap-2 border-b border-border/80 bg-bg/70 px-5 text-sm backdrop-blur">
        <Link
          href={backHref}
          className="flex items-center gap-1 rounded-lg px-2 py-1 text-muted transition-colors hover:bg-surface-2 hover:text-brand-strong"
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
        <span className="min-w-0 truncate text-muted" data-testid="context-title">
          {contextTitle}
        </span>
        {/* Task 13：顶栏「新建对话」按钮移除——新建入口收敛到侧栏按钮（/chat 同路由
            经信号 reset 干净态，避免导航重挂载丢滚动位置）；此处 ml-auto 保持标题左侧 */}
      </div>

      {/* 消息流滚动区（智能吸底滚动） */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto" data-testid="message-scroll">
        {history && history.status === "pending" && displayMessages.length === 0 ? (
          // 历史回显加载中：消息区骨架（与 Suspense fallback 同形）
          <ChatSkeleton />
        ) : history && history.status === "error" && displayMessages.length === 0 ? (
          // 历史回显失败：页内横幅 + 重试（设计 §1.7 Error）
          <div className="mx-auto w-full max-w-[840px] px-6 py-8">
            <SectionError onRetry={history.retry} />
          </div>
        ) : resumingPlaceholder && isEmpty ? (
          // M6.4：续流进行中且历史为空——「正在继续生成…」占位（避免看起来像坏了；
          // resume 失败静默降级为空态：占位优先于普通空态渲染，回放首帧到达即让位消息流）
          <div
            className="flex h-full flex-col items-center justify-center gap-3 px-6 text-center"
            data-testid="resume-placeholder"
          >
            <AiBadge />
            <p className="text-sm text-muted">正在继续生成…</p>
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
                  className="rounded-full border border-border bg-surface px-4 py-2 text-sm text-muted transition-all hover:-translate-y-0.5 hover:border-brand/40 hover:bg-brand-light hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
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
            onEdit={handleEdit}
            onRegenerate={handleRegenerate}
          />
        )}

        {/* 消息尾错误横幅（设计 §1.5.4 error 事件）：分级操作：retryable=重试（手动重连）+
             重新生成（M7：runId 保留时复用 M5 REGENERATE replay 整轮重开——判死重试耗尽/
             有产出失败保留现场后的手动恢复入口）；replay_failed=重新提问（清空对话引导重问）；
             auth 由全局登出流承接，仅展示文案 */}
        {state.error ? (
          <div
            role="alert"
            data-testid="stream-error-banner"
            className="mx-auto w-full max-w-[840px] px-6 pt-4"
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
              {state.error.kind === "retryable" && state.runId ? (
                /* M7：ERROR 终态 reducer 不清 runId（error/end 分支均保留）——据其发起
                   REGENERATE replay（服务端软删回滚 + 新 run 重新生成；正在回答中/目标失效
                   等 409 由 handleRegenerate 的 toast 分级承接） */
                <button
                  type="button"
                  data-testid="error-regenerate"
                  onClick={() => {
                    if (state.runId) void handleRegenerate(state.runId);
                  }}
                  className="shrink-0 rounded-lg border border-danger/30 bg-surface px-3 py-1.5 text-sm font-medium text-danger transition-colors hover:bg-danger/10 focus-visible:ring-2 focus-visible:ring-danger"
                >
                  重新生成
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

      {/* 拖拽高亮层（Task 12）：文件拖入工作区时点亮（pointer-events-none 保证 drop 落回容器） */}
      {dragActive ? (
        <div
          data-testid="drag-highlight"
          className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center border-2 border-dashed border-brand bg-bg/85"
        >
          <p className="rounded-full border border-brand/40 bg-surface px-5 py-2 text-sm font-medium text-brand-strong">
            松开鼠标上传附件
          </p>
        </div>
      ) : null}

      {/* 吸底输入区：bg/80 + backdrop-blur + 顶部 1px 边框（kimi 对话页形态） */}
      <div className="shrink-0 border-t border-border/80 bg-bg/80 backdrop-blur">
        <div className="mx-auto w-full max-w-[840px] px-6 py-4">
          <ChatInput
            streaming={state.streaming}
            sendDisabled={pendings.some((item) => item.status === "uploading")}
            onSend={handleSend}
            onCancel={() => void cancel()}
            onNotify={notify}
            value={inputValue}
            onValueChange={setInputValue}
            resetKey={inputResetKey}
            onPasteFiles={(files) => void handleFiles(files)}
            /* 附件区（图一扩容形态）：chips 渲染进输入卡内顶部，border-t 与输入行分隔 */
            attachmentsArea={
              pendings.length > 0 ? (
                <AttachmentChips
                  items={pendings}
                  onRemove={removeAttachment}
                  onPreview={setPreviewItem}
                />
              ) : undefined
            }
            attachmentSlot={
              /* ＋附件：单按钮单接口承载图片+文档（2026-08-27 用户拍板合并；G11 白名单合并 accept） */
              <>
                <label
                  htmlFor="attachment-input"
                  title="上传图片 / 文档"
                  aria-label="上传图片或文档"
                  className="mb-1 grid size-9 shrink-0 cursor-pointer place-items-center rounded-full text-subtle transition-colors hover:bg-surface-2 hover:text-brand-strong"
                >
                  <Paperclip size={18} aria-hidden />
                </label>
                <input
                  id="attachment-input"
                  data-testid="file-input"
                  type="file"
                  accept="image/jpeg,image/png,image/gif,image/webp,image/bmp,.pdf,.doc,.docx,.txt,.md"
                  multiple
                  className="sr-only"
                  onChange={(event) => {
                    void handleFiles(event.target.files);
                    // 清空已选，允许同文件重复选择
                    event.target.value = "";
                  }}
                />
              </>
            }
          />
          {/* 底部免责（设计 §1.5.4 输入区 Caption） */}
          <p className="mt-2 text-center text-xs text-subtle">AI 生成内容仅供参考</p>
        </div>
      </div>

      {/* 附件预览弹窗（Task 12）：图片 Zoom / pdf iframe / 其他格式图标卡 */}
      <AttachmentPreviewDialog item={previewItem} onClose={() => setPreviewItem(null)} />
      <ChatToast message={toast} />
    </div>
  );
}
