/**
 * 消息操作栏（复制 + 反馈）测试（Task 12 TDD 先行用例 + Task 13 carry 适配）
 *
 * 覆盖（设计 §1.5.4 消息操作栏 + D9 反馈锁定）：
 * - 复制：clipboard 写入 AI 回答正文 + toast「已复制」（2026-09-03 停止态改版后
 *   CANCELLED 终态正文不再拼后缀，原样复制无剥离逻辑）
 * - 反馈请求体 {sessionId, messageId, isLiked, intentType?}：
 *   intentType 由「本 run 是否出现 sources」推断（有 → knowledge_question，无 → chat）；
 *   历史回显透传真实 intentType 时优先使用（非法值回退推断）
 * - 一次选择后锁定（UNIQUE(user_id,message_id) 语义，不提供撤销）
 * - messageId 为 null（ERROR 终态/取消落库降级窗口）不渲染反馈按钮，仅保留复制；
 *   CANCELLED 携 id（2026-09-03 停止态拍板）反馈入口保留
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
    // toast 断言并入轮询：copyToClipboard 引入多一跳微任务，固定时点断言会竞态
    await vi.waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith("回答正文");
      expect(onNotify).toHaveBeenCalledWith("已复制");
    });
  });

  it("BUG-27 降级：clipboard 不可用（非安全上下文）→ execCommand 中转成功仍「已复制」", async () => {
    // 模拟 HTTP 内网部署：navigator.clipboard 为 undefined
    Object.defineProperty(navigator, "clipboard", { value: undefined, configurable: true });
    const execCommand = vi.fn(() => true);
    Object.defineProperty(document, "execCommand", { value: execCommand, configurable: true });
    renderBar();
    fireEvent.click(screen.getByRole("button", { name: /复制/ }));
    await vi.waitFor(() => {
      expect(execCommand).toHaveBeenCalledWith("copy");
    });
    expect(onNotify).toHaveBeenCalledWith("已复制");
    delete (document as unknown as Record<string, unknown>).execCommand;
  });

  it("BUG-27 降级：两条路径均失败 → toast「复制失败，请手动复制」（不再静默无响应）", async () => {
    Object.defineProperty(navigator, "clipboard", { value: undefined, configurable: true });
    Object.defineProperty(document, "execCommand", { value: () => false, configurable: true });
    renderBar();
    fireEvent.click(screen.getByRole("button", { name: /复制/ }));
    await vi.waitFor(() => {
      expect(onNotify).toHaveBeenCalledWith("复制失败，请手动复制");
    });
    delete (document as unknown as Record<string, unknown>).execCommand;
  });

  it("2026-09-03 停止态：CANCELLED 终态正文原样复制（后缀机制已下线，无剥离逻辑）", async () => {
    renderBar({ text: "回答到一半的内容" });
    fireEvent.click(screen.getByRole("button", { name: /复制/ }));
    await vi.waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith("回答到一半的内容");
    });
  });
});

describe("FeedbackBar act 按钮组样式（Task 11 对齐设计稿 .act）", () => {
  it("三个按钮挂 act-btn 类；选中态金棕/danger 胶囊（hover 上浮由 CSS 承担）", async () => {
    renderBar({ messageId: "m-1" });
    expect(screen.getByRole("button", { name: "复制回答" })).toHaveClass("act-btn");
    const like = screen.getByTestId("feedback-like");
    const dislike = screen.getByTestId("feedback-dislike");
    expect(like).toHaveClass("act-btn");
    expect(like).not.toHaveClass("act-btn--on");
    fireEvent.click(like);
    await waitFor(() => expect(apiMock.postFeedback).toHaveBeenCalled());
    // 锁定后：选中态胶囊类落位
    expect(like).toHaveClass("act-btn--on");
    expect(dislike).not.toHaveClass("act-btn--on-danger");
  });

  it("点踩选中态挂 danger 胶囊类（语义区分）", async () => {
    renderBar({ messageId: "m-1" });
    const dislike = screen.getByTestId("feedback-dislike");
    fireEvent.click(dislike);
    await waitFor(() => expect(apiMock.postFeedback).toHaveBeenCalled());
    expect(dislike).toHaveClass("act-btn--on-danger");
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

  it("messageId 为 null（ERROR 终态/取消落库降级窗口）：不展示反馈按钮，仅保留复制", () => {
    renderBar({ messageId: null });
    expect(screen.queryByRole("button", { name: /有用/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /无用/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /复制/ })).toBeInTheDocument();
  });

  it("2026-09-03 停止态：CANCELLED 终态携带 messageId → 反馈按钮照常渲染可提交", async () => {
    renderBar({ messageId: "msg-cancelled" });
    fireEvent.click(screen.getByRole("button", { name: /有用/ }));
    await vi.waitFor(() => {
      expect(apiMock.postFeedback).toHaveBeenCalledWith({
        sessionId: "s-1",
        messageId: "msg-cancelled",
        isLiked: true,
        intentType: "chat",
      });
    });
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
