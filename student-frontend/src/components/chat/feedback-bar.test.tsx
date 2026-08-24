/**
 * 消息操作栏（复制 + 反馈）测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 消息操作栏 + D9 反馈锁定）：
 * - 复制：clipboard.writeText(text) + toast「已复制」
 * - 反馈请求体 {sessionId, messageId, isLiked, intentType?}：
 *   intentType 由「本 run 是否出现 sources」推断（有 → knowledge_question，无 → chat）
 * - 一次选择后锁定（UNIQUE(user_id,message_id) 语义，不提供撤销）
 * - messageId 为 null（CANCELLED/ERROR 终态）不展示反馈按钮，仅保留复制
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ postFeedback: vi.fn() }));
vi.mock("@/lib/api", () => ({ postFeedback: apiMock.postFeedback }));

import { FeedbackBar, inferIntentType } from "./feedback-bar";

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

function renderBar(overrides: { messageId?: string | null; hasSources?: boolean } = {}) {
  return render(
    <FeedbackBar
      sessionId="s-1"
      messageId={overrides.messageId === undefined ? "msg-1" : overrides.messageId}
      hasSources={overrides.hasSources ?? false}
      text="回答正文"
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
});
