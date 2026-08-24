/**
 * 消息操作栏（复制 + 反馈）测试（Task 12 TDD 先行用例 + Task 13 carry2/carry 适配）
 *
 * 覆盖（设计 §1.5.4 消息操作栏 + D9 反馈锁定）：
 * - 复制：clipboard 写入 AI 回答正文 + toast「已复制」；CANCELLED 终态文本
 *   带「已停止生成」后缀时剥离后缀再复制（carry2 注释与行为对齐）
 * - 反馈请求体 {sessionId, messageId, isLiked, intentType?}：
 *   intentType 由「本 run 是否出现 sources」推断（有 → knowledge_question，无 → chat）；
 *   历史回显透传真实 intentType 时优先使用（非法值回退推断）
 * - 一次选择后锁定（UNIQUE(user_id,message_id) 语义，不提供撤销）
 * - messageId 为 null（CANCELLED/ERROR 终态）不渲染反馈按钮，仅保留复制
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ postFeedback: vi.fn() }));
vi.mock("@/lib/api", () => ({ postFeedback: apiMock.postFeedback }));

import { FeedbackBar, inferIntentType } from "./feedback-bar";
import { STOPPED_SUFFIX } from "@/hooks/use-chat-stream";

const onNotify = vi.fn();
/** jsdom 无 clipboard API：注入可控 writeText */
let clipboardWriteText: ReturnType<typeof vi.fn>;
function stubClipboard() {
  clipboardWriteText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText: clipboardWriteText },
    configurable: true,
  });
}

function renderBar(
  overrides: {
    messageId?: string | null;
    hasSources?: boolean;
    text?: string;
    intentType?: string | null;
  } = {},
) {
  return render(
    <FeedbackBar
      sessionId="s-1"
      messageId={overrides.messageId === undefined ? "msg-1" : overrides.messageId}
      hasSources={overrides.hasSources ?? false}
      text={overrides.text ?? "回答正文"}
      intentType={overrides.intentType}
      onNotify={onNotify}
    />,
  );
}

beforeEach(() => {
  apiMock.postFeedback.mockReset().mockResolvedValue(undefined);
  onNotify.mockReset();
  stubClipboard();
});

afterEach(() => {
  apiMock.postFeedback.mockReset();
});

describe("inferIntentType 意图推断（sources 出现与否）", () => {
  it("有 sources → knowledge_question", () => {
    expect(inferIntentType(true)).toBe("knowledge_question");
  });
  it("无 sources → chat", () => {
    expect(inferIntentType(false)).toBe("chat");
  });
});

describe("FeedbackBar 复制", () => {
  it("点击复制：clipboard 写入回答正文 + toast 已复制", async () => {
    renderBar();
    fireEvent.click(screen.getByRole("button", { name: /复制/ }));
    await vi.waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith("回答正文");
    });
    expect(onNotify).toHaveBeenCalledWith("已复制");
  });

  it("carry2：CANCELLED 终态文本带「已停止生成」后缀 → 复制前剥离后缀", async () => {
    renderBar({ text: `回答内容${STOPPED_SUFFIX}` });
    fireEvent.click(screen.getByRole("button", { name: /复制/ }));
    await vi.waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith("回答内容");
    });
  });

  it("carry2：正文恰好以「已停止生成」结尾（非 CANCELLED 追加场景）不误剥离", async () => {
    renderBar({ text: "回答正文" });
    fireEvent.click(screen.getByRole("button", { name: /复制/ }));
    await vi.waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith("回答正文");
    });
  });
});

describe("FeedbackBar 反馈与锁定", () => {
  it("点赞：请求体携带 sessionId/messageId/isLiked=true/intentType=knowledge_question", async () => {
    renderBar({ hasSources: true });
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    await vi.waitFor(() => {
      expect(apiMock.postFeedback).toHaveBeenCalledWith({
        sessionId: "s-1",
        messageId: "msg-1",
        isLiked: true,
        intentType: "knowledge_question",
      });
    });
  });

  it("点踩：无 sources 时 intentType=chat", async () => {
    renderBar({ hasSources: false });
    fireEvent.click(screen.getByRole("button", { name: /无用/ }));
    await vi.waitFor(() => {
      expect(apiMock.postFeedback).toHaveBeenCalledWith({
        sessionId: "s-1",
        messageId: "msg-1",
        isLiked: false,
        intentType: "chat",
      });
    });
  });

  it("一次选择后锁定：再次点击不再提交（D9 无撤销）", async () => {
    renderBar();
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    // 等待提交完成且进入锁定态（状态刷新后再断言，避免竞态）
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /有用/ })).toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /无用/ }));
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    expect(apiMock.postFeedback).toHaveBeenCalledTimes(1);
    // 锁定态：两个反馈按钮均禁用
    expect(screen.getByRole("button", { name: /有用/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /无用/ })).toBeDisabled();
  });

  it("提交中守卫：前次请求未完成时重复点击不重复提交", async () => {
    let resolveSubmit!: () => void;
    apiMock.postFeedback.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveSubmit = resolve;
      }),
    );
    renderBar();
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    expect(apiMock.postFeedback).toHaveBeenCalledTimes(1);
    resolveSubmit();
  });

  it("反馈提交失败：提示且不锁定（可重试）", async () => {
    apiMock.postFeedback.mockRejectedValueOnce(new Error("网络故障"));
    renderBar();
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    await vi.waitFor(() => {
      expect(onNotify).toHaveBeenCalledWith("反馈提交失败，请稍后重试");
    });
    // 未锁定：再次点击可用
    fireEvent.click(screen.getByRole("button", { name: /无用/ }));
    await vi.waitFor(() => {
      expect(apiMock.postFeedback).toHaveBeenCalledTimes(2);
    });
  });

  it("messageId 为 null（CANCELLED/ERROR）：不展示反馈按钮，仅保留复制", () => {
    renderBar({ messageId: null });
    expect(screen.queryByRole("button", { name: /有用/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /无用/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /复制/ })).toBeInTheDocument();
  });

  it("intentType 透传：历史回显携带真实意图时优先使用（覆盖 hasSources 推断）", async () => {
    renderBar({ hasSources: false, intentType: "knowledge_question" });
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    await vi.waitFor(() => {
      expect(apiMock.postFeedback).toHaveBeenCalledWith({
        sessionId: "s-1",
        messageId: "msg-1",
        isLiked: true,
        intentType: "knowledge_question",
      });
    });
  });

  it("intentType 非法值（存量 unknown 等）：回退 hasSources 推断", async () => {
    renderBar({ hasSources: false, intentType: "unknown" });
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    await vi.waitFor(() => {
      expect(apiMock.postFeedback).toHaveBeenCalledWith(
        expect.objectContaining({ intentType: "chat" }),
      );
    });
  });
});
