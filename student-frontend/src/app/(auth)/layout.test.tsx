/**
 * (auth) 认证路由组布局测试（Task 8 补齐）：无顶导壳 + 认证渐变背景
 *
 * 本布局仅承载 children（登录页等认证场景），与 (main) 顶导壳互斥。
 */
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import AuthLayout from "./layout";

describe("(auth) 认证路由组布局", () => {
  it("渲染 children（无顶导）", () => {
    render(
      <AuthLayout>
        <p>登录页内容</p>
      </AuthLayout>,
    );
    expect(screen.getByText("登录页内容")).toBeInTheDocument();
  });
});
