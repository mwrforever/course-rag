/**
 * SectionError 页内错误横幅测试（Task 9 TDD 先行用例）
 *
 * 设计 §1.7 Error：页内横幅（danger-soft 底）+ [重试]；503/网络错误文案「服务暂时不可用，请稍后重试」。
 * 供课程列表页与课程工作台共用，语义与首页局部实现一致。
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SectionError } from "./section-error";

describe("SectionError 错误横幅", () => {
  it("渲染警示文案与重试按钮，点击触发重试回调", () => {
    const onRetry = vi.fn();
    render(<SectionError onRetry={onRetry} />);
    const banner = screen.getByRole("alert");
    expect(banner).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
