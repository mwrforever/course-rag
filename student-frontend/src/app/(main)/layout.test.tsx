/**
 * (main) 主站布局测试（Task 8）：顶导壳 + QueryProvider 挂载
 *
 * (auth) 路由组（登录页）无此壳；本布局负责全站顶导与 react-query 服务端状态上下文。
 */
import { QueryClient, useQueryClient } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import MainLayout from "./layout";

/** 探针：验证 children 处于 QueryProvider 上下文内 */
function Probe() {
  const client = useQueryClient();
  return <span data-testid="client">{client instanceof QueryClient ? "已挂载" : "未挂载"}</span>;
}

describe("(main) 主站布局", () => {
  it("渲染顶导品牌标识并挂载 QueryProvider", () => {
    render(
      <MainLayout>
        <Probe />
      </MainLayout>,
    );
    expect(screen.getByRole("link", { name: "课程助手" })).toBeInTheDocument();
    expect(screen.getByTestId("client")).toHaveTextContent("已挂载");
  });
});
