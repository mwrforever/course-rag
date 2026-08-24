/**
 * QueryProvider 挂载测试（Task 8）：children 需处于 QueryClient 上下文内
 *
 * 以探针组件读取 useQueryClient，验证 Provider 确实提供了 react-query 客户端。
 */
import { QueryClient, useQueryClient } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { QueryProvider } from "./query-provider";

/** 探针：读取上下文中的 QueryClient 实例并回显 */
function Probe() {
  const client = useQueryClient();
  return <span data-testid="client">{client instanceof QueryClient ? "已挂载" : "未挂载"}</span>;
}

describe("QueryProvider", () => {
  it("包裹 children 并提供 QueryClient 上下文", () => {
    render(
      <QueryProvider>
        <Probe />
      </QueryProvider>,
    );
    expect(screen.getByTestId("client")).toHaveTextContent("已挂载");
  });
});
