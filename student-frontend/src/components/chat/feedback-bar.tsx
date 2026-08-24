"use client";

/**
 * 消息操作栏（复制 + 有用/无用反馈，设计 §1.5.4 + D9 锁定语义）
 *
 * - end 后浮现（由 message-list 控制透明度过渡，本组件只管内容与交互）
 * - 复制：CANCELLED 终态文本带「已停止生成」后缀，复制前剥离后缀（carry2），
 *   再经 navigator.clipboard 写入纯回答正文 + toast「已复制」
 * - 反馈：POST /student/feedbacks {sessionId, messageId, isLiked, intentType?}
 *   intentType 优先取历史回显透传的真实意图；缺省按「本 run 是否出现 sources」
 *   推断（有 → knowledge_question，无 → chat）
 * - 一次选择后锁定（UNIQUE(user_id,message_id) 约束语义，不提供撤销）；
 *   提交失败时解锁并提示重试
 * - messageId 为 null（CANCELLED/ERROR 终态）不渲染反馈按钮，仅保留复制
 */
import { Copy, ThumbsDown, ThumbsUp } from "@phosphor-icons/react";
import { useState } from "react";
import { STOPPED_SUFFIX } from "@/hooks/use-chat-stream";
import { postFeedback } from "@/lib/api";

/** 操作栏组件 props */
export interface FeedbackBarProps {
  /** 会话 id（反馈请求体；新会话 metadata 到达后必有值） */
  sessionId: string;
  /** end COMPLETED 的 messageId（反馈唯一来源；CANCELLED/ERROR 为 null） */
  messageId: string | null;
  /** 本 run 是否出现 sources（intentType 推断依据） */
  hasSources: boolean;
  /** AI 回答正文（复制内容；CANCELLED 终态含「已停止生成」后缀，复制时剥离） */
  text: string;
  /** 历史回显透传的真实意图（knowledge_question/chat/存量 unknown；缺省按 hasSources 推断） */
  intentType?: string | null;
  /** 提示回调（复制/反馈失败 toast） */
  onNotify(message: string): void;
}

/**
 * 意图推断：sources 出现 → knowledge_question；否则 chat
 * （反馈请求体 intentType 可选字段，仅两个取值）
 *
 * @param hasSources 本 run 是否出现来源卡
 * @returns 推断意图
 */
export function inferIntentType(hasSources: boolean): "knowledge_question" | "chat" {
  return hasSources ? "knowledge_question" : "chat";
}

/**
 * 意图判定：历史回显透传的真实意图（knowledge_question/chat）优先；
 * 缺省或存量非法值（unknown 等）回退 hasSources 推断
 *
 * @param hasSources 本 run 是否出现来源卡
 * @param intentType 历史回显透传的意图（可空）
 * @returns 反馈请求体 intentType
 */
function resolveIntentType(hasSources: boolean, intentType: string | null | undefined) {
  if (intentType === "knowledge_question" || intentType === "chat") {
    return intentType;
  }
  return inferIntentType(hasSources);
}

/**
 * 消息操作栏（复制 + 反馈，反馈一次锁定）
 *
 * @param props 见 FeedbackBarProps
 */
export function FeedbackBar({
  sessionId,
  messageId,
  hasSources,
  text,
  intentType,
  onNotify,
}: FeedbackBarProps) {
  // null=未选择 / true=有用 / false=无用（D9：一次选择后锁定，不提供撤销）
  const [choice, setChoice] = useState<boolean | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const locked = choice !== null;
  const canFeedback = messageId !== null && messageId !== "" && sessionId !== "";

  /** 复制回答正文到剪贴板 + 提示（CANCELLED 终态剥离「已停止生成」后缀，carry2） */
  async function copyAnswer() {
    // 后缀由 useChatStream 在 CANCELLED 终态追加进 text；复制内容应为纯回答正文
    const copyText = text.endsWith(STOPPED_SUFFIX) ? text.slice(0, -STOPPED_SUFFIX.length) : text;
    await navigator.clipboard.writeText(copyText);
    onNotify("已复制");
  }

  /** 提交反馈：成功后锁定；失败解锁并提示重试 */
  async function submitFeedback(isLiked: boolean) {
    if (locked || !canFeedback || submitting) return;
    setSubmitting(true);
    try {
      await postFeedback({
        sessionId,
        messageId: messageId as string,
        isLiked,
        // 意图：历史透传优先，缺省按来源卡推断（设计 §1.5.4 反馈请求体）
        intentType: resolveIntentType(hasSources, intentType),
      });
      setChoice(isLiked);
    } catch {
      // 提交失败：不锁定，提示重试（网络/服务异常场景）
      onNotify("反馈提交失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex items-center gap-1" data-testid="feedback-bar">
      <button
        type="button"
        aria-label="复制回答"
        onClick={() => void copyAnswer()}
        className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-subtle transition-colors hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
      >
        <Copy size={13} aria-hidden />
        复制
      </button>
      {canFeedback ? (
        <>
          {/* 有用：选中态 brand 填充（一次选择后锁定，D9） */}
          <button
            type="button"
            aria-label="有用"
            disabled={locked}
            onClick={() => void submitFeedback(true)}
            className={`flex items-center gap-1 rounded-lg px-2 py-1 text-xs transition-colors disabled:cursor-not-allowed focus-visible:ring-2 focus-visible:ring-brand ${
              choice === true
                ? "bg-brand-soft font-medium text-brand-strong"
                : "text-subtle hover:bg-surface-2 hover:text-text"
            }`}
          >
            <ThumbsUp size={13} weight={choice === true ? "fill" : "regular"} aria-hidden />
            有用
          </button>
          {/* 无用：选中态 danger 填充 */}
          <button
            type="button"
            aria-label="无用"
            disabled={locked}
            onClick={() => void submitFeedback(false)}
            className={`flex items-center gap-1 rounded-lg px-2 py-1 text-xs transition-colors disabled:cursor-not-allowed focus-visible:ring-2 focus-visible:ring-brand ${
              choice === false
                ? "bg-danger/10 font-medium text-danger"
                : "text-subtle hover:bg-surface-2 hover:text-text"
            }`}
          >
            <ThumbsDown size={13} weight={choice === false ? "fill" : "regular"} aria-hidden />
            无用
          </button>
        </>
      ) : null}
    </div>
  );
}
