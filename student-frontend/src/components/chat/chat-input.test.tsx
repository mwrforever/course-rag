/**
 * 对话输入区测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 输入区 + §3.2 错误分级）：
 * - Enter 发送 / Shift+Enter 换行 / IME 组合态 Enter 不发送 / 空输入禁用
 * - 发送/停止 morph：streaming 时按钮切换为「停止生成」并触发 onCancel；
 *   图标交叉淡入由 motion spring（180ms）驱动（mock 断言 transition 参数）
 * - 409/503/网络失败分级 toast 文案（onNotify 回调）与失败后输入内容恢复
 * - 自动增高 ≤6 行（rows 按换行数增长、封顶）
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/** motion 假实现：可控 reduced + 记录每次 motion.span 收到的 props（断言 morph spring 参数） */
const motionMock = vi.hoisted(() => ({
  reduce: false as boolean | null,
  received: [] as Array<Record<string, unknown>>,
}));

vi.mock("motion/react", async () => {
  const React = await import("react");
  return {
    useReducedMotion: () => motionMock.reduce,
    motion: {
      span: (props: Record<string, unknown>) => {
        motionMock.received.push(props);
        const { animate, transition, children, ...rest } = props;
        void animate;
        void transition;
        return React.createElement(
          "span",
          rest as React.HTMLAttributes<HTMLSpanElement>,
          children as React.ReactNode,
        );
      },
    },
  };
});

import { ApiError, NetworkError } from "@/lib/api";
import { ChatInput, chatErrorText } from "./chat-input";

const onSend = vi.fn();
const onCancel = vi.fn();
const onNotify = vi.fn();

function renderInput(overrides: { streaming?: boolean; sendDisabled?: boolean } = {}) {
  return render(
    <ChatInput
      streaming={overrides.streaming ?? false}
      sendDisabled={overrides.sendDisabled ?? false}
      onSend={onSend}
      onCancel={onCancel}
      onNotify={onNotify}
    />,
  );
}

/** 输入中文并返回 textarea 元素 */
function typeText(text: string) {
  const textarea = screen.getByRole("textbox") as HTMLTextAreaElement;
  fireEvent.change(textarea, { target: { value: text } });
  return textarea;
}

beforeEach(() => {
  onSend.mockReset().mockResolvedValue(undefined);
  onCancel.mockReset();
  onNotify.mockReset();
  motionMock.reduce = false;
  motionMock.received = [];
});

afterEach(() => {
  motionMock.reduce = false;
  motionMock.received = [];
});

