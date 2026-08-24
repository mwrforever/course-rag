import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import Home from "./page";

// 首页冒烟测试：保障品牌标识与欢迎标题可渲染，防止布局壳或字体挂载意外破坏首页
describe("首页占位", () => {
  it("渲染品牌徽标与欢迎标题", () => {
    render(<Home />);
    expect(screen.getByText("课程助手")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("你好，欢迎回到学习空间");
  });
});