describe("ChatInput 键盘与禁用", () => {
  it("空输入：发送按钮禁用，Enter 不触发发送", () => {
    renderInput();
    const send = screen.getByRole("button", { name: "发送" });
    expect(send).toBeDisabled();
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("输入后 Enter：发送内容并清空输入框", () => {
    renderInput();
    const textarea = typeText("什么是 RAG？");
    fireEvent.keyDown(textarea, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith("什么是 RAG？");
    expect(textarea).toHaveValue("");
    // 清空后发送键回到禁用态
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
  });

  it("Shift+Enter：换行不发送", () => {
    renderInput();
    const textarea = typeText("第一行");
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: true });
    expect(onSend).not.toHaveBeenCalled();
    expect(textarea).toHaveValue("第一行");
  });

  it("IME 组合态 Enter（isComposing）：不发送（中文输入法确认候选不误发）", () => {
    renderInput();
    const textarea = typeText("拼音");
    fireEvent.keyDown(textarea, { key: "Enter", isComposing: true });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("sendDisabled（附件上传中）：发送禁用", () => {
    renderInput({ sendDisabled: true });
    const textarea = typeText("带附件的问题");
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
    fireEvent.keyDown(textarea, { key: "Enter" });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("自动增高 ≤6 行：2 个换行 → rows=3；10 个换行封顶 rows=6", () => {
    renderInput();
    let textarea = typeText("a\nb\nc");
    expect(textarea).toHaveAttribute("rows", "3");
    textarea = typeText("行\n行\n行\n行\n行\n行\n行\n行\n行\n行");
    expect(textarea).toHaveAttribute("rows", "6");
  });
});

describe("ChatInput 发送/停止 morph", () => {
  it("streaming=false：发送按钮 + PaperPlane 图标（morph spring 挂载）", () => {
    renderInput();
    expect(screen.getByRole("button", { name: "发送" })).toBeInTheDocument();
    const props = motionMock.received.at(-1) as Record<string, unknown> | undefined;
    expect(props?.transition).toMatchObject({ type: "spring" });
  });

  it("streaming=true：按钮切换为停止生成，点击触发 onCancel", () => {
    renderInput({ streaming: true });
    const stop = screen.getByRole("button", { name: "停止生成" });
    fireEvent.click(stop);
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "发送" })).not.toBeInTheDocument();
  });

  it("reduced-motion 命中：morph 不挂动画（交叉淡入静态切换）", () => {
    motionMock.reduce = true;
    renderInput({ streaming: true });
    const props = motionMock.received.at(-1) as Record<string, unknown> | undefined;
    expect(props?.transition).toBeUndefined();
  });

  it("streaming 中 Enter：仍尝试发送（触发后端 409 并发冲突路径）", () => {
    renderInput({ streaming: true });
    const textarea = typeText("追问问题");
    fireEvent.keyDown(textarea, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith("追问问题");
  });
});

describe("ChatInput 错误分级提示（设计 §3.2）", () => {
  it("409：toast「当前会话正在回答中」，输入内容恢复供重试", async () => {
    onSend.mockRejectedValueOnce(new ApiError(409, "当前会话正在回答中"));
    renderInput();
    const textarea = typeText("并发问题");
    fireEvent.keyDown(textarea, { key: "Enter" });
    await vi.waitFor(() => {
      expect(onNotify).toHaveBeenCalledWith("当前会话正在回答中");
    });
    expect(textarea).toHaveValue("并发问题");
  });

  it("503：toast「服务暂时不可用，请稍后重试」", async () => {
    onSend.mockRejectedValueOnce(new ApiError(503, "服务不可用"));
    renderInput();
    fireEvent.keyDown(typeText("问题"), { key: "Enter" });
    await vi.waitFor(() => {
      expect(onNotify).toHaveBeenCalledWith("服务暂时不可用，请稍后重试");
    });
  });

  it("网络错误：toast「网络连接失败，请检查网络」", async () => {
    onSend.mockRejectedValueOnce(new NetworkError());
    renderInput();
    fireEvent.keyDown(typeText("断网问题"), { key: "Enter" });
    await vi.waitFor(() => {
      expect(onNotify).toHaveBeenCalledWith("网络连接失败，请检查网络");
    });
  });

  it("其它异常：兜底「发送失败，请稍后重试」", async () => {
    onSend.mockRejectedValueOnce(new Error("未知错误"));
    renderInput();
    fireEvent.keyDown(typeText("未知问题"), { key: "Enter" });
    await vi.waitFor(() => {
      expect(onNotify).toHaveBeenCalledWith("发送失败，请稍后重试");
    });
  });
});

describe("chatErrorText 错误分级映射（页面建议提问 chip 复用）", () => {
  it("409 → 当前会话正在回答中", () => {
    expect(chatErrorText(new ApiError(409, "x"))).toBe("当前会话正在回答中");
  });
  it("503 → 服务暂时不可用，请稍后重试", () => {
    expect(chatErrorText(new ApiError(503, "x"))).toBe("服务暂时不可用，请稍后重试");
  });
  it("NetworkError → 网络连接失败，请检查网络", () => {
    expect(chatErrorText(new NetworkError())).toBe("网络连接失败，请检查网络");
  });
  it("未知异常 → 发送失败，请稍后重试", () => {
    expect(chatErrorText(new Error("e"))).toBe("发送失败，请稍后重试");
  });
});

describe("ChatInput 附件区扩容（设计稿图一形态：附件区卡内顶部 + border-t 分隔）", () => {
  it("attachmentsArea 提供时：附件区渲染于输入行之前且输入行带 border-t 分隔线", () => {
    render(
      <ChatInput
        streaming={false}
        onSend={onSend}
        onCancel={onCancel}
        onNotify={onNotify}
        attachmentsArea={<div data-testid="mock-attachment-chips">chips</div>}
      />,
    );
    expect(screen.getByTestId("mock-attachment-chips")).toBeInTheDocument();
    const area = screen.getByTestId("attachment-area");
    const row = screen.getByTestId("chat-input-row");
    // 附件区在卡内顶部（输入行之前）
    expect(area.compareDocumentPosition(row) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // 分隔线：附件区存在时输入行挂 border-t 细线
    expect(row).toHaveClass("border-t");
  });

  it("无附件区：不渲染占位结构与分隔线", () => {
    renderInput();
    expect(screen.queryByTestId("attachment-area")).not.toBeInTheDocument();
    expect(screen.getByTestId("chat-input-row")).not.toHaveClass("border-t");
  });
});

describe("ChatInput 受控与重置（T12 受控化，T13 新建会话 reset 消费）", () => {
  it("受控模式：外部 value 驱动输入框，键入经 onValueChange 上抛", () => {
    const onValueChange = vi.fn();
    render(
      <ChatInput
        streaming={false}
        onSend={onSend}
        onCancel={onCancel}
        onNotify={onNotify}
        value="预填内容"
        onValueChange={onValueChange}
      />,
    );
    expect(screen.getByRole("textbox")).toHaveValue("预填内容");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "预填内容2" } });
    expect(onValueChange).toHaveBeenCalledWith("预填内容2");
  });

  it("resetKey 变化（受控模式）：onValueChange('') 通知父级清空", () => {
    const onValueChange = vi.fn();
    const { rerender } = render(
      <ChatInput
        streaming={false}
        onSend={onSend}
        onCancel={onCancel}
        onNotify={onNotify}
        value="待清理"
        onValueChange={onValueChange}
        resetKey={0}
      />,
    );
    rerender(
      <ChatInput
        streaming={false}
        onSend={onSend}
        onCancel={onCancel}
        onNotify={onNotify}
        value="待清理"
        onValueChange={onValueChange}
        resetKey={1}
      />,
    );
    expect(onValueChange).toHaveBeenCalledWith("");
  });

  it("resetKey 变化（非受控模式）：内部输入清空", () => {
    const { rerender } = render(
      <ChatInput
        streaming={false}
        onSend={onSend}
        onCancel={onCancel}
        onNotify={onNotify}
        resetKey={0}
      />,
    );
    typeText("待清理内容");
    rerender(
      <ChatInput
        streaming={false}
        onSend={onSend}
        onCancel={onCancel}
        onNotify={onNotify}
        resetKey={1}
      />,
    );
    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("受控模式发送失败：onValueChange 先清空后恢复（供修改重试）", async () => {
    const onValueChange = vi.fn();
    onSend.mockRejectedValueOnce(new ApiError(409, "并发"));
    render(
      <ChatInput
        streaming={false}
        onSend={onSend}
        onCancel={onCancel}
        onNotify={onNotify}
        value="并发问题"
        onValueChange={onValueChange}
      />,
    );
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    await vi.waitFor(() => {
      expect(onValueChange).toHaveBeenCalledWith("");
      expect(onValueChange).toHaveBeenCalledWith("并发问题");
    });
  });
});
